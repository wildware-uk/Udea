package dev.wildware.moba.net

import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.moba.MobaControls
import dev.wildware.moba.MobaGame
import dev.wildware.moba.Player
import dev.wildware.moba.Position
import dev.wildware.moba.ability.Combatant
import dev.wildware.moba.ability.Teams
import dev.wildware.moba.PlayerControlSystem
import dev.wildware.moba.PlayerIntents
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.moba.level.MobaBlueprints
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.SnapshotRing
import dev.wildware.udea.core.snapshot.SnapshotTimeTravel
import dev.wildware.udea.core.snapshot.WorldSnapshot
import dev.wildware.udea.net.input.MoveInput
import dev.wildware.udea.net.replication.BandwidthBudget
import dev.wildware.udea.net.relevancy.FogOfWar
import dev.wildware.udea.net.relevancy.VisionGrid
import dev.wildware.udea.net.replication.RelevancySet
import dev.wildware.udea.net.replication.ReplicationServer
import dev.wildware.udea.net.transport.LoopbackNetwork
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.transport.Transport
import dev.wildware.moba.ability.AbilityActivationSink
import dev.wildware.moba.ability.AbilityRpc
import dev.wildware.udea.core.blueprint.blueprints
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.gas.Abilities
import dev.wildware.udea.gas.AbilityActivation
import dev.wildware.udea.gas.ActivationResult
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.gas.GameplayEffects
import dev.wildware.udea.gas.GasServices
import dev.wildware.moba.ability.ChampionOwnership
import dev.wildware.udea.net.bits.BitBufferReader
import dev.wildware.udea.net.rpc.RpcRateLimiter
import dev.wildware.udea.net.rpc.RpcRefusal
import dev.wildware.udea.net.rpc.RpcRegistry
import dev.wildware.udea.net.rpc.RpcServer
import dev.wildware.udea.net.wire.FrameReader
import dev.wildware.udea.net.wire.MessageType
import dev.wildware.udea.net.wire.PacketHeader
import dev.wildware.udea.net.wire.ProtocolDescriptor
import dev.wildware.udea.render.input.Intent
import dev.wildware.udea.render.input.IntentSource
import dev.wildware.udea.render.input.IntentState

/**
 * `moba` as an **authoritative server**: it owns the simulation and replicates it to clients.
 *
 * This is the sentence `MobaServer`'s KDoc used to have to retract. The host underneath is the
 * ordinary [MobaGame.host] every other entry point builds - identical modules, identical scene,
 * identical [dev.wildware.udea.core.loop.GameLoop] - and everything network about it is the
 * three lines of [tick]: consume what clients sent, run the tick, replicate what happened.
 *
 * ## The ring is the baseline store, and there is not a second one
 *
 * [ReplicationServer] is handed [ring], which is the *same* [SnapshotRing] that backs
 * `time.rewind` on this host - reached through the definition's own [SnapshotTimeTravel], never
 * rebuilt. Spec 3.1 requires exactly that, and it is not a saving: a per-client shadow copy of
 * the world is what makes a server's memory scale with players instead of with entities, and it
 * is a second structure that can disagree with the first.
 *
 * A capture is forced every tick rather than left to `EngineConfig.snapshotIntervalTicks`,
 * because a baseline a client acked has to still be *in* the ring when the next delta is written
 * against it. The cadence's own captures are idempotent per tick, so this does not double up;
 * what it costs is a denser dense window, which is 120 ticks either way.
 *
 * ## Clients send input. Only input.
 *
 * A client's datagram reaches [ReplicationServer.onPacket] and nowhere else, and that method has
 * exactly two branches: acks, and `@InputCommand` frames into a per-client
 * [dev.wildware.udea.net.input.JitterBuffer]. There is no path from a received datagram to a
 * replicated component field. That is what makes "a client cannot own its own position"
 * structural rather than a rule; the old stack's `PacketUtil.kt:148` carried
 * `// TODO validate the sender!` because in that design there was something to validate.
 *
 * The consumed command becomes an [IntentSource] - the *same* seam a keyboard and an agent's
 * `input.*` tools are wired through - so a remote player drives `PlayerControlSystem` down the
 * identical code path a local one does.
 *
 * ## Every connection gets a champion of its own
 *
 * There are no spectators. [addClient] gives each peer a `Player` entity it alone drives: the
 * level's authored one for the first joiner, and a soldier spawned beside it for everybody after
 * - the same [dev.wildware.moba.level.MobaBlueprints.soldier] the level is full of, so the second
 * human plays the same unit the first one does. The peer's id is written into
 * [dev.wildware.moba.Player.owner], which is the one `@Net` field of that component, so a client
 * can tell its own champion from the other one; and it is recorded in [ownership], which is what
 * the generated RPC guard reads. Those are two different jobs and deliberately two different
 * places - the replicated field is what a window draws, the map is what refuses a datagram.
 *
 * Each peer also gets an [Intent] of its own, filled from that peer's jitter buffer alone and
 * handed to [PlayerControlSystem] through [PlayerIntents]. That is the whole of "two humans can
 * play": before it, one global `IntentState` was written from one connection's command and read
 * by every `Player` in the world, so a second champion would have mirrored the first.
 *
 * What is **not** here: a champion is not destroyed when its peer leaves. [removeClient] releases
 * it, and the next joiner reclaims the same entity rather than growing the roster on every
 * reconnect. A departed player's soldier therefore stands in the level, unowned and idle
 * ([PlayerControlSystem] zeroes an unowned champion's axis), until somebody takes it over. That
 * is a stated cost, not an oversight: destroying it is a structural change to the world from
 * outside a system, which is a barrier action and a wave of its own.
 */
