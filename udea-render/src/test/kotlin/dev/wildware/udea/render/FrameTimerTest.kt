package dev.wildware.udea.render

import dev.wildware.udea.render.support.ManualFrameClock
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one place seconds are computed in this engine (spec 5, "Time").
 *
 * Driven through a [ManualFrameClock] rather than the wall clock, because a test that slept
 * for a frame would be both slow and flaky, and because the interesting inputs -- a 40-second
 * stall, a clock that does not advance -- do not happen on demand.
 */
class FrameTimerTest {

    @Test
    fun `the first frame has no predecessor and so has no elapsed time`() {
        val clock = ManualFrameClock(nanos = 1_234_567_890L)
        val timer = FrameTimer(clock)

        // Not "nanoTime since the JVM started", which is what a naive zero-initialised
        // previous stamp would hand the first overlay frame.
        assertEquals(0f, timer.nextFrameSeconds())
    }

    @Test
    fun `a frame is the wall time since the previous one`() {
        val clock = ManualFrameClock()
        val timer = FrameTimer(clock)
        timer.nextFrameSeconds()

        clock.advanceNanos(16_666_667L)
        assertEquals(0.016666668f, timer.nextFrameSeconds(), absoluteTolerance = 1e-7f)

        clock.advanceNanos(33_333_333L)
        assertEquals(0.033333335f, timer.nextFrameSeconds(), absoluteTolerance = 1e-7f)
    }

    @Test
    fun `a stall is clamped rather than passed on`() {
        val clock = ManualFrameClock()
        val timer = FrameTimer(clock)
        timer.nextFrameSeconds()

        clock.advanceSeconds(40f)

        assertEquals(RenderPipeline.MAX_FRAME_SECONDS, timer.nextFrameSeconds())
    }

    @Test
    fun `a clamped frame does not leave the missing time to be paid back`() {
        // The residue is dropped, not carried: an overlay owes nothing for a pause, and a
        // carried debt is what turns one stall into a run of maximum-length frames.
        val clock = ManualFrameClock()
        val timer = FrameTimer(clock)
        timer.nextFrameSeconds()
        clock.advanceSeconds(40f)
        timer.nextFrameSeconds()

        clock.advanceSeconds(0.01f)

        assertEquals(0.01f, timer.nextFrameSeconds(), absoluteTolerance = 1e-6f)
    }

    @Test
    fun `two frames at the same instant produce no elapsed time`() {
        val clock = ManualFrameClock()
        val timer = FrameTimer(clock)
        timer.nextFrameSeconds()

        assertEquals(0f, timer.nextFrameSeconds())
    }

    @Test
    fun `time running backwards yields zero rather than a negative frame`() {
        val clock = ManualFrameClock(nanos = 1_000_000_000L)
        val timer = FrameTimer(clock)
        timer.nextFrameSeconds()

        clock.advanceNanos(-500_000_000L)

        // An animation stepping backwards is worse than a dropped frame, and a monotonic
        // clock is a promise this module cannot enforce on its own.
        assertEquals(0f, timer.nextFrameSeconds())
    }
}
