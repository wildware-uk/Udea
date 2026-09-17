package dev.wildware.udea.agent.activity

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentToolArg
import dev.wildware.udea.agent.AllocationProbe
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The activity ring's four load-bearing properties (issue #157): bounded, non-destructive,
 * allocation-free to read, and anchored from *declared* arguments rather than a name table.
 */
class AgentActivityRingTest {

    @Test
    fun `the oldest call is dropped, not the newest`() {
        val ring = AgentActivityRing(capacity = 3)
        repeat(5) { index -> ring.begin(command("t$index"), tick = index.toLong(), anchor = AnchorRule.NONE) }

        assertEquals(3, ring.size)
        assertEquals(listOf("t4", "t3", "t2"), names(ring, limit = 10))
    }

    @Test
    fun `reading does not consume, so two reads see the same calls`() {
        // The defect this guards: the overlay re-formats whenever the version moves, and the
        // panel re-reads the ring each time. A destructive read would empty the panel as it
        // drew it, on a schedule set by the frame rate.
        val ring = AgentActivityRing(capacity = 4)
        ring.begin(command("world.get_component"), tick = 1L, anchor = AnchorRule.NONE)

        assertEquals(names(ring, 4), names(ring, 4))
        assertEquals(1, ring.size)
    }

    @Test
    fun `a walk over a full ring allocates nothing`() {
        val ring = AgentActivityRing(capacity = 32)
        repeat(32) { index -> ring.begin(command("t$index"), tick = index.toLong(), anchor = AnchorRule.NONE) }
        // Read every field of every entry, which is what the overlay's re-format does.
        //
        // The visitor is hoisted out of the measured block, exactly as `AgentOverlayModel`
        // hoists its own: a *capturing* lambda is SAM-converted at the call site, so writing it
        // inline here would allocate one wrapper per walk and this test would measure 16 bytes
        // of its own making. That is the same reason `StateDigest` holds its four sinks as
        // fields rather than writing them at the call.
        val sink = LongArray(1)
        val visitor = AgentActivityVisitor { call ->
            sink[0] += call.tick + call.durationNanos + call.commandId +
                call.toolName.length + call.argDigest.length + call.outcome.ordinal +
                call.anchorKind.ordinal + call.anchorNetId + call.session.raw
        }
        val walk = { ring.forEachRecent(32, visitor); Unit }

        assumeTrue(AllocationProbe.isSupported, "no thread allocation counters on this JVM")
        val allocated = AllocationProbe.bytesAllocated(warmups = 200, attempts = 20, block = walk)

        assertEquals(
            0L,
            allocated,
            "an overlay re-format allocated $allocated bytes per walk over 32 entries; the " +
                "cursor is supposed to be reused, and this walk runs every time the agent " +
                "calls a tool",
        )
    }

    @Test
    fun `a call is visible while it is still running, and its outcome lands later`() {
        // The reason `begin` is separate from `complete`: a tool that wedges is exactly the call
        // a human needs to see, and a ring written only on completion shows them nothing.
        val ring = AgentActivityRing(capacity = 4)
        val command = command("time.rewind")

        val slot = ring.begin(command, tick = 9L, anchor = AnchorRule.NONE)
        assertEquals(AgentOutcome.RUNNING, first(ring).outcome)
        assertEquals(0L, first(ring).durationNanos)

        ring.complete(slot, command.id, AgentOutcome.OK, durationNanos = 2_500_000L)
        assertEquals(AgentOutcome.OK, first(ring).outcome)
        assertEquals(2_500_000L, first(ring).durationNanos)
    }

    @Test
    fun `completing a slot another call has taken over does nothing`() {
        // Without the command-id guard this would stamp `world.spawn_blueprint`'s duration onto
        // whatever now owns the slot, and a human would read a timing for a call that did not
        // have it.
        val ring = AgentActivityRing(capacity = 2)
        val slow = command("time.fast_forward")
        val slot = ring.begin(slow, tick = 1L, anchor = AnchorRule.NONE)
        ring.begin(command("a"), tick = 2L, anchor = AnchorRule.NONE)
        ring.begin(command("b"), tick = 3L, anchor = AnchorRule.NONE)

        ring.complete(slot, slow.id, AgentOutcome.OK, durationNanos = 999L)

        ring.forEachRecent(2) { call ->
            assertEquals(
                0L,
                call.durationNanos,
                "${call.toolName} was given a duration belonging to a call that had been " +
                    "evicted from its slot",
            )
        }
    }

    @Test
    fun `version moves on every write, including once the ring is full`() {
        // `size` stops moving at capacity, so a panel gated on `size` would freeze after the
        // first N calls and show a human a stale history for the rest of the session.
        val ring = AgentActivityRing(capacity = 2)
        ring.begin(command("a"), tick = 1L, anchor = AnchorRule.NONE)
        ring.begin(command("b"), tick = 2L, anchor = AnchorRule.NONE)
        val full = ring.version
        assertEquals(2, ring.size)

        ring.begin(command("c"), tick = 3L, anchor = AnchorRule.NONE)

        assertEquals(2, ring.size)
        assertNotEquals(full, ring.version, "the panel would never re-format again")
    }

