package dev.wildware.moba.entry

import dev.wildware.moba.MobaControls
import dev.wildware.moba.MobaGame
import dev.wildware.moba.audio.MobaAudio
import dev.wildware.moba.match.MatchService
import dev.wildware.moba.match.NewMatchSignal
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.moba.Player
import dev.wildware.moba.Position
import dev.wildware.moba.net.LateTransport
import dev.wildware.moba.net.MobaClientSession
import dev.wildware.moba.net.MobaHostSession
import dev.wildware.udea.net.relevancy.FogOfWar
import dev.wildware.moba.net.MobaNet
import dev.wildware.moba.net.NetIntentSource
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.net.replication.BandwidthBudget
import dev.wildware.udea.net.transport.ConnectionSecret
import dev.wildware.udea.net.transport.DatagramSink
import dev.wildware.udea.net.transport.DisconnectReason
import dev.wildware.udea.net.transport.LoopbackNetwork
import dev.wildware.udea.net.transport.ManualClock
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.transport.UdpConfig
import dev.wildware.udea.net.transport.UdpConnectionListener
import dev.wildware.udea.net.transport.UdpTransport
import dev.wildware.udea.render.input.InjectedIntent
import dev.wildware.udea.render.input.Intent
import dev.wildware.udea.render.input.IntentState
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * `moba.client`: what a player runs. A real LWJGL3 context in a visible window.
 *
 * The frame cadence belongs to the render thread here, not to the process: `GameHost.run()` is
 * refused in any mode with a context, and the backend calls `GameHost.frame(wallDelta)` instead.
 *
 * `-Dudea.render.mode=Offscreen` demotes this to a hidden window, which is occasionally what you
 * want when checking that a capture path works without a window stealing focus.
 *
 * ## Two modes, and the honest difference between them
 *
 * **Local (the default).** Boot it and you are one of the soldiers in `level/test_level`. WASD
 * walks, Space swings, and the camera follows you through the fight. One process, one world, the
 * simulation underneath identical to [MobaServer]'s.
 *
 * **Networked (`-Dudea.net.client=true`).** The process stands up a
 * [MobaHostSession] - the authoritative server, with its own world and its own snapshot ring -
 * and this window becomes `client(1)` on a
 * [dev.wildware.udea.net.transport.LoopbackTransport] beside it. That is Trello #8's listen
 * server, and it is a real one rather than a shortcut: the window's world is **empty** at boot
 * and every entity in it arrives as a datagram and is written onto Fleks components by
 * `Replicator.apply`. The keyboard's axis goes out as an `@InputCommand` and comes back as the
 * server's answer about where the soldier now is. Nothing this window renders was simulated by
 * this window.
 *
 * A socket changes one expression in this file - which `Transport` the two sessions are handed -
 * and nothing else, which is the point of the seam.
 *
 * ### What networked mode does not do yet, plainly
 *
 * - **Poses do not animate.** `MobaScene` reads the playhead as `clock.tick - startTick`, and a
 *   replicated client never steps its simulation (see [MobaClientSession] for why: `moba` has no
 *   authority gating, so a stepping client would run `MatchSystem` and restart the level out from
 *   under the state it is being sent). `SimClock.moveTo` is `internal` to `udea-core`, so the
 *   client cannot follow the server's tick without either stepping or that API widening.
 * - **No prediction.** The player's own input is not applied locally, so their movement is a
 *   round trip late. Over loopback that is two ticks and invisible; over a real link it would not
 *   be. Every piece prediction needs is present in `udea-net` and none of it is joined up.
 * - **The camera does not follow.** Following resolves a `NetId` through the *definition's*
 *   `NetIdIndex`, and in networked mode the entity lives in the client host's index under the
 *   server's id, which is not known until the first packet lands.
 *
 * ## Four modes, and the two new ones are the shipped pair
 *
 * The mode is `args[0]`, so `--args=` reaches it through Gradle without this module's build file
 * having to forward a system property:
 *
 * | command | what it is |
 * |---|---|
 * | `runClient` | single process, one world, no transport |
 * | `runClient --args="listen"` | authoritative session and this window as its client, over an in-process loopback link |
 * | `runClient --args="host 27015"` | **binds a real UDP socket** and plays in this window as its first client |
 * | `runClient --args="join 10.0.0.4:27015"` | **connects a real UDP socket** to somebody else's `host` |
 *
 * `host` and `join` are the launchable pair. Everything socket-shaped in this tree before them
 * was a proof harness: `MobaUdpServer` and `MobaUdpClient` speak a line protocol on stdin to a
 * parent test and drive a scripted walk, and `MobaServer` is a listen server in one JVM. Neither
 * opens a window, and neither takes a host address. These two do, and there is nothing test-only
 * on the path: `host` is a [MobaHostSession] over [UdpTransport.server], `join` is a
 * [MobaClientSession] over [UdpTransport.client], and the two are the same classes the loopback
 * proof runs.
 *
 * A `host` window is a client of its own server over the loopback interface rather than a special
 * in-process case, and that is deliberate: it means the person hosting plays down the identical
 * code path as the person who joined, so a defect in the client half cannot hide on the host's
 * screen.
 *
 * ### What a networked window can and cannot do, plainly
 *
 * It **can** now tell which champion is its own - [Player.owner] is replicated, the server writes
 * the peer id into it, and this file points the camera at the matching entity. The status line
 * prints both champions' positions from this window's own replicated world, which is how "each
 * sees the other move" is checked rather than asserted.
 *
 * The three costs listed above are unchanged: poses do not animate, there is no prediction, and
 * a `host` window's own input still makes a round trip through its own socket.
 *
 * `./gradlew :moba:runClient`, or `./gradlew :moba:runClient --args="host 27015"`.
 */
