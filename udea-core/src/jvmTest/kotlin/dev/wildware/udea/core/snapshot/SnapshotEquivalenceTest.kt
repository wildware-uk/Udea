package dev.wildware.udea.core.snapshot

import dev.wildware.udea.core.Tick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The determinism gate itself: run it twice, and the two runs must agree hash for hash.
 *
 * Spec 7 names this as **the actual gate from Phase 0**, over the ASM determinism scanner,
 * because the scanner "catches direct calls but not nondeterminism laundered through Fleks
 * internals, LibGDX math, or cross-JVM float differences". To earn that it has to catch
 * nondeterminism *regardless of source*, and that takes three different comparisons, because
 * each is blind to what the others see:
 *
 * 1. **Two independent worlds, in lockstep.** Two `SnapshotWorld`s built from the same seed and
 *    given the same operations must produce the same hash on every tick. This is the one that
 *    catches address-derived nondeterminism — a `HashMap<Object, _>` iterated inside a system,
 *    an `identityHashCode` folded into state, a `System.nanoTime` — because the two worlds are
 *    different object graphs and nothing else about them differs. A self-replay cannot see any
 *    of it: it reuses the same `World`, the same entities and the same addresses on both sides,
 *    so whatever the defect is, both halves share it and agree.
 * 2. **A checked-in golden.** One number per pinned tick that every leg of the CI matrix has to
 *    reproduce from a cold JVM. This is the only cross-machine check in the tree that runs a
 *    *simulation*: a float that rounds differently on another JVM, or an iteration order that
 *    differs between two processes rather than within one, moves this number and nothing else.
 * 3. **Snapshot, restore, re-run.** That a restored world resumes the future it was captured
 *    from — the property the ring exists for, and the only one of the three that exercises
 *    restore at all.
 *
 * All three are plain `@Test`s, so `gradle check` runs them on every platform in the matrix.
 *
 * When one fails it reports the differing *fields* through [DivergenceReport] and not just a
 * pair of hashes, because "hash 8371... != hash 4402..." is precisely the useless outcome spec
 * 7 pairs the hash with a field-level report to avoid.
 */
class SnapshotEquivalenceTest {

    @Test
    fun `two independently built worlds agree on every tick`() {
        // Same seed, same spawn, same number of steps -- and nothing else in common. Any input
        // to the simulation that is derived from an object's address rather than from its
        // state differs between these two worlds and shows up here as a differing hash.
        // One shared registry, because `DivergenceReport.compare` refuses stores laid out by
        // two different ones. A registry describes component *types* and holds no simulated
        // state, so sharing it shares nothing this gate is hunting for.
        val registry = TestComponents.registry()
        val first = SnapshotWorld(registry = registry)
        val second = SnapshotWorld(registry = registry)
        first.spawn(ENTITIES)
        second.spawn(ENTITIES)
        val left = first.service.newSnapshot()
        val right = second.service.newSnapshot()

        repeat(TOTAL_TICKS) {
            first.step()
            second.step()
            first.captureInto(left)
            second.captureInto(right)
            if (WorldHasher.hash(left) != WorldHasher.hash(right)) {
                fail(
                    "two independent runs of the same simulation diverged.\n" +
                        DivergenceReport.compare(left.tick, left, right).describe(),
                )
            }
        }
    }

    @Test
    fun `the hash stream matches the golden every machine must reproduce`() {
        // The cross-JVM half of the gate. The two-world comparison above shares a process, so
        // it cannot see a float that rounds differently on another JVM or an ordering that is
        // stable within a process and not between two; this number can, because every leg of
        // the matrix computes it from scratch.
        //
        // Update it deliberately, never to make a red build green: it moves only when the
        // fixture, the RNG, the capture layout or the hash algorithm changes on purpose.
        val sim = SnapshotWorld()
        sim.spawn(ENTITIES)
        val scratch = sim.service.newSnapshot()
        var atOne = 0L
        var atHundred = 0L
        var atTwoHundred = 0L

        for (tick in 1..TOTAL_TICKS) {
            sim.step()
            when (tick) {
                1 -> atOne = hashOf(sim, scratch)
                100 -> atHundred = hashOf(sim, scratch)
                TOTAL_TICKS -> atTwoHundred = hashOf(sim, scratch)
            }
        }

        assertEquals(GOLDEN_AT_1, atOne, "the world hashes differently after one tick")
        assertEquals(GOLDEN_AT_100, atHundred, "the world hashes differently at tick 100")
        assertEquals(GOLDEN_AT_200, atTwoHundred, "the world hashes differently at tick 200")
    }

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
        assertNull(divergence, divergence?.let { describeReplayDivergence(it) }.orEmpty())
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

    /** The whole-snapshot hash: fields, clock, random streams and id allocator. */
    private fun hashOf(sim: SnapshotWorld, scratch: WorldSnapshot): Long {
        sim.captureInto(scratch)
        return WorldHasher.hash(scratch)
    }

    /**
     * Rebuilds both sides of a self-replay divergence at [tick] and names the fields.
     *
     * The streamed comparison keeps two `LongArray`s and nothing else, so by the time it
     * fails both worlds are long gone and all it could say on its own is that two numbers
     * differed. Re-running is cheap -- a couple of hundred ticks -- and the difference between
     * "diverged at t141" and "NetId(7) Vitals.health: expected 61.5, got 61.0" is the
     * difference between a bug report and a bug.
     */
    private fun describeReplayDivergence(tick: Tick): String {
        val straight = SnapshotWorld()
        straight.spawn(ENTITIES)
        repeat(tick.value.toInt()) { straight.step() }

        val rewound = SnapshotWorld()
        rewound.spawn(ENTITIES)
        repeat(SNAPSHOT_AT) { rewound.step() }
        val keyframe = rewound.service.capture()
        repeat(TOTAL_TICKS - SNAPSHOT_AT) { rewound.step() }
        rewound.service.applyNow(keyframe)
        repeat(tick.value.toInt() - SNAPSHOT_AT) { rewound.step() }

        val report = DivergenceReport.compare(
            tick,
            straight.service.capture(),
            rewound.service.capture(),
        )
        return "the re-run diverged from the original at $tick.\n" + report.describe()
    }

    private companion object {
        const val ENTITIES: Int = 60
        const val TOTAL_TICKS: Int = 200
        const val SNAPSHOT_AT: Int = 100

        /** Goldens. Update deliberately, never to make a failing build green. */
        const val GOLDEN_AT_1: Long = -4_752_322_825_843_493_348L
        const val GOLDEN_AT_100: Long = 6_891_921_824_053_448_405L
        const val GOLDEN_AT_200: Long = 357_473_661_550_879_449L
    }
}
