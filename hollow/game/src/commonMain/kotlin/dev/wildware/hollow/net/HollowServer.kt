package dev.wildware.hollow.net

import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.hollow.FoxWaves
import dev.wildware.hollow.HollowControls
import dev.wildware.hollow.HollowGame
import dev.wildware.hollow.HollowHost
import dev.wildware.hollow.HollowIntents
import dev.wildware.hollow.HollowLevel
import dev.wildware.hollow.HollowMovement
import dev.wildware.hollow.Player
import dev.wildware.hollow.PlayerControlSystem
import dev.wildware.udea.core.NetRole
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.SnapshotRing
import dev.wildware.udea.core.snapshot.SnapshotTimeTravel
import dev.wildware.udea.core.snapshot.WorldSnapshot
import dev.wildware.udea.net.input.MoveInput
import dev.wildware.udea.net.replication.BandwidthBudget
import dev.wildware.udea.net.replication.ReplicationServer
import dev.wildware.udea.net.transport.LoopbackNetwork
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.transport.Transport
import dev.wildware.udea.net.wire.ProtocolDescriptor
import dev.wildware.udea.render.input.Intent
import dev.wildware.udea.render.input.IntentSource
import dev.wildware.udea.render.input.IntentState

/**
 * The authoritative Hollow server: a headless [HollowGame] playing a level, replicating its world to
 * every client over [transport] (issue #249), and simulating the character each of them drives
 * (issue #250).
 *
 * Written against [Transport] alone, so the in-process harness a test drives and a UDP socket are
 * the same thing to it.
 *
 * One [tick] is: consume what clients sent, step the world, capture the ring, send every client its
 * delta. The order is the contract - input **before** the step so the command a client sent for
 * this tick is the one `SimPhase.Intent` samples, the capture **after** it so what goes on the wire
 * is the world as it ended the tick, and the broadcast against the slot the capture just committed.
 *
 * ## Clients send input. Only input.
 *
 * A client's datagram reaches [ReplicationServer.onPacket] and nowhere else, and that method
 * understands exactly two things: acknowledgements, and `@InputCommand` frames into a per-client
 * jitter buffer. There is no path from a received datagram to a replicated component field, which
 * is what makes "a client cannot own its own position" structural rather than a rule.
 *
 * The consumed command becomes an `IntentSource` - the **same** seam a keyboard and an agent's
 * `input.*` tools are wired through - so a remote player drives [PlayerControlSystem] down the
 * identical code path a local one does.
 *
 * ## One character per connection
 *
 * [addClient] gives each peer a character of its own: one standing in the clearing that nobody is
 * driving, if a player left one behind, and a freshly spawned one otherwise. The peer's id is
 * written into `Player.owner`, which is that component's one `@Net` field, so a client can tell its
 * own character from the other one. [removeClient] releases the character rather than destroying
 * it - it stands in the clearing, its axis zeroed by [PlayerControlSystem], until somebody takes it
 * over - which is what stops a server that has been joined and left a hundred times holding a
 * hundred idle humans.
 */