public class MobaHostSession(

    /** Where datagrams go. Any [Transport]: loopback, simulated, or a socket. */
    private val transport: Transport,

    /** Payload ceiling per datagram. */
    budget: BandwidthBudget = BandwidthBudget(),

    /**
     * Largest datagram this session will build, in bytes.
     *
     * Defaulted to the network's own 1200, which is what a real link carries. It is a parameter
     * because the agreement proof raises it: at 1200 the packer defers entities that do not fit
     * and a client's world is legitimately a *mix* of server ticks, which is correct replication
     * and not a state two peers can be hashed against each other on.
     */
    mtu: Int = LoopbackNetwork.DEFAULT_MTU,

    /**
     * Server-side fog of war, or null for the historical everything-is-relevant behaviour.
     *
     * **Off by default, and that is a decision rather than an oversight.** Turning it on changes
     * what a client is *allowed to hold*, so both of this game's headline proofs - the 28-unit
     * roster agreement in `MobaUdpTwoProcessTest` and the two windows seeing each other's
     * champion 1700 units apart in `MobaTwoPlayerTest` - assert the opposite of what fog does. A
     * default that silently broke them would be a worse outcome than a switch.
     *
     * [MobaHostSession.fogOfWar] builds a sensible one over this level. `MobaClient host` turns
     * it on from `-Dudea.moba.fog=<radius>`, and `MobaFogTest` asserts what it does to a real
     * client's world.
     */
    public val fog: FogOfWar? = null,

    /**
     * How far a champion sees, in world units. Only read when [fog] is non-null.
     *
     * Per-entity rather than a `FogSettings` field because sight is a *game* number: a champion,
     * a tower and a ward all see different distances, and `FogOfWar.observe` takes one per body
     * for exactly that reason.
     */
    public val championSight: Float = DEFAULT_CHAMPION_SIGHT,
) : AutoCloseable {

    /** The authoritative simulation. Headless: a dedicated server draws nothing. */
    public val host: GameHost = MobaGame.host(RenderMode.Headless)

    /**
     * The level's authored champion. The first client to join claims it.
     *
     * Still exactly one at construction - the level has one `Player` in it and [MobaEntry.seed]
     * refuses any other number - which is why this is resolved here, before any peer can join
     * and spawn a second.
     */
    public val playerId: NetId = MobaEntry.seed(host)

    private val travel: SnapshotTimeTravel = checkNotNull(
        host.game.simulation.travel as? SnapshotTimeTravel,
    ) {
        "a replicating server needs a snapshot ring: the ring is the baseline store (spec 3.1), " +
            "and MobaGame.definition supplies one through `snapshotTimeTravel`"
    }

    /** The one ring. Rewind reads these slots; so does every client's delta baseline. */
    public val ring: SnapshotRing get() = travel.ring

    /** The component types this session speaks. Taken from the ring, so there is one list. */
    public val registry: ComponentRegistry get() = ring.registry

    /** This build's protocol. A client on another build is refused by name. */
    public val protocol: ProtocolDescriptor = MobaNet.protocol(registry)

    /** The packer. Handed the ring, never a copy of it. */
    public val replication: ReplicationServer = ReplicationServer(
        registry = registry,
        protocol = protocol,
        transport = transport,
        ring = ring,
        budget = budget,
        relevancy = fog ?: RelevancySet.ALL_VISIBLE,
        mtu = mtu,
    )

    /**
     * One seat per connection, in join order.
     *
     * A `LinkedHashMap` so [clients] and [controllingPeer] still answer join order, and so a
     * lookup by peer on the receive path is not a scan.
     */
    private val seats = LinkedHashMap<PeerId, Seat>()

    /** Champion raw id to seat, so [PlayerIntents] is a hash lookup and not a walk of the seats. */
    private val byChampion = HashMap<Int, Seat>()

    /** How many champions this session has spawned. Only used to space them out at spawn. */
    private var spawned: Int = 0

    /**
     * Datagrams from a connected peer that could not be parsed at all.
     *
     * A counter and not a log line: a client that floods junk would otherwise write the server's
     * disk full, and the number is what a test asserts on. See [onPacket].
     */
    public var malformedPackets: Long = 0L
        private set

    /**
     * Where this session's accepted activations go. Compared by identity in [close].
     *
     * Held as a field rather than passed as a lambda, because `AbilityRpc.sink` is process state
     * and the only way to hand it back correctly is to know which value was ours.
     */
    private val activationSink: AbilityActivationSink =
        AbilityActivationSink { self, slot -> activate(self, slot) }

    /**
     * This session's champion-to-hands routing, installed on this world's control system.
     *
     * A `Player` entity with no seat - the level's authored champion before anybody joins, or one
     * whose peer left - answers null, and the control system leaves it standing still.
     */
    private val router: PlayerIntents = PlayerIntents { self -> byChampion[self.raw]?.intent }

    /**
     * Which connection owns which champion. The generated ownership guard reads this and nothing
     * else, and every id it has not been told about answers [PeerId.SERVER] - which is every AI
     * unit on the field, every arrow in flight, and every id that does not exist.
     */
    public val ownership: ChampionOwnership = ChampionOwnership()

    /** Every RPC this build speaks. A client shares it, so the wire indices agree. */
    public val rpcRegistry: RpcRegistry = AbilityRpc.registry()

    /**
     * The half of the session that refuses a client's RPC, and the reason this class parses a
     * datagram before handing it on.
     *
     * `ReplicationServer.onPacket` deliberately understands exactly two things - acks and input -
     * and drops everything else on the floor, which is what makes "a client cannot write a
     * component" structural. So an `@Rpc` frame has to be picked out here, ahead of it. The cost
     * is that the fixed header is parsed twice per datagram; the alternative is a third branch
     * inside `udea-net`'s receive path, and a third branch there is exactly where the old stack's
     * `PacketUtil.kt:148` `// TODO validate the sender!` used to live.
     */
    public val rpc: RpcServer = RpcServer(
        rpcRegistry,
        ownership,
        RpcRateLimiter(ticksPerSecond = TICKS_PER_SECOND, rpcCount = rpcRegistry.size),
    )

    /** The most recent refusal, for a log line or an assertion. Null while nothing was refused. */
    public var lastRefusal: RpcRefusal? = null
        private set

    init {
        // The remote players' hands, one pair per connection. Installed on **this world's**
        // system instance rather than through the single `IntentState`, which is what stops a
        // second connection driving the first one's champion. `IntentState` is left with its
        // default source: a dedicated server has no keyboard, and nothing else reads it.
        host.world.system<PlayerControlSystem>().intents = router
        // Accepted activations reach this world's ability path and no other. `AbilityRpc.sink` is
        // process state - the honest cost of a top-level `@Rpc` body, stated in its own KDoc - so
        // it is *also* rebound around every `rpc.receive` call in `dispatchRpcs`. This line is
        // what makes a session that has received nothing yet answer correctly anyway.
        AbilityRpc.bind(activationSink)
    }

    /** The tick the server is about to run. */
    public val tick: Tick get() = host.tick

    /**
     * Registers [peer] and gives it a champion of its own. Idempotent.
     *
     * The champion is an existing `Player` entity nobody is driving where there is one - the
     * level's authored champion for the first joiner, a champion a departed peer left behind for
     * a later one - and a freshly spawned soldier otherwise. Reclaiming before spawning is what
     * stops a server that has been joined and left a hundred times holding a hundred idle
     * soldiers.
     *
     * A spawn is a barrier action, so the entity itself does not exist until the next [tick]
     * drains it. The [NetId] is reserved immediately, which is why ownership can be recorded here
     * and why a reserved id cannot be handed to a second peer joining on the same tick.
     *
     * @return the champion [peer] now drives.
     */
    public fun addClient(peer: PeerId): NetId {
        seats[peer]?.let { return it.champion }
        replication.addClient(peer)
        val champion = unownedChampion() ?: spawnChampion(peer)
        val seat = Seat(
            peer = peer,
            champion = champion,
            source = NetIntentSource(),
            intent = Intent(host.ctx[IntentState.KEY].bindings.catalog),
        )
        seats[peer] = seat
        byChampion[champion.raw] = seat
        // Two records of the same fact, for two readers that must not share one. `ownership` is
        // read by generated code to refuse a datagram; `Player.owner` is replicated so a window
        // can point its camera. A client that edited its own copy of the field changes what it
        // draws and is refused exactly as before.
        ownership.assign(champion, peer)
        stamp(champion, peer.raw)
        // Fog is per team, and a client with no team is shown nothing (`FogOfWar` fails closed).
        // A champion spawned this tick has no `Combatant` yet, so the team is read where it is
        // already known: `Player.spawn` puts every champion on `Teams.SOLDIER`.
        fog?.assign(peer, championTeam(champion))
        return champion
    }

    /** [champion]'s team if the entity exists yet, and the champion team otherwise. */
    private fun championTeam(champion: NetId): Int {
        val entity = host.ctx[CoreModule.NET_IDS].resolveOrNull(champion) ?: return Teams.SOLDIER
        return with(host.world) { entity.getOrNull(Combatant)?.teamId ?: Teams.SOLDIER }
    }

    /**
     * Drops [peer]: it stops being replicated to, and its baselines are forgotten.
     *
     * Needed the moment the transport is a socket rather than an in-process link. A disconnected
     * client that is still registered is packed and sent a datagram every tick for the life of
     * the process; worse, `UdpTransport` recycles peer slots, so the *next* connection into that
     * slot would inherit the dead client's acked baseline ticks and be delta-encoded against
     * state belonging to somebody who has left. That decodes cleanly and is wrong in every field.
     *
     * Its champion is **released, not destroyed**: it stays in the world unowned, its axis zeroed
     * by `PlayerControlSystem`, and [addClient] hands it to the next joiner. Nobody else's
     * champion moves - a departure used to promote the next peer onto the leaver's unit, which
     * was the only sensible rule while there was one unit to have.
     *
     * @return true when [peer] was joined.
     */
    public fun removeClient(peer: PeerId): Boolean {
        val seat = seats.remove(peer) ?: return false
        replication.removeClient(peer)
        ownership.release(peer)
        byChampion.remove(seat.champion.raw)
        stamp(seat.champion, Player.UNOWNED)
        return true
    }

    /** Every joined peer, in join order. */
    public fun clients(): List<PeerId> = seats.keys.toList()

    /** The champion [peer] drives, or null when it has not joined. */
    public fun championOf(peer: PeerId): NetId? = seats[peer]?.champion

    /**
     * The first peer still joined, or null while nobody has.
     *
     * It no longer means "the one connection that can play". Every seat drives a champion; this
     * is only the peer that claimed the level's authored one, and it is kept because a caller
     * that wants *a* client - a report line, a proof - should not have to know that.
     */
    public fun controllingPeer(): PeerId? = seats.keys.firstOrNull()

    /**
     * Feeds one received datagram in: RPCs through the ownership guard, then acks and input.
     *
     * There is still no branch below this that can write a replicated component field out of a
     * client's bytes. What an `@Rpc` frame may do is ask for an activation on an entity the
     * sender **owns**, and the generated `receive` compares the sender against [ownership] before
     * the body is reached. That comparison is the one the old engine did not have.
     *
     * ## A connected client must not be able to end the run
     *
     * Both halves are guarded, and the second one was not. [dispatchRpcs] has always caught its
     * own parse failures; `ReplicationServer.onPacket` reads a [PacketHeader] and then walks
     * length-prefixed frames out of the same attacker-supplied bytes, and a truncated header, a
     * frame length past the end of the slice or a component index that does not exist all throw
     * out of it. Unguarded, one datagram from any peer that has completed the handshake ends the
     * server's tick - a denial of service dressed as a stack trace, and the exact shape of the
     * old stack's `PacketUtil.kt:148`.
     *
     * Dropped and counted, never rethrown and never logged per packet: a peer that can make the
     * server write a line can make it write a disk full. [malformedPackets] is the signal, and
     * `MobaHostileClientTest` fires forty junk payloads from a connected peer and requires the
     * session to still be ticking and still replicating afterwards.
     *
     * @return the refusal, when the datagram carried an RPC that was refused. Null otherwise.
     */
    public fun onPacket(from: PeerId, buffer: ByteArray, offset: Int, length: Int): RpcRefusal? {
        val refusal = dispatchRpcs(from, buffer, offset, length)
        try {
            replication.onPacket(from, buffer, offset, length)
        } catch (malformed: RuntimeException) {
            malformedPackets++
        }
        return refusal
    }

    /**
     * Walks the datagram's frames for [MessageType.Rpc] and hands each to [rpc].
     *
     * Malformed bytes are dropped rather than thrown. A datagram is attacker-controlled, and a
     * parse failure that propagated would let one hostile packet end the server's tick - which is
     * a denial of service dressed as a stack trace.
     */
    private fun dispatchRpcs(from: PeerId, buffer: ByteArray, offset: Int, length: Int): RpcRefusal? {
        var refusal: RpcRefusal? = null
        // `AbilityRpc.sink` is process-wide, because an `@Rpc` body is a top-level function and a
        // datagram carries arguments and no receiver. Rebinding it around this call - and putting
        // back whatever was there, which on a one-session process is this session's own sink and
        // costs two field writes - narrows the global to the dynamic extent of one dispatch, so
        // two `MobaHostSession`s in one JVM each activate on their **own** world. The remaining
        // limitation is stated rather than hidden: this is not thread-safe. Two sessions dispatch-
        // ing concurrently on different threads would still interleave, and closing that needs
        // `activateAbility` to be handed a session, which is a change to the generated RPC
        // signature in `udea-codegen` and not to this file. Every driver in this tree - the
        // harness, the UDP loop, the render thread - dispatches on one thread.
        val previous = AbilityRpc.sink
        AbilityRpc.bind(activationSink)
        try {
            val src = BitBufferReader(buffer, offset, length)
            val header = PacketHeader.read(src)
            if (header.protoHash != protocol.protoHash) return null
            val walker = FrameReader(buffer, offset, length, src.bitPosition)
            while (true) {
                val frame = walker.next() ?: break
                if (frame.type != MessageType.Rpc) continue
                val answer = rpc.receive(from, walker.readerFor(frame), host.tick)
                if (answer != null) {
                    refusal = answer
                    lastRefusal = answer
                }
            }
        } catch (malformed: RuntimeException) {
            malformedPackets++
            return refusal
        } finally {
            AbilityRpc.bind(previous)
        }
        return refusal
    }

    /**
     * Runs an accepted activation against this host's world. Reached only through [rpc].
     *
     * The identical call `PlayerControlSystem` makes for a local key press and `UnitBrain` makes
     * for an AI unit: same `AbilityActivation`, same cost check, same cooldown, same tick. A
     * remote player gets no separate path into the ability system, so there is no second place a
     * permission check could have been forgotten.
     */
    private fun activate(self: NetId, slot: Int): ActivationResult {
        val gas = host.ctx[GasServices.KEY]
        val entity = host.ctx[CoreModule.NET_IDS].resolveOrNull(self)
            ?: return ActivationResult.NoAuthority
        return with(host.world) {
            val abilities = entity.getOrNull(Abilities) ?: return@with ActivationResult.NoAuthority
            val attributes = entity.getOrNull(Attributes) ?: return@with ActivationResult.NoAuthority
            val effects = entity.getOrNull(GameplayEffects) ?: return@with ActivationResult.NoAuthority
            gas.activation.activate(self, abilities, attributes, effects, slot, host.tick)
        }
    }

    /**
     * One authoritative tick: consume input, simulate, capture, replicate.
     *
     * The order is the whole contract. Input is drained **before** the step so that the command
     * a client sent for this tick is the one `SimPhase.Intent` samples; the capture is **after**
     * it, so what goes on the wire is the world as it ended the tick; and the broadcast reads the
     * slot the capture just committed, so every client's delta is written against a baseline that
     * provably exists in the ring.
     */
    public fun tick() {
        drainInput()
        host.run(1)
        travel.captureNow()
        solveVision()
        replication.broadcast(state())
    }

    /**
     * Offers every body in the world to [fog], once, between the capture and the broadcast.
     *
     * **After the capture** so the positions solved against are the ones about to be sent - a
     * solve on last tick's positions grants vision for a state the client never receives - and
     * **before the broadcast** because the packer asks `isRelevant` while it packs.
     *
     * Every unit is a body that can be seen; only a `Player` champion is a vision *source*. That
     * is this game's rule and not the engine's: `observe` takes a sight radius per entity, so a
     * game where towers or wards see would pass one for those too.
     */
    private fun solveVision() {
        val seen = fog ?: return
        val netIds = host.ctx[CoreModule.NET_IDS]
        seen.beginSolve(host.tick)
        with(host.world) {
            val bodies = host.world.family { all(Position, Combatant) }.entities
            for (index in 0 until bodies.size) {
                val entity = bodies[index]
                val at = entity[Position]
                val sight = if (entity.getOrNull(Player) != null) championSight else 0f
                seen.observe(netIds.netIdOf(entity), at.x, at.y, entity[Combatant].teamId, sight)
            }
        }
        seen.endSolve()
    }

    /** The captured world at the server's current tick. */
    public fun state(): WorldSnapshot = stateAt(host.tick)

    /**
     * The captured world at [at] exactly.
     *
     * Exactly, and not "nearest at or before": an agreement check against the newest capture
     * would be asserting that replication is instantaneous rather than that it is correct. A
     * client can only ever hold a tick the server has already left.
     */
    public fun stateAt(at: Tick): WorldSnapshot {
        val slot = ring.nearestAtOrBefore(at)
            ?: error("the server's ring holds nothing at or before $at")
        check(slot.tick == at) { "the ring no longer holds $at; the nearest slot is ${slot.tick}" }
        return slot
    }

    /**
     * Consumes exactly one command per client per tick, into that client's own [Intent].
     *
     * One buffer, one seat, one champion, and no crossing: this is where "client 2 is a
     * spectator" was. It used to drain every buffer and keep only the controlling peer's command,
     * so a second player's axis was read off the wire and thrown away.
     *
     * A peer whose packet has not arrived consumes `null` and its [Intent] is cleared, which
     * leaves that champion standing still rather than repeating last tick's axis - the jitter
     * buffer's own starvation repeat is what makes that rare, and pretending it did not happen is
     * what would make a lagging player slide.
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
     * Walks the world rather than a list of this session's own spawns, so a champion left behind
     * by a peer that disconnected is found by the same code that finds the level's authored one.
     * A champion reserved for a peer that joined this tick is already in [byChampion] and its
     * entity does not exist yet, so neither can be handed out twice.
     */
    private fun unownedChampion(): NetId? {
        val netIds = host.ctx[CoreModule.NET_IDS]
        val entities = host.world.family { all(Player) }.entities
        for (index in 0 until entities.size) {
            val id = netIds.netIdOf(entities[index])
            if (!byChampion.containsKey(id.raw)) return id
        }
        return null
    }

    /**
     * Spawns one more soldier for a joining peer, offset from the level's champion.
     *
     * Offset because two champions on the same coordinate are one sprite as far as a player is
     * concerned, and "the second player cannot see themselves" is indistinguishable from "the
     * second player is not being simulated". A soldier walks [dev.wildware.moba.level.GameUnit]
     * speed per tick, so [CHAMPION_SPACING] is a little under a second of walking apart.
     */
    private fun spawnChampion(peer: PeerId): NetId {
        spawned++
        return Player.spawn(
            spawner = host.ctx.blueprints,
            blueprints = host.ctx[MobaBlueprints.KEY],
            x = Player.SPAWN_X + spawned * CHAMPION_SPACING,
            y = Player.SPAWN_Y,
            owner = peer.raw,
        )
    }

    /**
     * Writes [owner] onto [champion]'s replicated `Player`, when the entity exists yet.
     *
     * A champion spawned this tick does not: the spawn is queued on the barrier and the component
     * is created with the owner already in it by [Player.spawn]'s override, so there is nothing
     * to write. A *reclaimed* champion does exist, and this is the write that moves it from one
     * window's camera to another's.
     */
    private fun stamp(champion: NetId, owner: Int) {
        val entity = host.ctx[CoreModule.NET_IDS].resolveOrNull(champion) ?: return
        with(host.world) { entity.getOrNull(Player)?.owner = owner }
    }

    override fun close() {
        // Only if it is still ours. Two sessions in one JVM would otherwise have the second one's
        // close unbind the first one's live sink, and every activation on a server that is still
        // running would answer `NoAuthority` - a game that silently stops accepting attacks.
        if (AbilityRpc.sink === activationSink) AbilityRpc.unbind()
        host.stop()
        transport.close()
    }

    override fun toString(): String =
        "MobaHostSession(tick=${host.tick}, clients=${seats.size}, player=$playerId)"

    /**
     * One connection's champion and one connection's hands, held together.
     *
     * A class rather than three parallel maps, because the invariant that matters is that they
     * move together: a seat is created, reclaimed and dropped as one thing, and a peer holding a
     * champion whose intent belongs to somebody else is precisely the defect this replaces.
     */
    private class Seat(
        val peer: PeerId,
        val champion: NetId,
        /** This peer's command, presented to the simulation as an ordinary [IntentSource]. */
        val source: NetIntentSource,
        /** Filled from [source] once per tick and handed to `PlayerControlSystem` for [champion]. */
        val intent: Intent,
    )

    public companion object {

        /** The rate the RPC limiter denominates its token buckets in. The simulation's own. */
        private const val TICKS_PER_SECOND = 60

        /** World units between one spawned champion and the next. */
        private const val CHAMPION_SPACING: Float = 1.5f

        /**
         * How far a champion sees by default, in world units.
         *
         * Chosen against this level rather than picked: `test_level.udea.kts` puts the orc
         * clearing at `(-50, 0)`, the priest post at `(0, 0)`, the soldier camp at `(0, -50)` and
         * the skeleton camp at `(100, 0)`. Sixty units means a champion in one cluster sees its
         * neighbours and never the far camp - so fog is visibly doing something, which a radius
         * that covered the arena would not be.
         */
        public const val DEFAULT_CHAMPION_SIGHT: Float = 60f

        /**
         * A [FogOfWar] sized for this level: one kilometre square at 32-unit cells, three teams.
         *
         * The grid is deliberately far larger than the authored clusters. A champion driven by a
         * player walks out of the arena within a minute, and [VisionGrid] clamps rather than
         * grows, so a snug grid would quietly stop indexing the two entities that matter most.
         */
        public fun fogOfWar(): FogOfWar = FogOfWar(
            grid = VisionGrid(originX = -512f, originY = -512f, cellSize = 32f, columns = 32, rows = 32),
            teams = TEAMS,
            capacity = FOG_CAPACITY,
        )

        /** `Teams` is an open set of ints; this level uses orc, soldier and undead. */
        private const val TEAMS: Int = 3

        /** `NetId` indices the fog tracks. Comfortably over this level's roster. */
        private const val FOG_CAPACITY: Int = 512
    }
}

