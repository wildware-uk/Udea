package dev.wildware.udea.replay.equality

import dev.wildware.udea.core.Tick
import dev.wildware.udea.replay.ReplayRecording
import dev.wildware.udea.replay.equality.fixture.DriftComponents
import dev.wildware.udea.replay.equality.fixture.DriftFixture
import dev.wildware.udea.replay.equality.fixture.DriftFixtureRecorder
import dev.wildware.udea.replay.equality.fixture.DriftWorld
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate, end to end, on the recording CI actually replays - both ways round.
 *
 * ## What "the job fails" means inside a JUnit test
 *
 * The CI job is `DriftDigestMain` per leg and `ReplayEqualsMain` over the results, and the second
 * one's exit code *is* `ReplayEqualityResult.isEqual`. So this drives the same two classes the job
 * drives, in-process, and asserts the verdict and the rendering. `udeaReplayEqualityProof` runs the
 * identical shape across five real processes and asserts the exit codes; between them the two cover
 * the whole job, and neither is a substitute for the other.
 *
 * ## The expected-output fixture
 *
 * `expected/planted-divergence.txt` is the rendered report, with the operating system and JVM
 * strings replaced by placeholders - those are the two things that legitimately differ between the
 * machine that wrote the fixture and the machine reading it, and pinning them would make this test
 * a test of the runner. Everything else is pinned, including both float values and their raw bits,
 * because the whole claim is that the report names the difference precisely.
 *
 * Regenerate with `-Dupdate.goldens=true`, exactly as `udea-core`'s system-order golden does.
 */
class CrossPlatformDivergenceTest {

    private val recording: ReplayRecording = DriftFixtureRecorder.readCheckedIn()

    @Test
    fun `the checked-in fixture is the one this test claims to replay`() {
        // Named separately so a fixture swap fails as a fixture swap rather than as a mysterious
        // golden-text diff two tests later.
        assertEquals(DriftFixture.PR_TICKS, recording.tickCount)
        assertEquals(Tick.ZERO, recording.firstTick)
        assertEquals(DriftFixture.GAME_ID, recording.header.gameId)
        assertEquals(DriftFixture.SCHEMA.hash, recording.header.identity.inputSchemaHash)
    }

