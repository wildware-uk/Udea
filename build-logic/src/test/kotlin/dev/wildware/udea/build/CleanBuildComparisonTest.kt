package dev.wildware.udea.build

import dev.wildware.udea.build.CleanBuildComparison.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Issue #181: the clean-build gate's verdict has to be **a function of the commit, not of the
 * runner** - and it still has to go red on a build that really got slower.
 *
 * Each test below is one of those two halves stated as an input the gate will actually meet on
 * `ubuntu-latest`: the same work on a slower machine, samples hit by a noisy neighbour, a head
 * that is slower in every sample. The numbers are shaped like the probe runs recorded on the
 * issue (a warm clean build of about twenty seconds), not like round numbers, so a rule that only
 * works on tidy inputs has nowhere to hide.
 */
class CleanBuildComparisonTest {

    private fun samples(vararg pairs: Pair<Side, Long>) =
        pairs.map { (side, ms) -> CleanBuildComparison.Sample(side, ms.milliseconds) }

    private fun abba(base: List<Long>, head: List<Long>) =
        base.zip(head).flatMap { (b, h) -> samples(Side.Base to b, Side.Head to h) }

    @Test
    fun `identical work agrees on a fast runner and on a runner a third slower`() {
        // Probe round 1 measured both of these CPU types under the same label `ubuntu-latest`:
        // EPYC 9V74 legs warmed to ~19 s, the EPYC 7763 leg to ~26 s. An absolute budget reads
        // those as two different commits. The comparison must read them as the same one.
        val fast = abba(base = listOf(19_059, 19_608, 18_446), head = listOf(18_753, 19_174, 18_531))
        val slow = abba(base = listOf(26_514, 27_067, 29_238), head = listOf(26_006, 25_801, 27_234))

        assertIs<CleanBuildComparison.Verdict.Within>(CleanBuildComparison.judge(fast))
        assertIs<CleanBuildComparison.Verdict.Within>(CleanBuildComparison.judge(slow))
    }

    @Test
    fun `head samples slowed by the machine do not fail the head`() {
        // Interference only ever adds time, so the fastest sample is the one closest to what
        // the build costs. A mean of these reads the 31 s draw as a regression of the commit,
        // and a median reads the 24.8 s one the same way.
        val verdict = CleanBuildComparison.judge(
            abba(base = listOf(20_367, 18_991, 18_446), head = listOf(31_020, 24_800, 18_303)),
        )

        assertIs<CleanBuildComparison.Verdict.Within>(verdict)
        assertEquals(18_446.milliseconds, verdict.base)
        assertEquals(18_303.milliseconds, verdict.head)
    }

    @Test
    fun `a head slower in every sample fails, on either runner`() {
        val regressionFactor = 1.25
        val base = listOf(19_059L, 19_608L, 18_446L)
        val head = base.map { (it * regressionFactor).toLong() }

        val onFast = CleanBuildComparison.judge(abba(base, head))
        val onSlow = CleanBuildComparison.judge(abba(base.map { it * 7 / 5 }, head.map { it * 7 / 5 }))

        assertIs<CleanBuildComparison.Verdict.Regressed>(onFast)
        assertIs<CleanBuildComparison.Verdict.Regressed>(onSlow)
    }

    @Test
    fun `the tolerance is inclusive at the line and fails one millisecond past it`() {
        val base = 20_000L
        val atLine = (base * CleanBuildComparison.TOLERANCE).toLong()

        val onLine = CleanBuildComparison.judge(
            abba(base = listOf(base, base, base), head = listOf(atLine, atLine, atLine)),
        )
        val pastLine = CleanBuildComparison.judge(
            abba(base = listOf(base, base, base), head = listOf(atLine + 1, atLine + 1, atLine + 1)),
        )

        assertIs<CleanBuildComparison.Verdict.Within>(onLine)
        assertIs<CleanBuildComparison.Verdict.Regressed>(pastLine)
    }

    @Test
    fun `a faster head passes`() {
        assertIs<CleanBuildComparison.Verdict.Within>(
            CleanBuildComparison.judge(
                abba(base = listOf(24_292, 23_989, 26_439), head = listOf(19_000, 19_500, 19_250)),
            ),
        )
    }

    @Test
    fun `too few samples of either side is refused rather than judged`() {
        // A single sample of a side is exactly the measurement issue #181 is about: one draw
        // from the runner's noise, read as a property of the commit.
        val oneHead = samples(
            Side.Base to 20_000, Side.Base to 20_100, Side.Base to 20_050, Side.Head to 20_000,
        )
        val noBase = samples(Side.Head to 20_000, Side.Head to 20_100, Side.Head to 20_050)

        val few = assertFailsWith<IllegalArgumentException> { CleanBuildComparison.judge(oneHead) }
        assertTrue("head" in few.message.orEmpty(), few.message)
        assertFailsWith<IllegalArgumentException> { CleanBuildComparison.judge(noBase) }
        assertFailsWith<IllegalArgumentException> { CleanBuildComparison.judge(emptyList()) }
    }

    @Test
    fun `samples parse from the file the workflow writes, and a malformed row is named`() {
        val parsed = CleanBuildComparison.parse("base 19059\nhead 18753\n\nhead 19174\n")

        assertEquals(
            samples(Side.Base to 19_059, Side.Head to 18_753, Side.Head to 19_174),
            parsed,
        )
        val badSide = assertFailsWith<IllegalArgumentException> { CleanBuildComparison.parse("heads 1") }
        assertTrue("heads 1" in badSide.message.orEmpty(), badSide.message)
        assertFailsWith<IllegalArgumentException> { CleanBuildComparison.parse("base 19.5") }
        assertFailsWith<IllegalArgumentException> { CleanBuildComparison.parse("base -3") }
    }

    @Test
    fun `the summary states both estimates, the ratio and the verdict`() {
        val verdict = CleanBuildComparison.judge(
            abba(base = listOf(20_000, 20_000, 20_000), head = listOf(25_000, 25_000, 25_000)),
        )
        val text = CleanBuildComparison.summary(verdict)

        assertTrue("20000 ms" in text, text)
        assertTrue("25000 ms" in text, text)
        assertTrue("1.250" in text, text)
        assertTrue("regressed" in text.lowercase(), text)
    }

    private val CleanBuildComparison.Verdict.base: Duration get() = baseEstimate
    private val CleanBuildComparison.Verdict.head: Duration get() = headEstimate
}
