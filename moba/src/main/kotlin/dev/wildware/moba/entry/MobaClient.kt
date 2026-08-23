package dev.wildware.moba.entry

import dev.wildware.moba.MobaControls
import dev.wildware.moba.MobaGame
import dev.wildware.moba.audio.MobaAudio
import dev.wildware.moba.match.MatchService
import dev.wildware.moba.match.NewMatchSignal
import dev.wildware.moba.net.MobaClientSession
import dev.wildware.moba.net.MobaHostSession
import dev.wildware.moba.net.NetIntentSource
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.net.transport.LoopbackNetwork
import dev.wildware.udea.net.transport.ManualClock
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.render.input.IntentState

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
 * `./gradlew :moba:runClient`, or `./gradlew :moba:runClient -Dudea.net.client=true`.
 */
public object MobaClient {

    /** Set true to run this window as a replicated client of an in-process authoritative server. */
    public const val NETWORKED_PROPERTY: String = "udea.net.client"

    /** Boots a window and blocks until it closes. */
    @JvmStatic
    public fun main(args: Array<String>) {
        val mode = MobaEntry.modeFromProperties(fallback = RenderMode.Windowed)
        val networked = System.getProperty(NETWORKED_PROPERTY)?.toBoolean() ?: false
        println("[moba.client] ${MobaGame.NAME} ${MobaGame.VERSION} $mode ${if (networked) "networked" else "local"}")
        if (networked) networked(mode) else local(mode)
    }

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
}
