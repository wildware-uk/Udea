package dev.wildware.udea.core.snapshot

import dev.wildware.udea.core.Tick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The determinism gate itself: run, snapshot, restore, re-run, compare the hash stream.
 *
 * Spec 7 names this as **the actual gate from Phase 0**, over the ASM determinism scanner,
 * because the scanner "catches direct calls but not nondeterminism laundered through Fleks
 * internals, LibGDX math, or cross-JVM float differences". This catches nondeterminism
 * regardless of source, and it works long before input replay exists. It is a Phase 0 exit
 * criterion (spec 6).
 *
 * It is a plain `@Test`, so `gradle check` runs it on every platform in the matrix — which is
 * the point of running it everywhere rather than once: a float difference between two JVMs
 * shows up here as a differing hash and nowhere else.
 */
class SnapshotEquivalenceTest {

    @Test
    fun `two hundred ticks, snapshot at one hundred, restore, re-run, identical hash stream`() {
        val sim = SnapshotWorld()
        sim.spawn(ENTITIES)
        val scratch = sim.service.newSnapshot()

        val baseline = LongArray(TOTAL_TICKS)
        var keyframe: WorldSnapshot? = null
        for (tick in 0 until TOTAL_TICKS) {
            sim.step()
            baseline[tick] = sim.hashNow(scratch)
            if (tick == SNAPSHOT_AT - 1) keyframe = sim.service.capture()
        }

        val restored = checkNotNull(keyframe)
        assertEquals(Tick(SNAPSHOT_AT.toLong()), restored.tick)
        sim.service.applyNow(restored)

        val replay = LongArray(TOTAL_TICKS - SNAPSHOT_AT)
        for (offset in replay.indices) {
            sim.step()
            replay[offset] = sim.hashNow(scratch)
        }

        val expected = baseline.copyOfRange(SNAPSHOT_AT, TOTAL_TICKS)
        val divergence = DivergenceReport.firstDivergingTick(expected, replay, Tick(SNAPSHOT_AT + 1L))
        assertNull(divergence, "the re-run diverged from the original at $divergence")
        assertTrue(baseline.distinct().size > TOTAL_TICKS / 2, "the simulation must actually change")
    }

    @Test
    fun `the gate would fail if the random streams did not rewind`() {
        // The control. Without it, a restore that quietly left the RNG running would still make
        // the test above pass on a simulation that happened not to draw a number, and the gate
        // would be a test that cannot fail.
        val sim = SnapshotWorld()
        sim.spawn(ENTITIES)
        val scratch = sim.service.newSnapshot()
        repeat(20) { sim.step() }

        val keyframe = sim.service.capture()
        val expected = LongArray(10) { sim.step(); sim.hashNow(scratch) }

        sim.service.applyNow(keyframe)
        // Burn draws from the stream the simulation reads, as an un-restored RNG would have.
        repeat(500) { sim.ctx.rng.nextLong(dev.wildware.udea.core.RngStream.Combat) }
        val perturbed = LongArray(10) { sim.step(); sim.hashNow(scratch) }

        assertTrue(
            DivergenceReport.firstDivergingTick(expected, perturbed, Tick(21)) != null,
            "a perturbed random stream must show up as a differing hash",
        )
    }

    @Test
    fun `a restored world hashes identically to the world that was captured`() {
        val sim = SnapshotWorld()
        sim.spawn(ENTITIES)
        repeat(31) { sim.step() }

        val keyframe = sim.service.capture()
        val before = WorldHasher.hash(keyframe)

        repeat(19) { sim.step() }
        sim.service.applyNow(keyframe)

        val after = WorldHasher.hash(sim.service.capture())
        assertEquals(before, after, "capture -> restore -> capture must be the identity")
    }

    private companion object {
        const val ENTITIES: Int = 60
        const val TOTAL_TICKS: Int = 200
        const val SNAPSHOT_AT: Int = 100
    }
}
