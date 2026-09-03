package dev.wildware.udea.render.gl

import dev.wildware.udea.render.backend.GlThread
import dev.wildware.udea.render.backend.WindowConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What [GlThread] says about itself in the gap between the render loop exiting and the OS
 * thread that ran it terminating.
 *
 * ## Why this exists as a test of its own
 *
 * `OffscreenBackendTest > closing the backend stops the render thread` failed twice in one
 * evening on `gl tests (xvfb)` and passed on a re-run of the identical commit, with this cause
 * chain both times (issue #178):
 *
 * ```
 * org.opentest4j.AssertionFailedError at OffscreenBackendTest.kt:206
 *     Caused by: dev.wildware.udea.render.backend.GlContextException at OffscreenBackendTest.kt:206
 *         Caused by: java.util.concurrent.CancellationException at OffscreenBackendTest.kt:206
 * ```
 *
 * Line 206 was `assertFailsWith<IllegalStateException> { backend.create(...) }`, and the chain
 * says exactly which step lost: `create` reached `GlThread.submit`, `check(isRunning)` **passed**
 * because `Thread.isAlive` was still true, the task went onto the queue, and `failAllQueued`
 * cancelled it a moment later. `GlContextException` where the contract says
 * `IllegalStateException` — a wrong answer, not a slow one.
 *
 * That window cannot be closed by waiting longer, because it is not a deadline: `run`'s
 * `finally` counts the exit latch down and only *then* fails the queue and runs the shutdown
 * hook, so a caller that observed the latch could always be inside those two statements.
 *
 * The test holds that window open on purpose rather than hoping to land in it. The shutdown
 * hook runs on the GL thread after the latch has been counted down, so parking in it puts the
 * thread in precisely the state a loaded runner produces by chance, for as long as the
 * assertions need — and makes a race into a fact.
 */
class GlThreadShutdownTest {

    @Test
    fun `a stopped GL thread refuses work before its thread object has died`() {
        GlAvailability.require()
        val gl = GlThread(
            WindowConfig(title = "udea-gl-shutdown", windowWidth = 64, windowHeight = 48),
            visible = false,
        )
        gl.start()

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        gl.onShutdown {
            entered.countDown()
            release.await()
        }

        try {
            gl.stop()

            assertTrue(
                entered.await(EXIT_WAIT_SECONDS, TimeUnit.SECONDS),
                "the render loop never reached its shutdown hook, so it never exited",
            )
            // The GL thread is now parked inside the hook and cannot leave it until this test
            // says so: `Thread.isAlive` is true, and stays true, while the loop is over.

            assertFalse(
                gl.isRunning,
                "the loop has exited but the thread reports itself running, so every check " +
                    "that guards on it is answering about the thread object rather than the loop",
            )
            assertFailsWith<IllegalStateException> { gl.submit { } }
        } finally {
            release.countDown()
        }
    }

    private companion object {

        /**
         * How long the loop may take to reach its hook once [GlThread.stop] has returned.
         *
         * Only the queue drain stands between the two, so this is a hang detector rather than
         * a budget: the honest failure it reports is "the loop did not exit at all".
         */
        const val EXIT_WAIT_SECONDS: Long = 10L
    }
}
