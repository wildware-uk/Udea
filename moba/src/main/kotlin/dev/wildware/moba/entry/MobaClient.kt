package dev.wildware.moba.entry

import dev.wildware.moba.MobaGame
import dev.wildware.moba.audio.MobaAudio
import dev.wildware.moba.match.MatchService
import dev.wildware.moba.match.NewMatchSignal
import dev.wildware.udea.core.host.RenderMode

/**
 * `moba.client`: what a player runs. A real LWJGL3 context in a visible window.
 *
 * The frame cadence belongs to the render thread here, not to the process: `GameHost.run()` is
 * refused in any mode with a context, and the backend calls `GameHost.frame(wallDelta)` instead.
 * That is the whole difference between this file and [MobaServer] - the simulation underneath is
 * the identical [MobaGame.definition].
 *
 * `-Dudea.render.mode=Offscreen` demotes this to a hidden window, which is occasionally what you
 * want when checking that a capture path works without a window stealing focus.
 *
 * ## What a human can now do with it
 *
 * Boot it and you are one of the soldiers in `level/test_level`. **WASD walks**, Space swings,
 * and the camera follows you through the fight. All three are new: this entry point used to open
 * a window on a simulation nothing could touch, because the only input path in the tree was
 * `Gdx.input` polled from inside a Fleks system in the *old* engine, and nothing had replaced it.
 *
 * `./gradlew :moba:runClient`
 */
public object MobaClient {

    /** Boots a window and blocks until it closes. */
    @JvmStatic
    public fun main(args: Array<String>) {
        val mode = MobaEntry.modeFromProperties(fallback = RenderMode.Windowed)
        println("[moba.client] ${MobaGame.NAME} ${MobaGame.VERSION} $mode")
        MobaEntry.runWithGl(mode) { host, rendering ->
            val player = MobaEntry.seed(host)
            // Keyboard in, camera on the unit it drives. `null` for the second source: a client
            // has no agent, and combining with nothing would be a `CompositeIntent` doing a
            // virtual call and a clamp for one input.
            MobaEntry.wireInput(host, rendering, extra = null)
            MobaEntry.follow(rendering, player)
            println("[moba.client] you are net id ${player.raw}; WASD to walk, Space to swing")
            // Audio. Built on the render thread because `Gdx.audio` has the same thread affinity
            // every `Gdx` static has, and this is the call that opens the OpenAL buffers.
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
            // `host.frame` *and* `audio.frame`, which is the one line that separates a client from
            // a silent one. It is not only "you can hear it now": nothing in the shipped build
            // emptied `GameContext.cues`, so `CueQueue` filled its 1024 slots in about two seconds
            // of fighting and discarded every cue after that. A client that renders and never
            // drains is a client whose cue mechanism is inert, whatever it does about sound.
            MobaEntry.Attachment(
                frame = { delta ->
                    host.frame(delta)
                    if (newMatch.poll()) {
                        // `playerIdOrNull` and not `playerId`: the swap and the repopulate land
                        // in one barrier action, but a client must not die over a tick in which
                        // it happened to look.
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
}
