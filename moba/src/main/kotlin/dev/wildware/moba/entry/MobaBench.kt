package dev.wildware.moba.entry

import dev.wildware.udea.core.host.RenderMode
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * `moba.bench`: boot Offscreen, present one frame, write the phase breakdown, exit zero.
 *
 * This is the process `udeaBenchStartup` launches, and the only reason it is a separate entry
 * point rather than a flag on [MobaClient] is that it must **exit**, which no other entry point
 * may do. Everything else about it is the ordinary composition root: it goes through
 * [MobaEntry.runWithGl] like the client and the agent do, so what it measures is the real boot
 * and not a harness that resembles one.
 *
 * ## What is inside the number
 *
 * Everything from the OS's process start: JVM boot, class loading, LWJGL native extraction, GL
 * context creation, the renderer's asset load, world construction, and the first swapped buffer.
 * Spec 3.5 keeps a real GL context in `Offscreen`, so driver initialisation is in the budget —
 * which is intended, since it is in the player's wait too, and it is recorded as its own phase so
 * a slow CI GPU is diagnosable rather than mysterious.
 *
 * ## Why it halts
 *
 * [Runtime.halt] rather than a clean shutdown, and deliberately: a benchmark that measured GL
 * teardown would be measuring something no player ever waits for, and — more practically — the
 * exit is requested from the render thread's own callback, where `GlThread.stop` would post a
 * runnable to the loop it is blocking and then wait ten seconds for it. The trace is written
 * *before* the halt, so nothing is lost.
 */
public object MobaBench {

    /** Where the phase breakdown is written. Defaults to the CI report path issue #94 names. */
    public const val OUTPUT_PROPERTY: String = "udea.bench.out"

    /** The flag `udeaBenchStartup` passes. Present so the entry point refuses to run without it. */
    public const val EXIT_AFTER_FIRST_FRAME: String = "--exit-after-first-frame"

    @JvmStatic
    public fun main(args: Array<String>) {
        StartupTrace.enterMain()
        require(args.contains(EXIT_AFTER_FIRST_FRAME)) {
            "moba.bench exists to start, draw once and exit; run it with $EXIT_AFTER_FIRST_FRAME " +
                "so that is visible on the command line rather than implied by the main class"
        }
        // Offscreen and not Windowed: issue #94 puts Windowed out of scope because window-manager
        // latency is outside this build's control and would make the budget a property of the CI
        // machine's desktop environment.
        val mode = MobaEntry.modeFromProperties(fallback = RenderMode.Offscreen)
        require(mode != RenderMode.Headless) {
            "moba.bench measures start-to-first-frame; RenderMode.Headless presents no frame"
        }
        var frames = 0
        MobaEntry.runWithGl(mode) { host, _ ->
            StartupTrace.world { MobaEntry.seed(host) }
            MobaEntry.Attachment(
                frame = { delta ->
                    // The *second* entry, not the first: the callback returns before the buffer
                    // swap, so frame 1 is only provably presented once the loop has come back
                    // round. See `StartupTrace.firstFramePresented`.
                    if (frames == 1) {
                        StartupTrace.firstFramePresented()
                        report()
                        // Flush explicitly: `halt` runs no shutdown hook and does not drain
                        // `System.out`, and a benchmark whose result is in an unflushed buffer
                        // reports nothing while exiting zero.
                        System.out.flush()
                        System.err.flush()
                        Runtime.getRuntime().halt(0)
                    }
                    frames++
                    host.frame(delta)
                },
            )
        }
        // Only reachable if the loop exited before two frames -- a driver that died, or a window
        // closed by hand. Non-zero, because a bench that presented no frame must not look green.
        System.err.println("[moba.bench] the render loop exited after $frames frame(s) without presenting one")
        exitProcess(1)
    }

    /** Writes the breakdown where `udeaBenchStartup` will read it, and echoes it. */
    private fun report() {
        val json = StartupTrace.toJson()
        val target = System.getProperty(OUTPUT_PROPERTY)
        if (target != null) {
            val path = Path.of(target)
            path.parent?.createDirectories()
            path.writeText(json)
        }
        println("[moba.bench] first frame in ${StartupTrace.firstFrameMillis} ms")
        print(json)
    }
}