    @Test
    fun `an entity anchor comes from a declared integer identity argument`() {
        val rule = AnchorRule.of(
            listOf(
                AgentToolArg("id", "integer", "packed NetId", required = true, default = null),
                AgentToolArg("component", "string", "name", required = true, default = null),
            ),
        )
        val ring = AgentActivityRing(capacity = 2)

        ring.begin(
            AgentCommand("world.get_component", mapOf("id" to "266", "component" to "Health")),
            tick = 4L,
            anchor = rule,
        )

        val call = first(ring)
        assertEquals(AnchorKind.ENTITY, call.anchorKind)
        assertEquals(266, call.anchorNetId)
    }

    @Test
    fun `a point anchor comes from declared number x and y`() {
        val rule = AnchorRule.of(
            listOf(
                AgentToolArg("blueprint", "string", "name", required = true, default = null),
                AgentToolArg("x", "number", "world x", required = false, default = null),
                AgentToolArg("y", "number", "world y", required = false, default = null),
            ),
        )
        val ring = AgentActivityRing(capacity = 2)

        ring.begin(
            AgentCommand("world.spawn_blueprint", mapOf("blueprint" to "minion", "x" to "12.5", "y" to "-3")),
            tick = 4L,
            anchor = rule,
        )

        val call = first(ring)
        assertEquals(AnchorKind.POINT, call.anchorKind)
        assertEquals(12.5f, call.anchorX)
        assertEquals(-3f, call.anchorY)
    }

    @Test
    fun `a declared position the caller omitted anchors to nothing rather than the origin`() {
        // `world.spawn_blueprint`'s x and y are optional: omitting them lets the blueprint place
        // itself. A pin at (0, 0) would tell a human the agent had spawned something at the map
        // origin, which is a specific and wrong claim.
        val rule = AnchorRule.of(
            listOf(
                AgentToolArg("x", "number", "world x", required = false, default = null),
                AgentToolArg("y", "number", "world y", required = false, default = null),
            ),
        )
        val ring = AgentActivityRing(capacity = 2)

        ring.begin(AgentCommand("world.spawn_blueprint", mapOf("blueprint" to "minion")), 1L, anchor = rule)

        assertEquals(AnchorKind.NONE, first(ring).anchorKind)
    }

    @Test
    fun `an integer argument that is not an identity does not anchor`() {
        // `limit` and `offset` are integers too. Anchoring on any integer would ring entity 40
        // every time an agent asked for forty rows.
        val rule = AnchorRule.of(
            listOf(
                AgentToolArg("limit", "integer", "rows", required = false, default = "20"),
                AgentToolArg("offset", "integer", "skip", required = false, default = "0"),
            ),
        )

        assertEquals(AnchorRule.NONE, rule)
    }

    @Test
    fun `a tool declaring no arguments anchors to nothing`() {
        assertEquals(AnchorRule.NONE, AnchorRule.of(emptyList()))
    }

    @Test
    fun `an unparseable identity argument anchors to nothing rather than to zero`() {
        val rule = AnchorRule.of(
            listOf(AgentToolArg("id", "integer", "packed NetId", required = true, default = null)),
        )
        val ring = AgentActivityRing(capacity = 2)

        ring.begin(AgentCommand("world.describe_entity", mapOf("id" to "4o")), 1L, anchor = rule)

        assertEquals(AnchorKind.NONE, first(ring).anchorKind)
    }

    @Test
    fun `the argument digest is capped, and says it was cut`() {
        val ring = AgentActivityRing(capacity = 2, digestLimit = 20)

        ring.begin(
            AgentCommand("world.set_component_field", mapOf("value" to "x".repeat(200))),
            tick = 1L,
            anchor = AnchorRule.NONE,
        )

        val digest = first(ring).argDigest
        assertEquals(20 + AgentActivityRing.ELLIPSIS.length, digest.length)
        assertTrue(digest.endsWith(AgentActivityRing.ELLIPSIS))
    }

    @Test
    fun `clear empties the ring and still moves the version`() {
        val ring = AgentActivityRing(capacity = 2)
        ring.begin(command("a"), 1L, anchor = AnchorRule.NONE)
        val before = ring.version

        ring.clear()

        assertEquals(0, ring.size)
        assertFalse(before == ring.version)
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun AgentActivityRing.begin(command: AgentCommand, tick: Long, anchor: AnchorRule): Int =
        begin(command, tick, AgentSessionId.LOCAL, anchor)

    private fun command(name: String): AgentCommand = AgentCommand(name)

    private fun names(ring: AgentActivityRing, limit: Int): List<String> {
        val out = ArrayList<String>()
        ring.forEachRecent(limit) { out.add(it.toolName) }
        return out
    }

    /**
     * A copy of the newest entry.
     *
     * Copied out on purpose: the cursor is reused and reading it after the walk is exactly the
     * misuse its KDoc warns about, so the tests may not do it either.
     */
    private fun first(ring: AgentActivityRing): Snapshot {
        var found: Snapshot? = null
        ring.forEachRecent(1) { call ->
            found = Snapshot(
                call.toolName, call.argDigest, call.tick, call.durationNanos,
                call.outcome, call.anchorKind, call.anchorNetId, call.anchorX, call.anchorY,
            )
        }
        return checkNotNull(found) { "the ring is empty" }
    }

    private class Snapshot(
        val toolName: String,
        val argDigest: String,
        val tick: Long,
        val durationNanos: Long,
        val outcome: AgentOutcome,
        val anchorKind: AnchorKind,
        val anchorNetId: Int,
        val anchorX: Float,
        val anchorY: Float,
    )
}