/**
 * A [MoveInput] presented to the simulation as an ordinary [IntentSource].
 *
 * Nothing downstream knows the axis came off a socket. `IntentSampleSystem` calls [sample] at the
 * top of `SimPhase.Intent` exactly as it does for `DeviceIntent` and `InjectedIntent`, and
 * `PlayerControlSystem` reads the result - so the remote player's swing goes through the same
 * `AbilityActivation` call, with the same cost check and the same cooldown, as the AI soldier
 * standing next to them.
 *
 * A null [pending] samples nothing, which leaves the intent idle: that is what a client whose
 * packets have not arrived yet should look like, and it is what the jitter buffer's own
 * starvation repeat exists to make rare.
 */
public class NetIntentSource : IntentSource {

    /** The command this tick. Set by the session immediately before the step. */
    public var pending: MoveInput? = null

    override fun sample(into: Intent) {
        val command = pending ?: return
        into.setAxis(MobaControls.MOVE_AXIS, command.moveX, command.moveY)
        if (command.buttons and PRIMARY != 0) into.setPressCount(MobaControls.ATTACK_ACTION, 1)
        if (command.buttons and SECONDARY != 0) into.setPressCount(MobaControls.ATTACK_2_ACTION, 1)
    }

    public companion object {

        /** Button bit 0: the basic attack, `PlayerControlSystem.SLOT_PRIMARY`. */
        public const val PRIMARY: Int = 1

        /** Button bit 1: the special, `PlayerControlSystem.SLOT_SECONDARY`. */
        public const val SECONDARY: Int = 2
    }
}