public object MobaClient {

    /** Set true to run this window as a replicated client of an in-process authoritative server. */
    public const val NETWORKED_PROPERTY: String = "udea.net.client"

    /** The default port. `MobaNet`'s, so a client and a server that name none still meet. */
    public const val DEFAULT_PORT: Int = MobaNet.DEFAULT_PORT

    /**
     * Frames to run before closing the window. `0`, the default, runs until a human closes it.
     *
     * A soak knob, and the only way a two-window run can be *checked* rather than watched: two
     * processes that exit on their own produce two transcripts a script can compare, and a
     * bounded run is what makes "the other champion moved" a reading instead of a screenshot.
     */
    public const val FRAMES_PROPERTY: String = "udea.net.frames"

    /**
     * A scripted walk on this window's move axis: `1` right, `-1` left, absent for hands.
     *
     * It goes in through [InjectedIntent] and [MobaEntry.wireInput]'s `extra` - the **same** seam
     * an agent's `input.*` tools use, composed with the keyboard rather than replacing it - so a
     * window driven by this is not driving a different code path from a window driven by a
     * person. That is the whole reason it is a knob here rather than a test-only main: a demo
     * that took a private route into the simulation would be demonstrating the route.
     */
    public const val WALK_PROPERTY: String = "udea.net.walk"

    /**
     * Server-side fog of war on a `host` window: a champion's sight radius in world units.
     *
     * Absent, the default, replicates everything to everybody, which is what both of this game's
     * headline proofs assert. Present, [MobaHostSession] is given a [FogOfWar] and a unit outside
     * every champion's radius is **not serialised at all** for that client - so the other window
     * can neither draw it nor read it out of its own store, which is the difference between fog
     * and a rendering trick.
     *
     * A knob rather than a default because turning it on changes what a client is *allowed to
     * hold*, and the roster-agreement proof and the two-window proof both assert full visibility.
     * `MobaFogTest` is what asserts the on case, against the same real session this builds.
     *
     * ```
     * ./gradlew :moba:runClient --args="host 7777" -Dudea.moba.fog=60
     * ```
     */
    public const val FOG_PROPERTY: String = "udea.moba.fog"

