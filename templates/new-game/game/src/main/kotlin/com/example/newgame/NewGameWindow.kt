package com.example.newgame

import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * The launcher a player runs: the game in a window. `./gradlew runWindow`.
 *
 * Four steps, in an order that is forced rather than chosen:
 *
 * 1. **What is drawn** is declared on a `RenderRegistry` - [NewGameScene].
 * 2. **The backend** opens the window and builds its drawing pipeline out of that registry, so the
 *    registry has to be complete first.
 * 3. **The host** is built over the backend, which is how the pipeline gets the world to draw.
 * 4. **The backend drives the host**: from here the window's frame loop runs the simulation at its
 *    fixed tick and draws between ticks. This thread waits for the window to close.
 *
 * The same `NewGame.definition()` a server and a test build is what runs here; only the backend is
 * new. A server is `./gradlew run`, which opens nothing.
 *
 * ## `-Pseconds=N`, and why the check after `awaitExit` is not decoration
 *
 * `-Pseconds=N` closes the window after N seconds of drawing, for a script that wants the window up
 * for a known length of time and then gone. A player leaves it unset and closes the window.
 *
 * A frame loop can also stop on its own - a renderer throws, a driver goes away, a pipeline ends -
 * and when it does, `awaitExit` returns exactly as it does for a clean close. That is not
 * hypothetical: an engine change in September 2026 ended the loop about eight seconds in, and every
 * game that met it exited **0** with nothing logged, because nothing was asking. So when a run was
 * given a length and did not reach it, this launcher says so on standard error and exits non-zero.
 */
public object NewGameWindow {

    /** The game's `assets/` directory, absolute. `runWindow` sets it. */
    private const val ASSET_ROOT_PROPERTY: String = "newgame.assets.root"

    /** Closes the window after this many seconds of drawing, when set. `-Pseconds=N` sets it. */
    private const val SECONDS_PROPERTY: String = "newgame.seconds"

    /**
     * How much of a requested run has to be reached for the window to count as having lasted it.
     *
     * Not 1.0: the last frame before the close lands somewhere inside the final frame's time, and a
     * software rasteriser's frames are long. 0.9 is far below anything a loop that died could
     * reach, and far above the fraction of a frame this is here to tolerate.
     */
    private const val LASTED: Float = 0.9f

    /** Milliseconds in a second: frame deltas are accumulated as whole milliseconds. */
    private const val MILLIS: Float = 1000f

    @JvmStatic
    public fun main(args: Array<String>) {
        val assetRoot = Path.of(
            checkNotNull(System.getProperty(ASSET_ROOT_PROPERTY)) {
                "-D$ASSET_ROOT_PROPERTY is not set; `runWindow` in game/build.gradle.kts sets it to " +
                    "game/assets, where the model files are"
            },
        )
        val runSeconds = System.getProperty(SECONDS_PROPERTY)?.toFloat()

        val registry = RenderRegistry()
        NewGameScene.register(registry, assetRoot, NewGameAssets.registry)
        val backend = KoolBackend.start(RenderMode.Windowed, WindowConfig(title = "new-game"), registry)
        // Written on the render thread and read on this one once the loop has gone, so they are
        // atomics rather than plain counters: how far the window got is the whole of the report
        // below, and a stale read of it would be a report about nothing.
        val frames = AtomicInteger()
        val drawnMillis = AtomicInteger()
        backend.use {
            val host = GameHost(RenderMode.Windowed, NewGame.definition(), backend)
            // Only the render thread touches these two, because only the render thread runs this
            // lambda.
            var announced = 0
            var closing = false
            backend.drive { seconds ->
                host.frame(seconds)
                val count = frames.incrementAndGet()
                val drawn = drawnMillis.addAndGet((seconds * MILLIS).toInt()) / MILLIS
                // On the first frame rather than after a batch of them: this line says the window
                // exists and the loop has begun, and a script that also wants "and it kept going"
                // reads the seconds below. Two facts, two lines.
                if (count == 1) println("new-game: window open")
                // Once a second, and only for a run that was given a length: a script watching this
                // log can see the loop is still alive, and a player does not want a line a second.
                if (runSeconds != null && drawn >= announced + 1f) {
                    announced = drawn.toInt()
                    println("new-game: drawing, ${announced}s, $count frames")
                }
                // Closed from a thread of its own: `close` waits for the frame loop to end, and
                // this lambda *is* the frame loop. Once, because frames keep arriving until the
                // close lands and a second `close` would be waiting on the first.
                if (runSeconds != null && drawn >= runSeconds && !closing) {
                    closing = true
                    thread(name = "new-game-close") { backend.close() }
                }
            }
            // Until the player closes the window, or the run's length closes it.
            backend.awaitExit()
        }
        val drawn = drawnMillis.get() / MILLIS
        println("new-game: window closed after ${drawn.toInt()}s, ${frames.get()} frames")
        if (runSeconds != null && drawn < runSeconds * LASTED) {
            System.err.println(
                "new-game: the window stopped drawing after ${drawn}s of the ${runSeconds}s it was " +
                    "asked for, at frame ${frames.get()}. The frame loop ended without this process " +
                    "asking it to.",
            )
            exitProcess(1)
        }
    }
}
