package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.Health
import dev.wildware.udea.core.loop.RewindFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Time travel through the bridge, asserted on tick deltas and never on sleeping.
 *
 * The defect these tests exist to keep out is at `DebugBridge.kt:40`: a harness that waited on
 * *render* frames made `step(n)` approximate, and every determinism measurement then silently
 * compared runs of different lengths. So every assertion here is `tickAfter - tickBefore`, a
 * number the tool itself returns.
 */
class TimeToolsetTest {

    @Test
    fun `pause then step advances the tick by exactly the number asked for`() {
        val harness = ToolsetHarness()

        harness.ok("time.pause")
        val before = harness.host.tick.value
        val stepped = harness.ok("time.step", "ticks" to "200")

        assertTrue(stepped.contains("\"tickBefore\":$before"), stepped)
        assertTrue(stepped.contains("\"tickAfter\":${before + 200}"), stepped)
        assertTrue(stepped.contains("\"ticksStepped\":200"), stepped)
        assertEquals(before + 200, harness.host.tick.value)
        // `step` leaves the loop paused, which is what makes the next assertion reproducible.
        assertTrue(stepped.contains("\"paused\":true"), stepped)
    }

    @Test
    fun `a paused game still drains, so resume always arrives`() {
        // The wedge this prevents: with the loop paused nothing steps, so nothing drains the
        // barrier, so the command that would unpause it can never be applied.
        val harness = ToolsetHarness()
        harness.ok("time.pause")
        assertTrue(harness.host.time.paused)

        val resumed = harness.ok("time.resume")

        assertTrue(resumed.contains("\"paused\":false"), resumed)
        assertEquals(false, harness.host.time.paused)
    }

    @Test
    fun `step refuses a negative count and names the tool that goes backwards`() {
        val harness = ToolsetHarness()

        val error = harness.failure("time.step", "ticks" to "-3")

        assertEquals(AgentErrorKind.BAD_ARGUMENT, error.kind)
        assertTrue(error.message.contains("time.rewind"), error.message)
    }

    @Test
    fun `set_time_scale changes the scale and never the fixed step`() {
        val harness = ToolsetHarness()
        val dt = harness.host.ctx.clock.dt

        val result = harness.ok("time.set_time_scale", "scale" to "0.25")

        assertTrue(result.contains("\"timeScale\":0.25"), result)
        assertEquals(0.25f, harness.host.loop.timeScale)
        assertEquals(dt, harness.host.ctx.clock.dt, "the 1/60s step is invariant")
    }

    @Test
    fun `set_time_scale refuses an absurd scale rather than clamping it`() {
        val harness = ToolsetHarness()

        val error = harness.failure("time.set_time_scale", "scale" to "1e9")

        assertEquals(AgentErrorKind.BAD_ARGUMENT, error.kind)
        assertEquals(1f, harness.host.loop.timeScale, "a refused scale must not be half-applied")
    }

    @Test
    fun `snapshot then run then rewind restores the world and the digest`() {
        val harness = ToolsetHarness()
        val netId = harness.place(health = 100f)
        val entity = assertNotNull(harness.netIds.resolveOrNull(netId))
        harness.ok("time.pause")
        harness.ok("time.step", "ticks" to "5")

        val captured = harness.ok("time.snapshot")
        val capturedTick = harness.host.tick.value
        assertTrue(captured.contains("\"tick\":$capturedTick"), captured)
        // The document as it stood at the captured tick, before anything moved.
        harness.digest.publish()
        val digestAtCapture = harness.bridge.snapshot()

        harness.ok(
            "world.set_component_field",
            "id" to netId.raw.toString(),
            "component" to "Health",
            "field" to "current",
            "value" to "7",
        )
        harness.ok("time.step", "ticks" to "100")
        with(harness.world) { assertEquals(7f, entity[Health].current) }

        val rewound = harness.ok("time.rewind", "ticks" to "100")

        assertTrue(rewound.contains("\"tick\":$capturedTick"), rewound)
        assertTrue(rewound.contains("\"assetGraphChangedSince\":false"), rewound)
        assertEquals(capturedTick, harness.host.tick.value)
        val restored = assertNotNull(harness.netIds.resolveOrNull(netId))
        with(harness.world) { assertEquals(100f, restored[Health].current, "the field must rewind") }

        harness.digest.publish()
        val digestAfterRewind = harness.bridge.snapshot()
        assertEquals(
            simulationHalfOf(digestAtCapture),
            simulationHalfOf(digestAfterRewind),
            "the Tier-0 document must match the one captured at the same tick, field for field",
        )
    }

    /**
     * The part of the document a rewind must reproduce exactly.
     *
     * `frame` is excluded because it is *supposed* to differ: it counts host iterations and
     * increases for the life of the process - the bridge reads a decreasing frame as "a
     * different process is answering on this port". `completedCommandId` is excluded for the
     * same reason: it is a high-water mark over commands, and the rewind itself was one.
     * Everything between them describes the simulation, and all of it must come back.
     */
    private fun simulationHalfOf(document: String): String =
        document.substringAfter("\"simFrame\"").substringBefore("\"completedCommandId\"")

    @Test
    fun `rewinding further than the ring holds is a typed refusal that leaves the game running`() {
        val harness = ToolsetHarness()
        harness.ok("time.step", "ticks" to "10")
        harness.ok("time.resume")

        val error = harness.failure("time.rewind", "ticks" to "10000")

        assertEquals(RewindFailure.TickOutOfRing.code, error.kind.id)
        // Probing the ring must not halt a running game as a side effect of asking.
        assertEquals(false, harness.host.time.paused)
    }

    @Test
    fun `a game with no snapshot ring answers no_snapshot_ring rather than throwing`() {
        val harness = ToolsetHarness(withSnapshotRing = false)

        val snapshotError = harness.failure("time.snapshot")
        val rewindError = harness.failure("time.rewind", "ticks" to "1")

        assertEquals(TimeToolset.NO_SNAPSHOT_RING, snapshotError.kind)
        assertEquals(RewindFailure.NoSnapshotRing.code, rewindError.kind.id)
        assertEquals("""{"count":0,"totalBytes":0,"snapshots":[]}""", harness.ok("time.list_snapshots"))
    }

    @Test
    fun `list_snapshots reports ids, ticks and sizes and the ring stays bounded`() {
        val harness = ToolsetHarness()

        harness.ok("time.fast_forward", "ticks" to "10000")
        val listed = harness.ok("time.list_snapshots")

        val count = Regex("\"count\":(\\d+)").find(listed)?.groupValues?.get(1)?.toInt()
        assertNotNull(count)
        assertTrue(count > 0, "10 000 ticks must have filled the ring: $listed")
        assertTrue(listed.contains("\"kind\":\""), listed)
        assertTrue(listed.contains("\"sizeBytes\":"), listed)
        assertTrue(listed.contains("\"totalBytes\":"), listed)
        // Bounded: the ring's own retention windows cap it, so 10 000 ticks does not mean
        // 10 000 snapshots.
        assertTrue(count < 10_000, "the ring is not bounded: $listed")
    }

    @Test
    fun `fast_forward runs the ticks and leaves the loop paused`() {
        val harness = ToolsetHarness()
        val before = harness.host.tick.value

        val result = harness.ok("time.fast_forward", "ticks" to "500")

        assertTrue(result.contains("\"ticksStepped\":500"), result)
        assertEquals(before + 500, harness.host.tick.value)
        assertTrue(result.contains("\"paused\":true"), result)
    }
}