    @Test
    fun `with the plant off, two runs of the fixture are cell-for-cell identical`() {
        // The control. Without it every assertion below would still pass if the comparison
        // reported a divergence unconditionally.
        val dir = createTempDirectory("replay-equality-equal")
        try {
            val a = digest(dir, "leg-a", "a.udeaeq", plantAt = null)
            val b = digest(dir, "leg-b", "b.udeaeq", plantAt = null)

            val result = ReplayEquality.replayEquals(a, b)

            assertTrue(result.isEqual, result.describe())
            assertEquals(DriftFixture.PR_TICKS, result.ticksCompared)
            assertFalse(result.describe().contains("FAILED"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a planted one-ulp divergence fails the comparison and names tick, entity, component and field`() {
        val dir = createTempDirectory("replay-equality-planted")
        try {
            val honest = digest(dir, "expected-leg", "honest.udeaeq", plantAt = null)
            val planted = digest(dir, "planted-leg", "planted.udeaeq", plantAt = DriftFixture.PLANT_TICK)

            val result = ReplayEquality.replayEquals(honest, planted)

            assertFalse(result.isEqual, "a one-ulp difference must not pass a determinism gate")
            assertEquals(DriftFixture.PLANT_TICK, result.tick)
            assertEquals(1, result.divergingCells, "the plant touches exactly one cell at that tick")

            val divergence = result.divergences.single()
            assertFalse(divergence.netId.isNone, "the divergence belongs to a real entity")
            assertEquals("dev.wildware.udea.replay.equality.fixture.Drifter", divergence.componentName)
            assertEquals("x", divergence.fieldName)
            assertEquals(ReplayEquality.HISTORY_TICKS, divergence.history.size)
            assertTrue(
                divergence.history.all { it.agreed },
                "the five ticks before a planted divergence agreed; if they did not, the plant is " +
                    "not where this test thinks it is",
            )

            assertGolden(normalise(result.describe()))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the plant is the smallest change a float can carry, not a visible one`() {
        // Guards the thing that makes this test worth having. A plant of 0.5 would prove the gate
        // catches a difference; the failure class the gate exists for is a last-bit difference, so
        // the plant has to be one, and the two rendered decimals are allowed to be equal.
        val dir = createTempDirectory("replay-equality-ulp")
        try {
            val honest = digest(dir, "a", "honest.udeaeq", plantAt = null)
            val planted = digest(dir, "b", "planted.udeaeq", plantAt = DriftFixture.PLANT_TICK)
            val divergence = ReplayEquality.replayEquals(honest, planted).divergences.single()

            val before = floatOf(divergence.expected)
            val after = floatOf(divergence.actual)
            assertEquals(
                Math.nextUp(before), after,
                "the plant must be exactly one representable step: $before then $after",
            )
            assertEquals(
                1, after.toRawBits() - before.toRawBits(),
                "one ulp is one bit of the significand",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Replays the checked-in fixture into [dir] and reads the digest back. */
    private fun digest(dir: Path, label: String, file: String, plantAt: Tick?): ReplayDigest {
        val out = dir.resolve(file)
        ReplayDigestRecorder.record(
            recording = recording,
            factory = DriftWorld.worlds(plantUlpAt = plantAt),
            registry = DriftComponents.registry(),
            output = out,
            label = label,
            fixture = DriftFixture.PR_FIXTURE,
        )
        return ReplayDigestIo.read(out)
    }

    /** The float out of a rendered `1.25 (0x3fa00000)`, taken from the bits and not the decimal. */
    private fun floatOf(rendered: String): Float {
        val hex = rendered.substringAfter("(0x").substringBefore(')')
        return Float.fromBits(hex.toUInt(HEX).toInt())
    }

    /**
     * Replaces the two things that legitimately differ between machines, and nothing else.
     *
     * **The operating system and the JVM.** Both are recorded in a digest header precisely so a
     * reader can see which two machines disagreed; pinning them would turn this into an assertion
     * about the runner.
     *
     * **A float's decimal rendering, but not its bits.** `Float.toString` changed algorithm in
     * JDK 19 (JDK-4511638) and now prints the shortest decimal that round-trips, so the same
     * `Float` renders as different text on either side of that release. The hexadecimal raw bits
     * beside it are the value itself and are pinned - which is the stronger half anyway, because
     * the difference this gate exists to catch is a difference in the last bit, and two floats one
     * ulp apart can print the same decimal.
     *
     * What is deliberately **not** normalised is the two world hashes and the tick. Those are
     * `WorldHasher` over the same folded bits, and the fixture world uses only exactly-specified
     * arithmetic - `StrictMath`, IEEE-754 add/multiply/divide, a specified narrowing, and an
     * integer RNG - so they are the same number on every conforming JVM by design. If a platform
     * ever disagrees with them, that is a real finding rather than a flaky golden, and it is
     * better to be told loudly than to have normalised it away in advance.
     */
    private fun normalise(rendered: String): String =
        FLOAT_DECIMAL.replace(BRACKETED.replace(rendered, "[<os>; <jvm>]"), "<float> (0x")

    private fun assertGolden(actual: String) {
        val projectDir = System.getProperty("udea.projectDir")
            ?: error("udea.projectDir is not set; the test task must pass it (see build.gradle.kts)")
        val golden = Path.of(projectDir, GOLDEN)
        if (System.getProperty("update.goldens") == "true") {
            Files.createDirectories(golden.parent)
            Files.writeString(golden, actual)
            return
        }
        assertTrue(Files.exists(golden), "$GOLDEN is missing; regenerate with -Dupdate.goldens=true")
        val expected = Files.readString(golden)
        assertEquals(
            expected.trimEnd(), actual.trimEnd(),
            "the rendered cross-platform divergence no longer matches $GOLDEN. If the change is " +
                "intended, regenerate with -Dupdate.goldens=true and read the diff: this text is " +
                "what a CI failure prints, and it is the only thing between a red job and somebody " +
                "guessing.",
        )
        // The four things spec 7 asks the log to name, asserted against the golden itself rather
        // than only against the live object, so a golden regenerated from a weakened renderer is
        // still caught.
        assertContains(expected, "at ${DriftFixture.PLANT_TICK}")
        assertContains(expected, "NetId(")
        assertContains(expected, "dev.wildware.udea.replay.equality.fixture.Drifter.x")
        assertContains(expected, "the preceding ${ReplayEquality.HISTORY_TICKS} tick(s)")
    }

    private companion object {
        const val GOLDEN: String = "src/test/resources/expected/planted-divergence.txt"
        const val HEX: Int = 16
        /**
         * The `[os; jvm]` suffix of a leg line, anchored to the end of the line.
         *
         * Anchored rather than global, because a divergence in the random streams or the id free
         * list renders as `<rng>.word[0]` and `<handles>.free[3].index` - bracketed text in the
         * middle of a line that a greedier pattern would erase, taking the field name with it.
         */
        val BRACKETED = Regex("""\[[^\]]*]$""", RegexOption.MULTILINE)

        /** The decimal a float renders as, immediately before its raw bits. */
        val FLOAT_DECIMAL = Regex("""[-\d][^\s(]* \(0x""")
    }
}
