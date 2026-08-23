package dev.wildware.moba.entry

import dev.wildware.moba.MobaGame
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
            // `host::frame` and nothing else: a client binds no port, drains no command queue and
            // holds no resource the backend's own `close` does not already own.
            MobaEntry.Attachment(frame = host::frame)
        }
    }
}
