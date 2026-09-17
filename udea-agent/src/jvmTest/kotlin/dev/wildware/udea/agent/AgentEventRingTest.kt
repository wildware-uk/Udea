package dev.wildware.udea.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The ring itself, at the boundaries the digest depends on.
 *
 * `AgentBridgeTest` covers the two properties that matter to a caller - bounded, and
 * non-destructive to read. These cover the windowing arithmetic underneath them, which is where
 * an off-by-one would silently hand the digest the *oldest* twenty events instead of the newest
 * and look entirely plausible doing it.
 */
class AgentEventRingTest {

    private fun AgentEventRing.recent(limit: Int): List<String> {
        val out = ArrayList<String>()
        forEachRecent(limit) { out.add(it) }
        return out
    }

    @Test
    fun `forEachRecent returns the newest entries, oldest of those first`() {
        val ring = AgentEventRing(capacity = 10)
        repeat(6) { ring.record("e$it") }

        assertEquals(listOf("e3", "e4", "e5"), ring.recent(3))
    }

    @Test
    fun `forEachRecent past a wrap still reads the newest entries`() {
        val ring = AgentEventRing(capacity = 4)
        repeat(10) { ring.record("e$it") }

        assertEquals(listOf("e6", "e7", "e8", "e9"), ring.recent(4))
        assertEquals(listOf("e8", "e9"), ring.recent(2))
    }

    @Test
    fun `a limit larger than the ring returns everything held`() {
        val ring = AgentEventRing(capacity = 4)
        ring.record("only")

        assertEquals(listOf("only"), ring.recent(100))
        assertEquals(1, ring.size)
    }

    @Test
    fun `a limit of zero visits nothing`() {
        val ring = AgentEventRing(capacity = 4)
        ring.record("a")

        assertEquals(emptyList(), ring.recent(0))
    }

    @Test
    fun `totalRecorded counts what was dropped, so a wrap is detectable`() {
        val ring = AgentEventRing(capacity = 2)
        repeat(5) { ring.record("e$it") }

        // size alone cannot tell "nothing happened" from "the ring wrapped".
        assertEquals(2, ring.size)
        assertEquals(5L, ring.totalRecorded)
    }

    @Test
    fun `clear empties the ring but keeps the history count`() {
        val ring = AgentEventRing(capacity = 4)
        repeat(3) { ring.record("e$it") }

        ring.clear()

        assertEquals(0, ring.size)
        assertEquals(emptyList(), ring.recent(4))
        assertEquals(3L, ring.totalRecorded, "clearing the ring does not un-happen the events")
    }

    @Test
    fun `a ring with no capacity is refused`() {
        assertFailsWith<IllegalArgumentException> { AgentEventRing(capacity = 0) }
    }

    @Test
    fun `a negative limit is refused rather than silently read as zero`() {
        assertFailsWith<IllegalArgumentException> { AgentEventRing(capacity = 4).forEachRecent(-1) { } }
    }
}
