package dev.wildware.udea.net.prediction

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Remote units slide, hold when starved, and step across a teleport rather than walking it. */
class EntityInterpolationTest {

    private val unit = NetId.of(index = 7, generation = 0)
    private val pose = PredictedPose()

    private fun interpolator() = EntityInterpolator()

    @Test
    fun `a remote unit slides between the two snapshots that bracket the render tick`() {
        val interpolation = interpolator()
        interpolation.record(unit, Tick(10), 0f, 0f)
        interpolation.record(unit, Tick(20), 10f, -20f)

        assertTrue(interpolation.sample(unit, 15.0, pose))
        assertEquals(5f, pose.x, TOLERANCE, "halfway in time is halfway in space")
        assertEquals(-10f, pose.y, TOLERANCE)

        interpolation.sample(unit, 12.5, pose)
        assertEquals(2.5f, pose.x, TOLERANCE)
    }

    @Test
    fun `the slide is monotonic - no frame moves the unit backwards`() {
        val interpolation = interpolator()
        for (tick in 0..10) interpolation.record(unit, Tick(tick.toLong()), tick * SPEED, 0f)
        var previous = Float.NEGATIVE_INFINITY
        var render = 0.0
        while (render < 10.0) {
            interpolation.sample(unit, render, pose)
            assertTrue(pose.x >= previous, "a remote unit must never step backwards, at $render")
            previous = pose.x
            render += SUB_TICK
        }
    }

    @Test
    fun `sampling past the newest snapshot holds rather than extrapolating`() {
        val interpolation = interpolator()
        interpolation.record(unit, Tick(10), 0f, 0f)
        interpolation.record(unit, Tick(11), 1f, 0f)

        interpolation.sample(unit, 20.0, pose)
        assertEquals(1f, pose.x, "a starved buffer holds the newest sample; extrapolating overshoots")
        assertEquals(1L, interpolation.starved, "and says so, so the choice is measurable")
    }

    @Test
    fun `an unknown entity is reported rather than drawn at whatever the pose held`() {
        val interpolation = interpolator()
        pose.set(999f, 999f)
        assertFalse(interpolation.sample(unit, 1.0, pose), "nothing is known about this id yet")
        assertEquals(999f, pose.x, "and the caller's pose must be left alone")
    }

    @Test
    fun `a jump past the teleport distance is stepped, not slid across`() {
        val interpolation = interpolator()
        interpolation.record(unit, Tick(10), 0f, 0f)
        interpolation.record(unit, Tick(20), 400f, 0f)

        interpolation.sample(unit, 15.0, pose)
        assertEquals(0f, pose.x, "the unit holds at the old position instead of walking the map")
        assertEquals(1L, interpolation.teleports)

        interpolation.sample(unit, 20.0, pose)
        assertEquals(400f, pose.x, "and arrives in one step once the render clock reaches it")
    }

    @Test
    fun `a duplicate or reordered snapshot is rejected rather than corrupting the ring`() {
        val interpolation = interpolator()
        interpolation.record(unit, Tick(10), 0f, 0f)
        interpolation.record(unit, Tick(20), 10f, 0f)
        interpolation.record(unit, Tick(15), 500f, 0f)
        interpolation.record(unit, Tick(20), 500f, 0f)

        assertEquals(2L, interpolation.rejected, "the late one and the duplicate are both dropped")
        interpolation.sample(unit, 15.0, pose)
        assertEquals(5f, pose.x, TOLERANCE, "and the interpolation is unaffected by them")
    }

    @Test
    fun `a recycled id does not inherit the previous entity's track`() {
        val interpolation = interpolator()
        interpolation.record(unit, Tick(10), 0f, 0f)
        interpolation.forgetAllExcept { false }
        assertEquals(0, interpolation.tracked)
        assertFalse(interpolation.sample(unit, 12.0, pose), "the track must be gone with the entity")
    }

    @Test
    fun `the render clock settles onto real time, one drawn tick per client tick`() {
        val clock = InterpolationClock()
        var server = 100L
        clock.onSnapshot(Tick(server))
        // Let the easing settle first: the interesting property is the steady state, not the
        // first few ticks where it is deliberately catching up to the delay it was started with.
        repeat(SETTLE_TICKS) {
            server++
            clock.onSnapshot(Tick(server))
            clock.advance()
        }
        val start = clock.renderTick
        repeat(MEASURE_TICKS) {
            server++
            clock.onSnapshot(Tick(server))
            clock.advance()
        }
        val advanced = clock.renderTick - start
        assertTrue(
            abs(advanced - MEASURE_TICKS) < CLOCK_TOLERANCE,
            "in the steady state the render clock must track real time; it advanced $advanced " +
                "over $MEASURE_TICKS ticks",
        )
        val behind = server - clock.renderTick
        assertTrue(
            abs(behind - InterpolationClock.DEFAULT_DELAY_TICKS) < CLOCK_TOLERANCE,
            "and must stay the interpolation delay behind the newest snapshot; it was $behind",
        )
    }

    @Test
    fun `the render clock eases through a gap instead of stalling and jumping`() {
        val clock = InterpolationClock()
        clock.onSnapshot(Tick(100))
        // Nothing arrives for five ticks - a burst of loss - and then the link recovers.
        repeat(5) { clock.advance() }
        val duringGap = clock.renderTick
        assertTrue(
            duringGap > 100.0 - InterpolationClock.DEFAULT_DELAY_TICKS,
            "the render clock keeps moving through a gap; a stalled one is a stuttering unit",
        )
        clock.onSnapshot(Tick(110))
        var previous = clock.renderTick
        repeat(20) {
            clock.advance()
            val step = clock.renderTick - previous
            assertTrue(
                step <= InterpolationClock.DEFAULT_MAX_RATE + CLOCK_TOLERANCE,
                "catching up must be rate-limited, stepped $step",
            )
            previous = clock.renderTick
        }
    }

    @Test
    fun `a reordered snapshot never drags the render clock backwards`() {
        val clock = InterpolationClock()
        clock.onSnapshot(Tick(100))
        clock.onSnapshot(Tick(120))
        val target = clock.target
        clock.onSnapshot(Tick(105))
        assertEquals(target, clock.target, "an overtaken datagram must not rewind everybody else")
    }

    @Test
    fun `a stall past the resync window is re-anchored rather than eased for a minute`() {
        val clock = InterpolationClock()
        clock.onSnapshot(Tick(100))
        clock.onSnapshot(Tick(1000))
        clock.advance()
        assertEquals(1L, clock.resyncs)
        assertEquals(clock.target, clock.renderTick, "and lands on the target in one step")
    }

    private companion object {
        const val TOLERANCE = 1e-4f
        const val CLOCK_TOLERANCE = 0.05
        const val SETTLE_TICKS = 60
        const val MEASURE_TICKS = 60
        const val SPEED = 0.75f
        const val SUB_TICK = 0.1
    }
}