public class HollowServer(
    private val transport: Transport,
    budget: BandwidthBudget = BandwidthBudget(),
    mtu: Int = LoopbackNetwork.DEFAULT_MTU,
    level: ByteArray = HollowLevel.bundledBytes(),
    /** The fox waves this session sends (issue #251), or null for none. */
    waves: FoxWaves? = FoxWaves.DEFAULT,
    /** A new match's seed, or null to draw from the level's own streams. See [HollowGame.seed]. */
    matchSeed: Long? = null,
) : AutoCloseable {

    /** The server's own game, headless, with the level loaded. Closed with this session. */
    private val opened: HollowHost =
        HollowGame.build(RenderMode.Headless, level = level, role = NetRole.Server, waves = waves)
            .also { HollowGame.seed(it.host, matchSeed) }

    /** The authoritative simulation. */
    public val host: GameHost get() = opened.host

    private val travel: SnapshotTimeTravel = checkNotNull(host.game.simulation.travel as? SnapshotTimeTravel) {
        "a replicating server needs a snapshot ring: it is the baseline store (spec 3.1), and " +
            "HollowGame.definition supplies one"
    }

    /** The baselines every client is sent deltas against. */
    internal val ring: SnapshotRing get() = travel.ring

    /** What replicates. */
    internal val registry: ComponentRegistry get() = ring.registry

    /** This build's protocol. */
    public val protocol: ProtocolDescriptor = HollowNet.protocol(registry)

    /** The replication half: baselines, priority and the wire. */
    public val replication: ReplicationServer = ReplicationServer(
        registry = registry,
        protocol = protocol,
        transport = transport,
        ring = ring,
        budget = budget,
        // The clearing's dressing behind everything that moves: see `HollowRelevancy` for the
        // stall it prevents (issue #251).
        relevancy = HollowRelevancy(host.world, host.ctx[CoreModule.NET_IDS]),
        mtu = mtu,
    )

    /** One seat per connection, in join order. */
    private val seats = LinkedHashMap<PeerId, Seat>()

    /** Character raw id to seat, so the intent router is a hash lookup and not a walk of the seats. */
    private val byCharacter = HashMap<Int, Seat>()

    /** How many characters this session has spawned. Only used to space them out at spawn. */
    private var spawned: Int = 0

    /**
     * Datagrams [onPacket] could not decode. Counted and dropped rather than thrown: a malformed or
     * hostile datagram must not stop the server for everybody else.
     */
    internal var malformedPackets: Long = 0L
        private set

    /** The server's simulation tick. */
    public val tick: Tick get() = host.tick

    init {
        // This world's control system, not a process global, so two servers in one process route
        // their own peers' hands to their own characters.
        host.world.system<PlayerControlSystem>().intents = HollowIntentRouter(byCharacter)
    }

    /**
     * Registers [peer], gives it a character, and hands back the character's [NetId]. Idempotent.
     *
     * Called between ticks - a session adds a client from its network pump, before it steps - so
     * the character is in the world immediately rather than at the next barrier drain.
     */
    public fun addClient(peer: PeerId): NetId {
        seats[peer]?.let { return it.character }
        replication.addClient(peer)
        val character = unownedCharacter()?.also { stamp(it, peer.raw) } ?: spawnCharacter(peer)
        val seat = Seat(
            peer = peer,
            character = character,
            source = HollowNetIntentSource(),
            intent = Intent(host.ctx[IntentState.KEY].bindings.catalog),
        )
        seats[peer] = seat
        byCharacter[character.raw] = seat
        return character
    }

    /**
     * Drops [peer]: it stops being replicated to, and its baselines are forgotten.
     *
     * Needed the moment the transport is a socket. A disconnected client that is still registered is
     * packed and sent a datagram every tick for the life of the process; worse, `UdpTransport`
     * recycles peer slots, so the next connection into that slot would be delta-encoded against a
     * baseline belonging to somebody who has left - which decodes cleanly and is wrong in every
     * field.
     *
     * @return true when [peer] was joined.
     */
    public fun removeClient(peer: PeerId): Boolean {
        val seat = seats.remove(peer) ?: return false
        replication.removeClient(peer)
        byCharacter.remove(seat.character.raw)
        stamp(seat.character, Player.UNOWNED)
        return true
    }

    /** The connected clients, in the order they joined. */
    public fun clients(): List<PeerId> = seats.keys.toList()

    /** The character [peer] drives, or null when it has not joined. */
    public fun characterOf(peer: PeerId): NetId? = seats[peer]?.character

    /** A datagram from [from]: acknowledgements, which move that client's baseline on, and input. */
    public fun onPacket(from: PeerId, buffer: ByteArray, offset: Int, length: Int) {
        try {
            replication.onPacket(from, buffer, offset, length)
        } catch (malformed: RuntimeException) {
            malformedPackets++
        }
    }

    /** One step: consume input, simulate, capture, and send every client its delta. */
    public fun tick() {
        drainInput()
        host.run(1)
        travel.captureNow()
        replication.broadcast(state())
    }

    /** The world as the ring captured it at the current tick: what was just sent. */
    internal fun state(): WorldSnapshot = stateAt(host.tick)

    /**
     * The captured world at [at] exactly.
     *
     * Exactly, and not "nearest at or before": an agreement check against the newest capture would
     * be asserting that replication is instantaneous rather than that it is correct. A client can
     * only ever hold a tick the server has already left.
     */
    internal fun stateAt(at: Tick): WorldSnapshot {
        val slot = checkNotNull(ring.nearestAtOrBefore(at)) { "the server's ring holds nothing at or before $at" }
        check(slot.tick == at) { "the ring's nearest slot is ${slot.tick}, not $at" }
        return slot
    }

    /**
     * Consumes exactly one command per client per tick, into that client's own [Intent].
     *
     * One buffer, one seat, one character, and no crossing. A peer whose packet has not arrived
     * consumes `null` and its [Intent] is cleared, which leaves that character standing still rather
     * than repeating last tick's axis - the jitter buffer's own starvation repeat is what makes that
     * rare, and pretending it did not happen is what would make a lagging player slide.
     */
    private fun drainInput() {
        for (seat in seats.values) {
            seat.source.pending = replication.jitterOf(seat.peer).consume(host.tick)
            seat.intent.clear()
            seat.source.sample(seat.intent)
        }
    }

    /**
     * A `Player` entity in the world that no seat is driving, or null when there is none.
     *
     * Walks the world rather than a list of this session's own spawns, so a character left behind by
     * a peer that disconnected is found by the same code that would find one placed in the level.
     */
    private fun unownedCharacter(): NetId? {
        val netIds = host.ctx[CoreModule.NET_IDS]
        val entities = host.world.family { all(Player) }.entities
        for (index in 0 until entities.size) {
            val id = netIds.netIdOf(entities[index])
            if (!byCharacter.containsKey(id.raw)) return id
        }
        return null
    }

    /**
     * Spawns one more character for a joining peer, offset from the last.
     *
     * Offset because two characters on the same spot are one human as far as a player is concerned,
     * and "the second player cannot see themselves" is indistinguishable from "the second player is
     * not being simulated". [SPACING] is wider than two characters, so neither starts inside the
     * other and the solver has nothing to push apart on the first tick.
     */
    private fun spawnCharacter(peer: PeerId): NetId {
        val id = Player.spawn(
            world = host.world,
            netIds = host.ctx[CoreModule.NET_IDS],
            x = SPAWN_X + spawned * SPACING,
            y = SPAWN_Y,
            owner = peer.raw,
        )
        spawned++
        return id
    }

    /** Writes [owner] onto [character]'s replicated `Player`. */
    private fun stamp(character: NetId, owner: Int) {
        val entity = host.ctx[CoreModule.NET_IDS].resolveOrNull(character) ?: return
        with(host.world) { entity.getOrNull(Player)?.owner = owner }
    }

    override fun close() {
        opened.close()
        transport.close()
    }

    override fun toString(): String = "HollowServer(tick=${host.tick}, clients=${seats.size})"

    /**
     * One connection's character and one connection's hands, held together.
     *
     * A class rather than three parallel maps, because the invariant that matters is that they move
     * together: a seat is created, reclaimed and dropped as one thing, and a peer holding a
     * character whose intent belongs to somebody else is the defect this shape prevents.
     */
    private class Seat(
        val peer: PeerId,
        val character: NetId,
        /** This peer's command, presented to the simulation as an ordinary `IntentSource`. */
        val source: HollowNetIntentSource,
        /** Filled from [source] once per tick and handed to [PlayerControlSystem] for [character]. */
        val intent: Intent,
    )

    /** [PlayerControlSystem]'s router over [byCharacter]. A class, so it names what it reads. */
    private class HollowIntentRouter(private val seats: Map<Int, Seat>) : HollowIntents {
        override fun intentFor(self: NetId): Intent? = seats[self.raw]?.intent
    }

    public companion object {

        /** Where the first character stands: the middle of the clearing, which nothing else is in. */
        public const val SPAWN_X: Float = 0f

        /** @see SPAWN_X */
        public const val SPAWN_Y: Float = 0f

        /** World units between one spawned character and the next. */
        public const val SPACING: Float = 1.5f
    }
}

/**
 * A `MoveInput` presented to the simulation as an ordinary `IntentSource`.
 *
 * Nothing downstream knows the axis came off a socket. `IntentSampleSystem` calls [sample] at the
 * top of `SimPhase.Intent` exactly as it does for a keyboard's `DeviceIntent` and an agent's
 * `InjectedIntent`, and `PlayerControlSystem` reads the result - so a remote player's character is
 * steered by the same code, in the same phase, as a local one's.
 *
 * A null [pending] samples nothing, which leaves the intent idle: that is what a client whose
 * packets have not arrived yet should look like, and the jitter buffer's own starvation repeat is
 * what makes it rare.
 */
public class HollowNetIntentSource : IntentSource {

    /** The command this tick. Set by the session immediately before the step. */
    public var pending: MoveInput? = null

    override fun sample(into: Intent) {
        val command = pending ?: return
        into.setAxis(HollowControls.MOVE_AXIS, command.moveX, command.moveY)
        into.setPressed(HollowControls.RUN_ACTION, command.buttons and HollowMovement.RUN_BUTTON != 0)
    }

    override fun toString(): String = "HollowNetIntentSource($pending)"
}
