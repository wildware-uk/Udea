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
 * `./gradlew :moba:runClient`
 */
public object MobaClient {

    /** Boots a window and blocks until it closes. */
    @JvmStatic
    public fun main(args: Array<String>) {
        val mode = MobaEntry.modeFromProperties(fallback = RenderMode.Windowed)
        println("[moba.client] ${MobaGame.NAME} ${MobaGame.VERSION} $mode")
        MobaEntry.runWithGl(mode) { host, _ ->
            MobaEntry.seed(host)
            // `host::frame` and nothing else: a client binds no port, drains no command queue and
            // holds no resource the backend's own `close` does not already own. The `Rendering`
            // is ignored for the same reason - a player steers the camera with input, not with a
            // control surface an agent calls into.
            MobaEntry.Attachment(frame = host::frame)
        }
    }
}
