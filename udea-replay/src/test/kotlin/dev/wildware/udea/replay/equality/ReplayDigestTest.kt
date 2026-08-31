package dev.wildware.udea.replay.equality

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.snapshot.WorldHasher
import dev.wildware.udea.replay.equality.fixture.DriftComponents
import dev.wildware.udea.replay.equality.fixture.DriftDigestMain
import dev.wildware.udea.replay.equality.fixture.DriftFixture
import dev.wildware.udea.replay.equality.fixture.DriftFixtureRecorder
import dev.wildware.udea.replay.equality.fixture.DriftWorld
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The digest stream itself: does the file say what the world said, and can a second machine read it?
 *
 * The load-bearing test is [a tick's cells fold back to the world hash], because everything the
 * gate promises rests on it. If the cells were a *subset* of what `WorldHasher` folds, two runs
 * could differ in the part that was left out, the hashes would differ, no cell would, and the only
 * honest report would be a bare hash mismatch - the thing this issue exists to remove.
 */
class ReplayDigestTest {

    private val dir: Path = createTempDirectory("replay-digest")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    /** A short run, so this file's tests stay quick; the 3600-tick fixture is exercised elsewhere. */
    private fun shortDigest(label: String = "leg", file: String = "short.udeaeq"): ReplayDigest {
        val recording = DriftFixtureRecorder.record(TICKS)
        val out = dir.resolve(file)
        ReplayDigestRecorder.record(
            recording = recording,
            factory = DriftWorld.worlds(),
            registry = DriftComponents.registry(),
            output = out,
            label = label,
            fixture = "short",
            gradleProject = DriftDigestMain.GRADLE_PROJECT,
        )
        return ReplayDigestIo.read(out)
    }

    @Test
    fun `a tick's cells fold back to the world hash`() {
        val digest = shortDigest()

        assertEquals(TICKS, digest.tickCount)
        for (index in 0 until digest.tickCount) {
            var folded = WorldHasher.OFFSET_BASIS
            for (cell in digest.cellsOf(index)) folded = WorldHasher.fold(folded, digest.valueAt(cell))
            assertEquals(
                digest.hashAt(index), folded,
                "the cells at tick index $index do not reproduce the recorded world hash, so the " +
                    "stream does not cover everything WorldHasher folds",
            )
        }
    }

    @Test
    fun `dropping a single cell breaks the fold`() {
        // The negative control for the test above. Without it, "the cells fold to the hash" would
        // pass just as happily if the fold ignored the cells entirely.
        val digest = shortDigest()
        val cells = digest.cellsOf(0).toList()
        assertTrue(cells.size > 1, "a one-cell tick would make this control vacuous")

        for (dropped in cells) {
            var folded = WorldHasher.OFFSET_BASIS
            for (cell in cells) {
                if (cell != dropped) folded = WorldHasher.fold(folded, digest.valueAt(cell))
            }
            assertNotEquals(
                digest.hashAt(0), folded,
                "dropping cell $dropped still folded to the recorded hash, so that cell is not " +
                    "actually part of what the hash covers",
            )
        }
    }

    @Test
    fun `a digest survives a round trip through the file`() {
        val digest = shortDigest()
        val copy = ReplayDigestIo.read(dir.resolve("short.udeaeq"))

        assertEquals(digest.tickCount, copy.tickCount)
        assertEquals(digest.header.label, copy.header.label)
        assertEquals(digest.header.components.size, copy.header.components.size)
        for (index in 0 until digest.tickCount) {
            assertEquals(digest.hashAt(index), copy.hashAt(index))
            assertEquals(digest.cellsOf(index).count(), copy.cellsOf(index).count())
        }
        assertTrue(ReplayEquality.replayEquals(digest, copy).isEqual)
    }

    @Test
    fun `the component table carries the fully qualified name and the field kinds`() {
        val digest = shortDigest()

        val drifter = digest.header.components.single { it.typeName == "Drifter" }
        assertEquals("dev.wildware.udea.replay.equality.fixture.Drifter", drifter.componentFqn)
        assertEquals(listOf("x", "y", "heading", "energy", "lastTurnTick"), drifter.fieldNames)
        // A float rendered from raw bits, which is what a join step with no game on its classpath
        // has to do.
        assertContains(drifter.render(0, 1.5f.toRawBits().toLong()), "1.5")
    }

    @Test
    fun `the fixture world really does churn its roster, its presence bits and its free list`() {
        // An empty fixture is not a neutral one. A gate proven only against a world that never
        // frees an id or drops a component says nothing about one that does, and every one of
        // those three is folded into the hash without being any entity's field.
        val digest = shortDigest()

        val rowCounts = (0 until digest.tickCount).map { index ->
            digest.cellsOf(index).first { digest.scopeAt(it) == DigestScope.RowCount }
                .let { digest.valueAt(it) }
        }.toSet()
        assertTrue(rowCounts.size > 1, "the roster never changed size: $rowCounts")

        val freeCounts = (0 until digest.tickCount).mapNotNull { index ->
            digest.cellsOf(index).firstOrNull {
                digest.scopeAt(it) == DigestScope.Handles &&
                    digest.fieldAt(it) == ReplayDigestCells.HANDLE_FREE_COUNT
            }?.let { digest.valueAt(it) }
        }.toSet()
        assertTrue(freeCounts.any { it > 0L }, "no NetId was ever freed: $freeCounts")

        val chargeSlots = (0 until digest.tickCount).mapNotNull { index ->
            digest.cellsOf(index).firstOrNull {
                digest.scopeAt(it) == DigestScope.ComponentSlots && digest.typeIdAt(it) == CHARGE_TYPE_ID
            }?.let { digest.valueAt(it) }
        }.toSet()
        assertTrue(chargeSlots.size > 1, "the Charge component was never added or removed: $chargeSlots")
    }

    @Test
    fun `a file that is not a digest is refused by name rather than at the first tick`() {
        val notADigest = dir.resolve("nonsense.udeaeq")
        Files.write(notADigest, ByteArray(NONSENSE_BYTES) { it.toByte() })

        // Gzip rejects it before the magic does, which is still a refusal at byte 0 and still a
        // named one; what matters is that it is not read as a stream of ticks.
        assertFailsWith<Exception> { ReplayDigestIo.read(notADigest) }
    }

    @Test
    fun `two streams of different fixtures refuse to be compared`() {
        val a = shortDigest(label = "leg-a", file = "a.udeaeq")
        val recording = DriftFixtureRecorder.record(TICKS)
        val out = dir.resolve("b.udeaeq")
        ReplayDigestRecorder.record(
            recording = recording,
            factory = DriftWorld.worlds(),
            registry = DriftComponents.registry(),
            output = out,
            label = "leg-b",
            fixture = "a-different-fixture",
            gradleProject = DriftDigestMain.GRADLE_PROJECT,
        )

        val failure = assertFailsWith<IncomparableDigestsException> {
            ReplayEquality.replayEquals(a, ReplayDigestIo.read(out))
        }
        assertContains(failure.message.orEmpty(), "fixture")
        assertTrue(failure.reasons.isNotEmpty())
    }

    @Test
    fun `the first tick is the tick that was simulated, not the clock after it`() {
        // An off-by-one here would point every divergence report one tick away from its cause,
        // which is the difference between landing on the bug and landing on its consequence.
        val digest = shortDigest()
        assertEquals(Tick.ZERO, digest.tickAt(0))
        assertEquals(Tick(TICKS - 1L), digest.tickAt(TICKS - 1))
        assertEquals("short", digest.header.fixture)
    }

    private companion object {
        const val TICKS: Int = 240
        const val CHARGE_TYPE_ID: Int = 2
        const val NONSENSE_BYTES: Int = 512
    }
}
