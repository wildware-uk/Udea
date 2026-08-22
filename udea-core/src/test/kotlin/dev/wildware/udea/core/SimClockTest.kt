package dev.wildware.udea.core

import java.lang.reflect.Modifier
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SimClockTest {

    @Test
    fun `dt is the reciprocal of the tick rate`() {
        assertEquals(1f / 60f, SimClock(60).dt)
        assertEquals(1f / 128f, SimClock(128).dt)
    }

    @Test
    fun `a non-positive tick rate is rejected`() {
        assertFailsWith<IllegalArgumentException> { SimClock(0) }
        assertFailsWith<IllegalArgumentException> { SimClock(-1) }
    }

    @Test
    fun `a fresh clock reads tick zero`() {
        val clock = SimClock()
        assertEquals(Tick.ZERO, clock.tick)
        assertEquals(0.0, clock.time)
    }

    @Test
    fun `time equals tick times dt exactly after 100000 ticks`() {
        val clock = SimClock(60)
        repeat(TICKS) { clock.advance() }

        assertEquals(Tick(TICKS.toLong()), clock.tick)
        assertEquals(TICKS.toLong() * clock.dt.toDouble(), clock.time)
    }

    @Test
    fun `time is derived, so jumping to a tick equals stepping to it`() {
        // A snapshot restore sets the tick and nothing else. If time were accumulated, the
        // restored clock would disagree with a clock that had actually run those ticks.
        val stepped = SimClock(60)
        repeat(TICKS) { stepped.advance() }

        val restored = SimClock(60)
        restored.moveTo(Tick(TICKS.toLong()))

        assertEquals(stepped.tick, restored.tick)
        assertEquals(stepped.time, restored.time)
    }

    @Test
    fun `rewinding restores the earlier time exactly`() {
        val clock = SimClock(60)
        repeat(600) { clock.advance() }
        val atTick600 = clock.time

        repeat(400) { clock.advance() }
        clock.moveTo(Tick(600))

        assertEquals(atTick600, clock.time)
    }

    @Test
    fun `accumulating a float delta drifts, which is why time is derived`() {
        // This is the defect being closed: common/UdeaGameManager.kt:222 ran `time += delta`
        // once per frame. Reproduce it beside the derived clock and show they disagree.
        val clock = SimClock(60)
        var accumulated = 0f
        repeat(TICKS) {
            clock.advance()
            accumulated += clock.dt
        }

        assertNotEquals(
            clock.time,
            accumulated.toDouble(),
            "if these ever agree, this test has stopped proving anything",
        )
        val drift = abs(clock.time - accumulated.toDouble())
        assertTrue(
            drift > 1e-3,
            "expected the float accumulator to have drifted measurably, drift was $drift",
        )
    }

    @Test
    fun `SimClock holds no accumulator field`() {
        val fields = SimClock::class.java.declaredFields.filterNot { it.isSynthetic }

        val mutable = fields.filterNot { Modifier.isFinal(it.modifiers) }
        assertEquals(
            listOf("tick"),
            mutable.map { it.name },
            "the tick counter must be the clock's only mutable state; anything else is an accumulator",
        )
        assertEquals(
            java.lang.Long.TYPE,
            mutable.single().type,
            "tick must be stored as a whole count, never as elapsed seconds",
        )

        val floatingPoint = fields.filter {
            it.type == java.lang.Float.TYPE || it.type == java.lang.Double.TYPE
        }
        assertTrue(
            floatingPoint.all { Modifier.isFinal(it.modifiers) },
            "every floating-point field must be immutable, found mutable: " +
                floatingPoint.filterNot { Modifier.isFinal(it.modifiers) }.map { it.name },
        )
        assertEquals(
            listOf("dt"),
            floatingPoint.map { it.name },
            "dt is the only seconds-denominated value the clock may hold",
        )
    }

    @Test
    fun `SimClock exposes no seconds-denominated mutator`() {
        val secondsSetters = SimClock::class.java.declaredMethods
            .filterNot { it.isSynthetic }
            .filter { method ->
                method.parameterTypes.any {
                    it == java.lang.Float.TYPE || it == java.lang.Double.TYPE
                }
            }
        assertEquals(
            emptyList(),
            secondsSetters.map { it.name },
            "nothing may push seconds into the clock; the loop converts elapsed time to whole ticks",
        )
    }

    private companion object {
        const val TICKS: Int = 100_000
    }
}