    /**
     * Boots a window and blocks until it closes.
     *
     * `args[0]` is the mode. An unrecognised one is refused by name rather than silently falling
     * back to single-player: a player who typed `joim 10.0.0.4` and got a local game with no
     * error would have no way at all to tell that from a server that is down.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        val mode = MobaEntry.modeFromProperties(fallback = RenderMode.Windowed)
        val legacy = System.getProperty(NETWORKED_PROPERTY)?.toBoolean() ?: false
        val requested = args.firstOrNull()?.trim()?.lowercase()
            ?: (if (legacy) "listen" else "local")
        println("[moba.client] ${MobaGame.NAME} ${MobaGame.VERSION} $mode $requested")
        when (requested) {
            "local" -> local(mode)
            "listen" -> networked(mode)
            "host" -> udp(mode, bindPort = portOf(args.getOrNull(1)), joinTo = null)
            "join" -> udp(mode, bindPort = null, joinTo = addressOf(args.getOrNull(1)))
            else -> throw IllegalArgumentException(
                "unknown mode '$requested'; expected local, listen, host [port] or join <host[:port]>",
            )
        }
    }

    /**
     * A window on a real socket: `host` binds one and plays, `join` connects to one.
     *
     * The two share every line below the first `if`, which is the point. A host is a server plus
     * a client, not a client with server powers, so the only difference between the two windows
     * is that one of them also has a [MobaHostSession] to poll and tick.
     */
    private fun udp(mode: RenderMode, bindPort: Int?, joinTo: InetSocketAddress?) {
        val serving = if (bindPort == null) null else bind(bindPort)
        val serverAddress = joinTo ?: InetSocketAddress(
            InetAddress.getLoopbackAddress(),
            checkNotNull(serving).address.port,
        )
        if (serving != null) {
            println("[moba.client] serving on ${serving.address}; tell the other player to run:")
            println("[moba.client]   ./gradlew :moba:runClient --args=\"join <this machine>:${serving.address.port}\"")
        }
        println("[moba.client] connecting to $serverAddress")

        val clock = ManualClock()
        val late = LateTransport()
        MobaEntry.runWithGl(mode) { host, rendering ->
            val client = MobaClientSession(PeerId.client(1), late, host, mtu = SESSION_MTU)
            // A window draws the *predicted* champion and the *interpolated* everybody else.
            // Off by default because a proof hashes this world and a presentation value in it
            // would disagree with the server by construction; on here, because the alternative
            // is a champion that answers the keyboard a round trip late.
            client.presentView = true
            var failure: DisconnectReason? = null
            val socket = UdpTransport.client(
                serverAddress = serverAddress,
                // The opening nonce. `udea-net` holds no random source by design, so the value
                // is supplied here - by a CSPRNG, because two clients that opened with the same
                // salt would be one connection as far as the server's address table is concerned.
                clientSalt = SecureRandom().nextLong(),
                clock = clock,
                protoHash = client.protocol.protoHash,
                config = udpConfig(),
                listener = object : UdpConnectionListener {
                    override fun onDisconnected(peer: PeerId, reason: DisconnectReason) {
                        failure = reason
                    }
                },
            )
            late.target = socket

            val intent = host.ctx[IntentState.KEY]
            // The scripted walk, if one was asked for, composed with the keyboard rather than
            // instead of it: a human watching a soak can still take over.
            val walk = System.getProperty(WALK_PROPERTY)?.trim()?.toFloatOrNull()
            val scripted = walk?.let { axis ->
                InjectedIntent(intent.bindings.catalog).also { it.setAxis(MobaControls.MOVE_AXIS, axis, 0f) }
            }
            MobaEntry.wireInput(host, rendering, extra = scripted)
            val limit = System.getProperty(FRAMES_PROPERTY)?.trim()?.toLongOrNull() ?: 0L
            var audioBuilt: MobaAudio? = null
            rendering.onRenderThread { audioBuilt = MobaAudio.forHost(host) }
            val audio = checkNotNull(audioBuilt) { "MobaAudio was not built on the render thread" }
            val sink = DatagramSink { _, buffer, offset, length ->
                client.onPacket(buffer, offset, length)
            }
            var frames = 0L
            var followed: NetId? = null
            var announced = false
            MobaEntry.Attachment(
                frame = {
                    val tick = clock.advance()
                    // The server half first, so a host's own datagram is released before its
                    // window polls: the same release-then-receive order `NetHarness` imposes.
                    serving?.pump()
                    intent.sample(tick)
                    socket.flush()
                    socket.poll(sink)
                    // Input, and only input. The axis and the two button bits this window's
                    // keyboard produced, minted once so the command's sequence number is spent
                    // exactly once - and never a component this window decided the value of.
                    client.tick(
                        tick,
                        client.command(
                            tick,
                            moveX = moveX(intent.intent),
                            moveY = moveY(intent.intent),
                            buttons = buttons(intent.intent),
                        ),
                    )
                    host.frame(0f)
                    audio.frame()
                    if (socket.isConnected && !announced) {
                        println("[moba.client] connected as ${socket.localPeer} at tick ${tick.value}")
                        announced = true
                    }
                    // Follow this window's own champion the first time it arrives. `Player.owner`
                    // is the server's answer to "which one is mine", and it cannot be known until
                    // the entity carrying it has been replicated - which is why this is a per-frame
                    // check and not a line at start-up.
                    if (followed == null) {
                        followed = championOf(host, socket.localPeer)?.also {
                            MobaEntry.follow(rendering, it)
                            audio.listenTo(it)
                            println("[moba.client] you are net id ${it.raw}; WASD to walk, Space to swing")
                        }
                    }
                    frames++
                    if (frames % REPORT_FRAMES == 0L) println(report(host, client, socket.localPeer))
                    if (limit > 0L && frames >= limit) {
                        println(report(host, client, socket.localPeer))
                        println("[moba.client] done after $frames frame(s)")
                        rendering.requestExit()
                    }
                    failure?.let {
                        println("[moba.client] disconnected: $it")
                        failure = null
                        rendering.requestExit()
                    }
                },
                close = {
                    audio.close()
                    client.close()
                    serving?.close()
                },
            )
        }
    }

