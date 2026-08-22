package dev.wildware.udea.core.snapshot

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A hash mismatch on its own is useless, so divergence is reported as a tick *and* a field.
 *
 * Spec 7 pairs the [WorldHasher] gate with this for a practical reason: an agent — or a person
 * at 1am — handed "hash 8371… != hash 4402…" has nowhere to go. Handed "tick 141,
 * `NetId(#3@0)` `Movement.position.x`: expected 4.5, got 4.500001" they have the defect. Phase
 * 7 extends the same shape to cross-OS replay equality, so the shape is fixed here.
 */
class DivergenceReportTest {

    @Test
    fun `a single perturbed float is reported with its tick, entity, component and field`() {
        val sim = SnapshotWorld()
        val ids = sim.spawn(ENTITIES)
        repeat(20) { sim.step() }

        val keyframe = sim.service.capture()
        val baseline = runRecording(sim, TICKS)

        sim.service.applyNow(keyframe)

        // One entity, one field, one ulp-ish nudge — the smallest divergence there is.
        val perturbed = checkNotNull(sim.netIds.resolveOrNull(ids[PERTURBED_ENTITY]))
        with(sim.world) { perturbed[Movement] }.position.x += 0.5f

        val replay = runRecording(sim, TICKS)

        val firstBad = DivergenceReport.firstDivergingTick(
            baseline.map { WorldHasher.hash(it.fields) }.toLongArray(),
            replay.map { WorldHasher.hash(it.fields) }.toLongArray(),
            keyframe.tick + 1L,
        )
        assertEquals(keyframe.tick + 1L, firstBad, "the very first re-run tick must differ")

        val report = DivergenceReport.compare(
            tick = checkNotNull(firstBad),
            expected = baseline[0],
            actual = replay[0],
        )

        assertTrue(!report.isIdentical)
        assertEquals(1, report.fields.size, "exactly one field was perturbed: ${report.describe()}")
        val divergence = report.fields.single()
        assertEquals(ids[PERTURBED_ENTITY], divergence.netId)
        assertEquals("Movement", divergence.componentName)
        assertEquals("position.x", divergence.fieldName)
        assertEquals(MovementReplicator.POSITION_X, divergence.fieldIndex)

        val message = report.describe()
        assertTrue(message.contains(firstBad.toString()), message)
        assertTrue(message.contains(ids[PERTURBED_ENTITY].toString()), message)
        assertTrue(message.contains("Movement.position.x"), message)
        assertTrue(message.contains(divergence.expected) && message.contains(divergence.actual), message)
    }

    @Test
    fun `an entity that only one run has is reported by name rather than as a bare mismatch`() {
        val sim = SnapshotWorld()
        sim.spawn(4)
        val expected = sim.service.capture()

        val extra = sim.spawnMovementOnly(x = 3f)
        val actual = sim.service.capture()

        val report = DivergenceReport.compare(Tick(0), expected, actual)

        assertEquals(setOf(extra), report.fields.map { it.netId }.toSet())
        assertTrue(report.fields.all { it.fieldIndex == FieldDiff.PRESENCE })
        assertTrue(report.describe().contains("<presence>"), report.describe())
        assertTrue(report.describe().contains("<no such entity>"), report.describe())
    }

    @Test
    fun `identical runs report no divergence at all`() {
        val sim = SnapshotWorld()
        sim.spawn(4)
        val expected = sim.service.capture()
        val actual = sim.service.capture()

        val report = DivergenceReport.compare(Tick(9), expected, actual)

        assertTrue(report.isIdentical)
        assertEquals("no divergence at t9", report.describe())
    }

    @Test
    fun `a divergence in the random streams alone is reported as such, not as a missing field`() {
        // Fields can agree while the *next* tick is already doomed, because the generators have
        // moved on. A report that only ever looked at fields would say "no field differs" and
        // stop; this one says where else to look.
        val sim = SnapshotWorld()
        sim.spawn(4)
        val expected = sim.service.capture()
        sim.ctx.rng.nextLong(dev.wildware.udea.core.RngStream.Loot)
        val actual = sim.service.capture()

        val report = DivergenceReport.compare(Tick(4), expected, actual)

        assertTrue(report.fields.isEmpty())
        assertTrue(!report.isIdentical, "the snapshot hash must cover the random streams")
        assertTrue(report.describe().contains("random streams"), report.describe())
    }

    @Test
    fun `the report is capped so a wholly diverged world does not print ten thousand lines`() {
        val sim = SnapshotWorld()
        sim.spawn(200)
        val expected = sim.service.capture()
        repeat(30) { sim.step() }
        val actual = sim.service.capture()

        val report = DivergenceReport.compare(Tick(30), expected, actual)

        assertTrue(report.fields.size > DivergenceReport.MAX_REPORTED)
        val lines = report.describe().lines()
        assertTrue(
            lines.size <= DivergenceReport.MAX_REPORTED + 4,
            "the message ran to ${lines.size} lines",
        )
        assertTrue(report.describe().contains("and ${report.fields.size - DivergenceReport.MAX_REPORTED} more"))
    }

    @Test
    fun `comparing hash streams of different lengths is a harness bug and says so`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            DivergenceReport.firstDivergingTick(LongArray(3), LongArray(4), Tick.ZERO)
        }
        assertTrue(failure.message!!.contains("same number of ticks"), failure.message!!)
    }

    /** Steps [ticks] times, keeping a full snapshot of each one so a divergence can be located. */
    private fun runRecording(sim: SnapshotWorld, ticks: Int): List<WorldSnapshot> =
        List(ticks) {
            sim.step()
            sim.service.capture()
        }

    private companion object {
        const val ENTITIES: Int = 12
        const val TICKS: Int = 8
        const val PERTURBED_ENTITY: Int = 5
    }
}
