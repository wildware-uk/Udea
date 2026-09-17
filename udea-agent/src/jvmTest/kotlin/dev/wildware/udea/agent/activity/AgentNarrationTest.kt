package dev.wildware.udea.agent.activity

import dev.wildware.udea.agent.AgentClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `agent.say`'s state (issue #158): wall-clock expiry, refusal rather than truncation, and
 * newest-replaces rather than a queue.
 *
 * The clock is driven by hand. A test that slept would be measuring the scheduler, and the
 * engineering standards forbid one anyway; [AgentClock] being an injected `fun interface` is
 * exactly so that this can be a comparison of two numbers.
 */
class AgentNarrationTest {

    private val clock = ManualClock()

    private val narration = AgentNarration(clock)

    @Test
    fun `a line expires on wall time and leaves nothing behind`() {
        narration.say("stepping to the first tower dive", ttlSeconds = 2f, AgentSessionId.LOCAL)
        assertTrue(narration.isLive())

        clock.advanceSeconds(1.9f)
        assertEquals("stepping to the first tower dive", narration.current)

        clock.advanceSeconds(0.2f)
        assertFalse(narration.isLive())
        assertEquals("", narration.current)
    }

    @Test
    fun `expiring moves the version, so a fade-out reaches the overlay`() {
        // Without the bump the panel would keep drawing the caption until the agent happened to
        // call another tool: the re-format gate is the version, and nothing else moves on
        // expiry.
        narration.say("looking at the jungle timers", ttlSeconds = 1f, AgentSessionId.LOCAL)
        val live = narration.version

        clock.advanceSeconds(1.5f)

        assertNotEquals(live, narration.version)
    }

    @Test
    fun `an over-long line is refused and the previous one survives`() {
        narration.say("checking respawn", ttlSeconds = 30f, AgentSessionId.LOCAL)

        val refusal = checkNotNull(
            narration.say("x".repeat(AgentNarration.MAX_LENGTH + 1), 30f, AgentSessionId.LOCAL),
        ) { "an over-long line was accepted" }

        assertEquals(SayRefusal.TOO_LONG, refusal)
        assertEquals(
            "checking respawn",
            narration.current,
            "the refused line replaced the previous one, so the refusal cost the human a " +
                "caption as well",
        )
        assertTrue(
            refusal.message.contains(AgentNarration.MAX_LENGTH.toString()),
            "the refusal has to name the limit; the agent cannot read the caption back to " +
                "discover what fitted",
        )
    }

    @Test
    fun `a line of exactly the limit is accepted`() {
        assertNull(narration.say("x".repeat(AgentNarration.MAX_LENGTH), 30f, AgentSessionId.LOCAL))
    }

    @Test
    fun `the newest line replaces the previous one rather than queueing behind it`() {
        // A queue would leave a human reading a caption from four calls ago while the agent is
        // doing something else entirely.
        narration.say("first", ttlSeconds = 60f, AgentSessionId.LOCAL)
        narration.say("second", ttlSeconds = 60f, AgentSessionId.LOCAL)

        assertEquals("second", narration.current)

        // And nothing brings the first one back once the second expires.
        clock.advanceSeconds(61f)
        assertEquals("", narration.current)
    }

    @Test
    fun `a blank line and an out-of-range ttl are both refused`() {
        assertEquals(SayRefusal.EMPTY, narration.say("   ", 30f, AgentSessionId.LOCAL))
        assertEquals(SayRefusal.TTL_OUT_OF_RANGE, narration.say("x", 0f, AgentSessionId.LOCAL))
        assertEquals(SayRefusal.TTL_OUT_OF_RANGE, narration.say("x", -1f, AgentSessionId.LOCAL))
        assertEquals(
            SayRefusal.TTL_OUT_OF_RANGE,
            narration.say("x", AgentNarration.MAX_TTL_SECONDS + 1f, AgentSessionId.LOCAL),
        )
        assertEquals(SayRefusal.TTL_OUT_OF_RANGE, narration.say("x", Float.NaN, AgentSessionId.LOCAL))
    }

    @Test
    fun `the session that said it is carried with the line`() {
        val sessions = AgentSessions()
        val agent = sessions.intern("claude-2")

        narration.say("holding the wave", ttlSeconds = 30f, agent)

        assertEquals(agent, narration.currentSession)
        assertEquals("claude-2", sessions.label(narration.currentSession))
    }

    @Test
    fun `remaining seconds counts down and bottoms out at zero`() {
        narration.say("x", ttlSeconds = 4f, AgentSessionId.LOCAL)
        assertEquals(4f, narration.remainingSeconds(), 0.01f)

        clock.advanceSeconds(3f)
        assertEquals(1f, narration.remainingSeconds(), 0.01f)

        clock.advanceSeconds(2f)
        assertEquals(0f, narration.remainingSeconds())
    }

    /** A hand-driven [AgentClock]. See the class KDoc for why there is no sleep here. */
    private class ManualClock : AgentClock {
        private var nanos: Long = 1_000_000_000L

        override fun nowNanos(): Long = nanos

        fun advanceSeconds(seconds: Float) {
            nanos += (seconds.toDouble() * 1_000_000_000L).toLong()
        }
    }
}
