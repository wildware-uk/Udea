package dev.wildware.moba.entry

import dev.wildware.moba.MobaGame
import dev.wildware.udea.core.host.RenderMode

/**
 * `moba.server`: the dedicated server. No GL context, no window, no `Presentation`.
 *
 * Ticks as fast as the CPU allows through the real [dev.wildware.udea.core.loop.GameLoop], so
 * pause, step and rewind all mean what they mean everywhere else. It binds no agent surface and
 * no network socket: `udea-net` is not wired into `moba` yet, so what this actually is today is a
 * simulation with nobody connected to it - which is still the exact process a `RenderMode`
 * regression would show up in first.
 *
 * `./gradlew :moba:runServer`
 */
public object MobaServer {

    /** Boots and blocks. Kill the process to stop it. */
    @JvmStatic
    public fun main(args: Array<String>) {
        val host = MobaGame.host(RenderMode.Headless)
        MobaEntry.seed(host)
        println("[moba.server] ${MobaGame.NAME} ${MobaGame.VERSION} headless; ticking")
        Runtime.getRuntime().addShutdownHook(Thread { host.stop() })
        host.run()
    }
}
