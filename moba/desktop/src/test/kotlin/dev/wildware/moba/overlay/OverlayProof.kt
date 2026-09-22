package dev.wildware.moba.overlay

import dev.wildware.moba.entry.MobaLaunch
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.render.OverlaySystem
import dev.wildware.udea.render.ScreenTarget
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.capture
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteBatch2D
import dev.wildware.udea.render.draw.SpriteRegion
import dev.wildware.udea.render.draw.SpriteTexture
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * `runOverlayProof`: the owner's case from issue #275, played out in a real game rather than a fixture.
 *
 * `moba` opens a **visible window** with an overlay registered, a heads-up panel of tinted quads cut
 * from a 2x2 white texture the game made itself, drawn through `OverlayResources.batch`. It then runs
 * for [SECONDS] seconds of real frames. Each second it checks that the overlay drew since the last
 * check and that a capture of the game still comes back, and prints a line saying so. At the end it
 * closes the window itself and exits 0. The window is the player's, so the panel is on it, and the
 * capture is the agent's, which by spec 3.7 never holds an overlay. A person watching the display, or
 * a grab of it, sees the panel. The captures written to `-Dudea.overlayproof.dir` show the game
 * without it.
 *
 * `-Dudea.overlayproof.forgetBegin=true` runs robot-game's mistake instead: the same panel with no
 * `beginPixels()`. That overlay throws on its first frame. On this branch the process says so, with
 * the exception and its stack trace on stderr, and exits non-zero. Before the fix the same run closed
 * its window and exited 0 with nothing said, which is what the owner saw.
 *
 * Needs a display, so it runs by name and never on `check`, for the reason `runMatchShot` gives.
 */
object OverlayProof {

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(property("udea.overlayproof.dir")).apply { mkdirs() }
        val forgetBegin = System.getProperty("udea.overlayproof.forgetBegin") == "true"
        val panel = Panel(forgetBegin)
        val passed = AtomicBoolean(false)

        MobaLaunch.runWithGl(RenderMode.Windowed, overlay = { resources -> panel.also { it.batch = resources.batch } }) { host, rendering ->
            rendering.scene.frameLevel()
            val slot = checkNotNull(rendering.pipeline.capture) { "this pipeline cannot be captured" }
            // Off the render thread: a capture blocks for a frame, and the render thread draws it.
            val watcher = Thread({
                try {
                    var drawn = 0
                    for (second in 1..SECONDS) {
                        TimeUnit.SECONDS.sleep(1)
                        val now = panel.frames.get()
                        val shot = slot.capture(CaptureRequest())
                        check(now > drawn) { "second $second: the overlay drew no frame since the last check (still $now)" }
                        drawn = now
                        say("second $second: window open, overlay has drawn $now frames, capture ${shot.width}x${shot.height} at tick ${shot.tick.value}")
                        if (second in SHOTS) File(out, "issue275-moba-capture-${second}s.png").writeBytes(shot.bytes)
                    }
                    passed.set(true)
                    say("all checks passed after ${SECONDS}s; the window closes now")
                } catch (failed: Exception) {
                    // Reported and then the game is closed: a proof that stopped checking and left the
                    // window running would wait for ever on a person to close it.
                    System.err.println("overlay-proof: a check failed: $failed")
                    failed.printStackTrace()
                } finally {
                    rendering.requestExit()
                }
            }, "overlay-proof-watcher")
            watcher.isDaemon = true
            watcher.start()
            MobaLaunch.Attachment(frame = host::frame)
        }

        if (!passed.get()) {
            System.err.println("overlay-proof: the game stopped before $SECONDS seconds were up")
            exitProcess(1)
        }
    }

    /**
     * A heads-up panel across the bottom of the window, drawn as a game draws one: tinted quads cut
     * from a 2x2 white texture of its own. With [forgetBegin] it skips `beginPixels()`, as
     * robot-game's `Hud` did.
     */
    private class Panel(private val forgetBegin: Boolean) : OverlaySystem {

        lateinit var batch: SpriteBatch2D

        val frames = AtomicInteger()

        private val white: SpriteRegion by lazy {
            SpriteRegion(SpriteTexture.fromRgba(2, 2, ByteArray(2 * 2 * 4) { -1 }, "overlay-proof-white"), 0, 0, 2, 2)
        }

        override fun render(target: ScreenTarget, dtSeconds: Float) {
            val width = target.width.toFloat()
            val height = target.height * 0.14f
            if (!forgetBegin) batch.beginPixels()
            batch.draw(white, 0f, 0f, width, height, PANEL)
            batch.draw(white, 0f, height - 3f, width, 3f, FRAME)
            // Three bars, one filling and emptying once every two seconds of frames, so a grab of the
            // window taken at different moments shows a panel that is being redrawn, not a still.
            val fill = (frames.get() % 120) / 120f
            for (bar in 0 until 3) {
                val y = height * (0.2f + bar * 0.25f)
                batch.draw(white, width * 0.04f, y, width * 0.3f, height * 0.14f, DIM)
                val share = if (bar == 0) fill else (bar + 1) / 4f
                batch.draw(white, width * 0.04f, y, width * 0.3f * share, height * 0.14f, BARS[bar])
            }
            if (!forgetBegin) batch.end()
            frames.incrementAndGet()
        }
    }

    private fun property(name: String): String = checkNotNull(System.getProperty(name)) { "-D$name was not set" }

    private fun say(line: String) {
        println("overlay-proof: $line")
        System.out.flush()
    }

    /** Past the eight seconds after which the owner's window closed itself, with room to spare. */
    private const val SECONDS = 20

    /** The seconds whose captures are written: before, around and well after the eighth. */
    private val SHOTS = setOf(2, 9, 20)

    private val PANEL = Rgba.of(0.08f, 0.10f, 0.12f, 0.85f)
    private val FRAME = Rgba.of(0.49f, 0.85f, 0.63f)
    private val DIM = Rgba.of(0.16f, 0.20f, 0.22f)
    private val BARS = listOf(Rgba.of(0.49f, 0.85f, 0.63f), Rgba.of(0.88f, 0.66f, 0.36f), Rgba.of(0.44f, 0.67f, 0.91f))
}
