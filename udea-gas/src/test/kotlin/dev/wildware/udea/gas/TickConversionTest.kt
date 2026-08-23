package dev.wildware.udea.gas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * One documented rounding rule, so two builds of the same asset always agree.
 *
 * A designer writes seconds; everything downstream is ticks. If the conversion were "whatever
 * `roundToInt` does" the rule would be half-away-from-zero in one place and half-to-even in
 * another the moment somebody reached for `Math.rint`, and two builds of the same `.udea.kts`
 * could disagree by a tick — which at 60Hz is a cooldown that ends a frame apart on a server and
 * a client.
 */
class TickConversionTest {

    @Test
    fun `whole seconds convert exactly`() {
        assertEquals(60, ticksFromSeconds(1f, 60))
        assertEquals(900, ticksFromSeconds(15f, 60))
        assertEquals(0, ticksFromSeconds(0f, 60))
    }

    @Test
    fun `a half tick rounds up, always`() {
        // 0.008333.. seconds at 120Hz is exactly one tick; 0.0041666 is exactly half of one.
        assertEquals(1, ticksFromSeconds(0.5f / 120f, 120), "exactly half a tick rounds up to one")
        assertEquals(2, ticksFromSeconds(1.5f / 60f, 60), "exactly one and a half ticks rounds up to two")
        assertEquals(1, ticksFromSeconds(1.4f / 60f, 60), "below the half rounds down")
    }

    @Test
    fun `the rule is floor of seconds times rate plus a half`() {
        for (millis in 0..2_000 step 7) {
            val seconds = millis / 1000f
            val expected = Math.floor(seconds.toDouble() * 60 + 0.5).toInt()
            assertEquals(expected, ticksFromSeconds(seconds, 60), "disagreed at ${seconds}s")
        }
    }

    @Test
    fun `a negative duration is refused rather than rounded`() {
        val failure = assertFailsWith<IllegalArgumentException> { ticksFromSeconds(-1f, 60) }
        assertTrue(failure.message!!.contains("-1"), failure.message!!)
    }

    @Test
    fun `a non-positive tick rate is refused`() {
        assertFailsWith<IllegalArgumentException> { ticksFromSeconds(1f, 0) }
    }
}
