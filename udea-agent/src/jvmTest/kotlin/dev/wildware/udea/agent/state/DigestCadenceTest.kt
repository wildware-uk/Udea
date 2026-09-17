package dev.wildware.udea.agent.state

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the digest is rebuilt, and - the point of the whole mechanism - when it is not.
 *
 * A digest rebuilt every tick whether or not anybody is looking is a tax the shipped game pays
 * for a feature only a developer uses. Two gates make it free instead: a tick interval, and a
 * read flag that the reader sets. A game with no agent attached builds exactly one document and
 * then stops.
 */
class DigestCadenceTest {

    @Test
    fun `the first build happens without waiting for a reader`() {
        val fixture = DigestFixture(rebuildIntervalTicks = 1)

        fixture.digest.publishIfDue()

        // `{"ready":false}` is not an answer, so the document has to exist before anyone asks.
        assertEquals(1L, fixture.digest.builds)
        assertTrue(fixture.bridge.snapshot().contains("\"ready\":true"))
    }

    @Test
    fun `nothing is rebuilt while nothing has read the last document`() {
        val fixture = DigestFixture(rebuildIntervalTicks = 1)
        fixture.digest.publishIfDue()

        for (tick in 1..20) {
            fixture.bridge.publishTick(tick.toLong())
            fixture.digest.publishIfDue()
        }

        assertEquals(1L, fixture.digest.builds, "an unwatched game must not pay for the digest")
    }

    @Test
    fun `a read makes the next tick due`() {
        val fixture = DigestFixture(rebuildIntervalTicks = 1)
        fixture.digest.publishIfDue()
        assertEquals(1L, fixture.digest.builds)

        fixture.bridge.snapshot()
        fixture.bridge.publishTick(1)
        fixture.digest.publishIfDue()

        assertEquals(2L, fixture.digest.builds)
    }

    @Test
    fun `a read alone is not enough before the interval elapses`() {
        val fixture = DigestFixture(rebuildIntervalTicks = 4)
        fixture.digest.publishIfDue()

        for (tick in 1..3) {
            fixture.bridge.snapshot()
            fixture.bridge.publishTick(tick.toLong())
            fixture.digest.publishIfDue()
            assertEquals(1L, fixture.digest.builds, "rebuilt at tick $tick, inside the interval")
        }

        fixture.bridge.snapshot()
        fixture.bridge.publishTick(4)
        fixture.digest.publishIfDue()

        assertEquals(2L, fixture.digest.builds)
    }

    @Test
    fun `a rewind forces a rebuild even inside the interval`() {
        val fixture = DigestFixture(rebuildIntervalTicks = 100)
        fixture.bridge.publishTick(500)
        fixture.digest.publishIfDue()
        fixture.bridge.snapshot()

        fixture.bridge.publishTick(400)
        fixture.digest.publishIfDue()

        // The document describes a world that has been replaced; the interval is about a world
        // moving forwards.
        assertEquals(2L, fixture.digest.builds)
        assertTrue(fixture.bridge.snapshot().contains("\"tick\":400"))
    }

    @Test
    fun `publishing clears the read flag so one read buys one rebuild`() {
        val fixture = DigestFixture(rebuildIntervalTicks = 1)
        fixture.digest.publishIfDue()
        fixture.bridge.snapshot()

        fixture.bridge.publishTick(1)
        fixture.digest.publishIfDue()
        fixture.bridge.publishTick(2)
        fixture.digest.publishIfDue()

        assertEquals(2L, fixture.digest.builds)
        assertFalse(fixture.bridge.readSinceLastPublish())
    }

    @Test
    fun `a completed command makes the digest due with the tick held still`() {
        // The Phase 1 workflow is pause, spawn, step, screenshot, rewind, inspect - and two of
        // those six happen with the simulation paused, where the tick does not move at all. On
        // the tick interval alone the digest is never due again, so a command that ran and
        // completed never reaches a document: the caller polls /state until it times out, the
        // frames-advanced fallback reads `frame` out of the same frozen document, and a healthy
        // game is reported as frozen.
        val fixture = DigestFixture(rebuildIntervalTicks = 100)
        fixture.bridge.publishTick(412)
        fixture.digest.publishIfDue()
        assertEquals(1L, fixture.digest.builds)

        fixture.bridge.snapshot()
        fixture.bridge.complete(77L, AgentResult.EMPTY)
        // The tick is held exactly where it was. Nothing else has changed.
        fixture.digest.publishIfDue()

        assertEquals(2L, fixture.digest.builds, "a completed command is news whatever the tick does")
        assertTrue(
            fixture.bridge.snapshot().contains("\"completedCommandId\":77"),
            fixture.bridge.snapshot(),
        )
        assertEquals(77L, fixture.digest.lastPublishedCommandId)
    }

    @Test
    fun `a command that has already been published does not make the digest due again`() {
        val fixture = DigestFixture(rebuildIntervalTicks = 100)
        fixture.bridge.publishTick(412)
        fixture.bridge.complete(77L, AgentResult.EMPTY)
        fixture.digest.publishIfDue()
        assertEquals(1L, fixture.digest.builds)

        // Fifty polls of a paused game with no new command: the id is already in the document,
        // so the gate is back to the tick interval and an unwatched paused game stays free.
        repeat(50) {
            fixture.bridge.snapshot()
            fixture.digest.publishIfDue()
        }

        assertEquals(1L, fixture.digest.builds)
    }

    @Test
    fun `the build reports its own cost as its own timing entry`() {
        val fixture = DigestFixture()
        fixture.clock.advancePerCall = 250_000L

        fixture.digest.publish()

        // The instrument the `diag` toolset reads, so the 0.3ms budget stays visible on the
        // machine that matters rather than only in CI.
        assertEquals(250_000L, fixture.timings.lastNanosOf(DigestBudgets.TIMING_NAME))
        assertEquals(250_000L, fixture.digest.lastBuildNanos)
    }

    @Test
    fun `a fresh bridge answers not-ready until the first build`() {
        val bridge = AgentBridge()

        assertEquals(AgentBridge.NOT_READY, bridge.snapshot())
    }
}
