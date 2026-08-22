package dev.wildware.udea.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TickTest {

    @Test
    fun `arithmetic moves along the tick axis`() {
        val start = Tick(100)
        assertEquals(Tick(103), start + 3)
        assertEquals(Tick(97), start - 3)
        assertEquals(Tick(101), start.inc())
        assertEquals(Tick(99), start.dec())
    }

    @Test
    fun `ticksSince is a signed duration in ticks`() {
        assertEquals(40L, Tick(100).ticksSince(Tick(60)))
        assertEquals(-40L, Tick(60).ticksSince(Tick(100)))
        assertEquals(0L, Tick(7).ticksSince(Tick(7)))
    }

    @Test
    fun `ordering follows the underlying count`() {
        assertTrue(Tick(1) < Tick(2))
        assertTrue(Tick(2) > Tick(1))
        assertEquals(0, Tick(5).compareTo(Tick(5)))
        assertEquals(Tick(9), listOf(Tick(3), Tick(9), Tick(1)).max())
    }

    @Test
    fun `until is half-open and iterates in ascending order`() {
        val range = Tick(10) until Tick(14)

        assertEquals(4L, range.count)
        assertEquals(listOf(Tick(10), Tick(11), Tick(12), Tick(13)), range.toList())
        assertTrue(Tick(10) in range)
        assertTrue(Tick(13) in range)
        assertFalse(Tick(14) in range, "until is half-open, so the end tick is excluded")
        assertFalse(Tick(9) in range)
    }

    @Test
    fun `an empty or inverted range has no ticks`() {
        val empty = Tick(5) until Tick(5)
        assertTrue(empty.isEmpty)
        assertEquals(0L, empty.count)
        assertEquals(emptyList(), empty.toList())

        val inverted = Tick(9) until Tick(4)
        assertTrue(inverted.isEmpty)
        assertEquals(0L, inverted.count)
        assertEquals(emptyList(), inverted.toList())
    }

    @Test
    fun `iterating past the end fails rather than running forever`() {
        val iterator = (Tick(0) until Tick(1)).iterator()
        assertEquals(Tick(0), iterator.next())
        assertFalse(iterator.hasNext())
        assertFailsWith<NoSuchElementException> { iterator.next() }
    }

    @Test
    fun `a tick range replays exactly its own length`() {
        // The reason until is half-open: stepping the whole range leaves the clock at `end`.
        val from = Tick(1_000)
        val to = Tick(1_060)
        var cursor = from
        for (unused in from until to) cursor = cursor.inc()
        assertEquals(to, cursor)
        assertEquals(to.ticksSince(from), (from until to).count)
    }

    @Test
    fun `ZERO is the first tick`() {
        assertEquals(Tick(0), Tick.ZERO)
        assertEquals(0L, Tick.ZERO.value)
    }
}
