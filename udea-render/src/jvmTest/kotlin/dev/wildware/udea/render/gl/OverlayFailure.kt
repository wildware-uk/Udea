package dev.wildware.udea.render.gl

import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.OverlaySystem
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.ScreenTarget
import dev.wildware.udea.render.backend.GlContextException
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.CaptureStalledException
import dev.wildware.udea.render.capture.capture
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteBatch2D
import dev.wildware.udea.render.draw.SpriteRegion
import dev.wildware.udea.render.draw.SpriteTexture
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Issue #275's real defect, shared by both render modes: **a frame that throws stops the game, and
 * says why.**
 *
 * The overlay here is robot-game's `Hud` as it was committed. It draws tinted quads from a 2x2 white
 * texture through `OverlayResources.batch` and never calls `beginPixels()`, so `SpriteBatch2D.draw`
 * throws on the first frame it runs. Before the fix the engine turned that into silence. The render
 * thread kept the exception in a field nobody read, `awaitExit()` returned as if the window had been
 * closed, a game's `main` exited 0, and a waiting capture was told only that "the render pipeline was
 * closed before the frame was read".
 *
 * So this asks for the exception in all three places a game or an agent could meet it. The capture's
 * failure has to carry it as a cause, `awaitExit()` has to throw with it, and it has to reach stderr
 * with the overlay's own frame in the trace. Every check is on the *same* exception object or on its
 * own text, never on a message the engine makes up, so a fix that invented a plausible error would
 * not pass.
 */
internal object OverlayFailure {

    fun run(mode: RenderMode, name: String) {
        GlAvailability.require()

        val original = System.err
        val stderr = ByteArrayOutputStream()
        // Both: the text is asserted on below, and a person reading the test report still sees it.
        System.setErr(PrintStream(TeeStream(stderr, original), true))
        try {
            val registry = RenderRegistry()
            registry.overlay({ resources -> ForgotBegin(resources.batch) })
            val backend = KoolBackend.start(
                mode,
                WindowConfig(title = "udea-overlay-failure-$name", windowWidth = 320, windowHeight = 240),
                registry,
            )
            try {
                val host = GameHost(mode, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()), backend)
                val slot = assertNotNull(backend.pipeline?.capture, "the pipeline has no capture slot")
                backend.drive(host)

                // 1. The capture fails, and the reason it carries is the overlay's exception.
                val stalled = assertFailsWith<CaptureStalledException>("$mode: a capture succeeded past a thrown frame") {
                    slot.capture(CaptureRequest(), timeoutMillis = TimeUnit.SECONDS.toMillis(20))
                }
                val thrown = assertNotNull(
                    causeOf(stalled),
                    "$mode: the capture failed without the exception that stopped the render loop: ${stalled.message}",
                )
                assertTrue(
                    thrown is IllegalStateException && "outside begin/end" in thrown.message.orEmpty(),
                    "$mode: the capture's cause is not the overlay's exception: $thrown",
                )
                // And the exception says how to fix it. A game author meets this message once and
                // has to be able to act on it without reading the engine: naming the call is the
                // whole difference between "it crashed" and "I forgot beginPixels()".
                assertTrue(
                    "beginPixels()" in thrown.message.orEmpty(),
                    "$mode: the draw-outside-begin message does not name the call to make: ${thrown.message}",
                )

                // 2. The loop has stopped, and waiting for it to stop says why rather than returning.
                assertFalse(backend.renderLoopRunning, "$mode: the render loop is still running after a frame threw")
                val exit = assertFailsWith<GlContextException>(
                    "$mode: awaitExit() returned normally for a render loop that died of an exception, " +
                        "so a game's main exits 0 with nothing said",
                ) { backend.awaitExit() }
                assertSame(thrown, exit.cause, "$mode: awaitExit() threw, but not with the frame's exception")

                // 3. And it reached stderr with the overlay's own line in the trace, for a person who
                //    has neither a capture nor awaitExit() to hand: a player with a window that closed.
                val printed = stderr.toString(Charsets.UTF_8)
                assertTrue(
                    thrown.message.orEmpty() in printed && ForgotBegin::class.java.name in printed,
                    "$mode: the render loop's failure did not reach stderr with its stack trace. stderr was:\n$printed",
                )
            } finally {
                backend.close()
            }
        } finally {
            System.setErr(original)
        }
    }

    /** The first link of [failure]'s cause chain that is not a `CaptureStalledException`. */
    private fun causeOf(failure: Throwable): Throwable? {
        var cause = failure.cause
        while (cause is CaptureStalledException) cause = cause.cause
        return cause
    }

    /** robot-game's `Hud` as committed: quads from a game-made 2x2 texture, and no `beginPixels()`. */
    class ForgotBegin(private val batch: SpriteBatch2D) : OverlaySystem {

        private val white: SpriteRegion by lazy {
            SpriteRegion(SpriteTexture.fromRgba(2, 2, ByteArray(2 * 2 * 4) { -1 }, "issue275-hud-white"), 0, 0, 2, 2)
        }

        override fun render(target: ScreenTarget, dtSeconds: Float) {
            batch.draw(white, 10f, 10f, 40f, 12f, Rgba.of(0.2f, 0.8f, 0.4f, 1f))
        }
    }

    /** Writes everything to both streams. */
    private class TeeStream(private val first: ByteArrayOutputStream, private val second: PrintStream) :
        java.io.OutputStream() {
        override fun write(b: Int) {
            synchronized(first) { first.write(b) }
            second.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(first) { first.write(b, off, len) }
            second.write(b, off, len)
        }
    }
}