    /**
     * The authoritative half of a `host` window: a bound socket and the session behind it.
     *
     * [LateTransport] unties the one knot: `UdpTransport` must be told this build's `protoHash`,
     * which is derived from the session's component registry, and the session is constructed
     * around a transport. Nothing is sent between the two moments.
     */
    private fun bind(port: Int): Serving {
        val clock = ManualClock()
        val late = LateTransport()
        val sight = System.getProperty(FOG_PROPERTY)?.trim()?.toFloatOrNull()
        val session = MobaHostSession(
            late,
            BandwidthBudget(SESSION_MTU),
            SESSION_MTU,
            fog = if (sight == null) null else MobaHostSession.fogOfWar(),
            championSight = sight ?: MobaHostSession.DEFAULT_CHAMPION_SIGHT,
        )
        if (sight != null) println("[moba.client] fog of war on: champions see ${sight} units")
        // A fresh key per process, from a CSPRNG. `ConnectionSecret` takes its key from the
        // caller precisely so this is a deployment's decision; a fixed one in a shipped binary
        // would let anybody mint a connect token for anybody's server.
        val key = ByteArray(ConnectionSecret.MIN_KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val arrivals = ConcurrentLinkedQueue<Pair<PeerId, Boolean>>()
        val socket = UdpTransport.server(
            bindAddress = InetSocketAddress(port),
            clock = clock,
            secret = ConnectionSecret(key),
            protoHash = session.protocol.protoHash,
            config = udpConfig(),
            listener = object : UdpConnectionListener {
                override fun onConnected(peer: PeerId) {
                    arrivals += peer to true
                }

                override fun onDisconnected(peer: PeerId, reason: DisconnectReason) {
                    arrivals += peer to false
                }
            },
        )
        late.target = socket
        return Serving(session, socket, clock, arrivals)
    }

    /** One `host` window's server: everything it needs pumped once per frame, and its teardown. */
    private class Serving(
        val session: MobaHostSession,
        val socket: UdpTransport,
        val clock: ManualClock,
        private val arrivals: ConcurrentLinkedQueue<Pair<PeerId, Boolean>>,
    ) {

        /** Where a joining client is told to send its datagrams. */
        val address: InetSocketAddress get() = socket.localAddress

        /**
         * One authoritative tick: release, receive, seat or unseat whoever changed, simulate.
         *
         * The join is applied **before** the tick rather than inside the listener, because
         * `addClient` spawns a champion and a spawn is a barrier action: applying it here means
         * the entity exists at the end of the very tick the connection was accepted on.
         */
        fun pump() {
            clock.advance()
            socket.flush()
            socket.poll { from, buffer, offset, length ->
                session.onPacket(from, buffer, offset, length)
            }
            while (true) {
                val (peer, joined) = arrivals.poll() ?: break
                if (joined) {
                    println("[moba.server] $peer joined, driving ${session.addClient(peer).raw}")
                } else {
                    session.removeClient(peer)
                    println("[moba.server] $peer left")
                }
            }
            session.tick()
        }

        fun close() {
            session.close()
        }
    }

    /**
     * Every champion this window can see, as one line: whose it is and where it is.
     *
     * Read out of **this window's own** Fleks world, which holds nothing it simulated, so a line
     * showing two champions at two different positions is a statement about replication. It is
     * what makes "each client sees the other move" a reading rather than a claim.
     */
    private fun report(host: GameHost, client: MobaClientSession, me: PeerId): String {
        val netIds = host.ctx[CoreModule.NET_IDS]
        val entities = host.world.family { all(Player) }.entities
        val champions = buildString {
            with(host.world) {
                for (index in 0 until entities.size) {
                    val entity = entities[index]
                    val player = entity[Player]
                    val position = entity.getOrNull(Position)
                    val mine = if (player.owner == me.raw) "*" else " "
                    append(" $mine peer${player.owner}@${netIds.netIdOf(entity).raw}")
                    append("=(%.2f, %.2f)".format(position?.x ?: 0f, position?.y ?: 0f))
                }
            }
        }
        return "[moba.client] server=${client.serverTick.value} units=${client.unitCount()} " +
            "applied=${client.applied} champions$champions"
    }

    /** This window's own champion, or null while the entity carrying its peer id has not arrived. */
    private fun championOf(host: GameHost, me: PeerId): NetId? {
        val netIds = host.ctx[CoreModule.NET_IDS]
        val entities = host.world.family { all(Player) }.entities
        with(host.world) {
            for (index in 0 until entities.size) {
                val entity = entities[index]
                if (entity[Player].owner == me.raw) return netIds.netIdOf(entity)
            }
        }
        return null
    }

    private fun moveX(intent: Intent): Float = intent.axisX(MobaControls.MOVE_AXIS)

    private fun moveY(intent: Intent): Float = intent.axisY(MobaControls.MOVE_AXIS)

    private fun buttons(intent: Intent): Int {
        var bits = 0
        if (intent.isJustPressed(MobaControls.ATTACK_ACTION)) bits = bits or NetIntentSource.PRIMARY
        if (intent.isJustPressed(MobaControls.ATTACK_2_ACTION)) bits = bits or NetIntentSource.SECONDARY
        return bits
    }

    private fun portOf(raw: String?): Int =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let {
            it.toIntOrNull() ?: throw IllegalArgumentException("'$it' is not a port number")
        } ?: DEFAULT_PORT

    /** `host`, or `host:port`. IPv6 in brackets is not handled, and says so rather than guessing. */
    private fun addressOf(raw: String?): InetSocketAddress {
        val text = raw?.trim().orEmpty()
        require(text.isNotEmpty()) { "join needs an address: --args=\"join <host[:port]>\"" }
        require(!text.startsWith("[")) { "bracketed IPv6 literals are not parsed here; use a name" }
        val colon = text.lastIndexOf(':')
        if (colon < 0) return InetSocketAddress(text, DEFAULT_PORT)
        val port = text.substring(colon + 1).toIntOrNull()
            ?: throw IllegalArgumentException("'${text.substring(colon + 1)}' is not a port number")
        return InetSocketAddress(text.substring(0, colon), port)
    }

    /**
     * What both sockets are configured with.
     *
     * `mtu` is the **link** ceiling and 1200 is what a real path carries; [SESSION_MTU] is the
     * message ceiling, and the transport fragments and reassembles the difference. A whole
     * 27-unit tick does not fit in 1200 bytes, and a packer that deferred half of it would make a
     * client's world a mix of server ticks by design.
     */
    private fun udpConfig(): UdpConfig = UdpConfig(mtu = LINK_MTU, maxClients = MAX_CLIENTS)

    /**
     * The listen server: an authoritative session in this process, and this window as its client.
     *
     * The window's host is built by [MobaEntry.runWithGl] as it always is, and handed straight to
     * [MobaClientSession] - so the render pipeline, the scene registry and the overlay-free frame
     * loop are all the ones a local client uses. What changes is the frame body: poll, apply,
     * send, then draw.
     */
    private fun networked(mode: RenderMode) {
        // One in-memory network and one manual clock for the whole session. The clock is advanced
        // by the frame body below rather than by a harness, because in this arrangement the render
        // thread owns the cadence - which is exactly the case a socket will also be in.
        val clock = ManualClock()
        val network = LoopbackNetwork(clock)
        val server = MobaHostSession(network.transportFor(PeerId.SERVER))
        server.addClient(PeerId.client(1))
        println("[moba.client] server up: ${server.host.world.numEntities} entities, proto ${server.protocol.protoHash}")

        MobaEntry.runWithGl(mode) { host, rendering ->
            val client = MobaClientSession(PeerId.client(1), network.transportFor(PeerId.client(1)), host)
            client.presentView = true
            check(client.protocol.protoHash == server.protocol.protoHash) {
                "this window and the server in the same process built different protocols"
            }
            // The keyboard, through the identical `IntentSource` seam a local client uses. Its
            // axis is read here and put in a command; it is never written into a component that
            // then goes to the server. A client sends what the player *did*.
            MobaEntry.wireInput(host, rendering, extra = null)
            val intent = host.ctx[IntentState.KEY]
            var audioBuilt: MobaAudio? = null
            rendering.onRenderThread { audioBuilt = MobaAudio.forHost(host) }
            val audio = checkNotNull(audioBuilt) { "MobaAudio was not built on the render thread" }
            var frames = 0L
            MobaEntry.Attachment(
                frame = {
                    val tick = clock.advance()
                    // Sample the keyboard by hand: `IntentSampleSystem` runs inside a tick, and a
                    // replicated client does not step one.
                    intent.sample(tick)
                    val moveX = intent.intent.axisX(MobaControls.MOVE_AXIS)
                    val moveY = intent.intent.axisY(MobaControls.MOVE_AXIS)
                    var buttons = 0
                    if (intent.intent.isJustPressed(MobaControls.ATTACK_ACTION)) {
                        buttons = buttons or NetIntentSource.PRIMARY
                    }
                    if (intent.intent.isJustPressed(MobaControls.ATTACK_2_ACTION)) {
                        buttons = buttons or NetIntentSource.SECONDARY
                    }
                    // The server's tick, then the client's: release before receive, send last.
                    network.transportFor(PeerId.SERVER).poll { from, buffer, offset, length ->
                        server.onPacket(from, buffer, offset, length)
                    }
                    server.tick()
                    network.transportFor(PeerId.client(1)).poll { _, buffer, offset, length ->
                        client.onPacket(buffer, offset, length)
                    }
                    client.tick(tick, client.command(tick, moveX, moveY, buttons = buttons))
                    // `frame(0f)` renders without stepping: the loop only advances the simulation
                    // when the delta buys it a whole tick, and zero never does. That is the one
                    // line that makes this window a *view* rather than a second simulation.
                    host.frame(0f)
                    audio.frame()
                    frames++
                    if (frames % REPORT_FRAMES == 0L) {
                        println(
                            "[moba.client] tick ${tick.value} server=${client.serverTick.value} " +
                                "units=${client.unitCount()} applied=${client.applied}",
                        )
                    }
                },
                close = {
                    audio.close()
                    client.close()
                    server.close()
                },
            )
        }
    }

    /** The single-process game: one world, one simulation, no transport. Unchanged. */
    private fun local(mode: RenderMode) {
        MobaEntry.runWithGl(mode) { host, rendering ->
            val player = MobaEntry.seed(host)
            MobaEntry.wireInput(host, rendering, extra = null)
            MobaEntry.follow(rendering, player)
            println("[moba.client] you are net id ${player.raw}; WASD to walk, Space to swing")
            var built: MobaAudio? = null
            rendering.onRenderThread { built = MobaAudio.forHost(host) }
            val audio = checkNotNull(built) { "MobaAudio was not built on the render thread" }
            audio.listenTo(player)
            // A restart is a scene swap, and a swap resets the id allocator without resetting the
            // generation counters - so the id captured above reads *stale* from match two onward.
            // The symptom is not an error: the camera stops following and the view sits where the
            // last match left it, which reads exactly like the game freezing at the moment it
            // restarted. `NewMatchSignal` fires once per match, including the first, and one
            // signal per consumer because `poll` consumes the edge.
            val newMatch = NewMatchSignal(host.ctx[MatchService.KEY])
            MobaEntry.Attachment(
                frame = { delta ->
                    host.frame(delta)
                    if (newMatch.poll()) {
                        MobaEntry.playerIdOrNull(host)?.let { current ->
                            MobaEntry.follow(rendering, current)
                            audio.listenTo(current)
                        }
                    }
                    audio.frame()
                },
                close = audio::close,
            )
        }
    }

    /** Frames between status lines in networked mode. */
    private const val REPORT_FRAMES: Long = 120L

    /** Largest message a session builds. Fragmented over the link by the transport. */
    private const val SESSION_MTU: Int = 16384

    /** What a real path carries. */
    private const val LINK_MTU: Int = 1200

    /** How many people may be in one `host` window's game, the host included. */
    private const val MAX_CLIENTS: Int = 8
}
