package dev.wildware.udea.build

import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The verdict behind the `clean-build-budget` CI job: is this commit's clean build slower than
 * the build it branched from, **measured on the same runner in the same job**.
 *
 * ## Why a comparison and not an absolute number (issue #181)
 *
 * The job used to time one `./gradlew clean udeaAssemble` against 90 000 ms. Fifty recorded runs of
 * it measured 60 405 to 102 453 ms, and five of them on work that did not differ went three red and
 * two green. Two probe runs on the issue branch found why, and neither cause is something an
 * estimator over a single job's samples can remove:
 *
 * - **Most of the number was not compiling.** The first clean build in a fresh job measured 84 to
 *   97 s; the same command repeated in the same job measured 18 to 26 s. Three quarters of the
 *   old measurement was a cold Gradle daemon, a cold Kotlin daemon and JIT warm-up.
 * - **`ubuntu-latest` is two machines.** The same label handed out AMD EPYC 9V74 and EPYC 7763
 *   runners, and the 7763 was about a third slower even warm. A number compared against a
 *   constant is a number about which of the two the job drew.
 *
 * A base build in the same job draws the same machine, so the machine divides out. What is left
 * is the thing a reader of CI can act on: this commit made a clean build slower, or it did not.
 * The 90 000 ms constant was not widened to absorb the noise - the absolute gate is gone, and the
 * reason is recorded in `docs/budgets.md` next to the number it used to enforce.
 *
 * ## Why the fastest sample
 *
 * Every source of error in a wall-clock build time only ever adds time: a neighbour on the host,
 * a GC pause, a page cache miss. None of them makes a build faster than the work it does. So the
 * fastest of several samples is the one nearest the true cost. A median still moves when most of a
 * handful draw badly, and a mean moves with any one of them. `udeaBenchCharacterMover` made the same change for the same
 * reason in issue #175.
 *
 * `internal`: only [UdeaCleanBuildVerdictTask] and this module's tests read it.
 */
internal object CleanBuildComparison {

    /**
     * How much slower than its base a head's clean build may be before the gate fails, as a
     * ratio of the two fastest samples.
     */
    const val TOLERANCE: Double = 1.10

    /**
     * The fewest samples of each side [judge] will read.
     *
     * One sample is a single draw from the runner's noise - the measurement issue #181 replaced -
     * and the fastest of two still lets one bad draw decide half the pair.
     */
    const val MIN_SAMPLES_PER_SIDE: Int = 3

    /** Which checkout a sample timed. */
    enum class Side(val label: String) {
        /** The commit this one is compared against, built in a second checkout. */
        Base("base"),

        /** The commit under test. */
        Head("head"),
    }

    /** One timed `./gradlew clean udeaAssemble --no-build-cache` of one checkout. */
    data class Sample(val side: Side, val elapsed: Duration)

    /** The outcome of comparing the two sides' fastest samples. */
    sealed interface Verdict {
        /** The fastest base sample. */
        val baseEstimate: Duration

        /** The fastest head sample. */
        val headEstimate: Duration

        /** [headEstimate] over [baseEstimate]; above 1 means the head is slower. */
        val ratio: Double get() = headEstimate / baseEstimate

        /** The head is no more than [TOLERANCE] times slower than its base. */
        data class Within(
            override val baseEstimate: Duration,
            override val headEstimate: Duration,
        ) : Verdict

        /** The head is more than [TOLERANCE] times slower than its base. */
        data class Regressed(
            override val baseEstimate: Duration,
            override val headEstimate: Duration,
        ) : Verdict
    }

    /**
     * The samples file the workflow appends to: one `<side> <milliseconds>` row per build, blank
     * lines ignored.
     *
     * A row that is not exactly that fails, naming the row. A parser that skipped what it could
     * not read would let a broken timing loop hand the gate fewer samples than it thinks it has.
     */
    fun parse(text: String): List<Sample> = text.lines()
        .filter { it.isNotBlank() }
        .map { row ->
            val match = requireNotNull(ROW.matchEntire(row.trim())) {
                "'$row' is not a clean-build sample; expected '<base|head> <whole milliseconds>'"
            }
            val side = Side.values().single { it.label == match.groupValues[1] }
            Sample(side, match.groupValues[2].toLong().milliseconds)
        }

    /** Compares the fastest head sample against the fastest base sample. */
    fun judge(samples: List<Sample>): Verdict {
        val base = fastest(samples, Side.Base)
        val head = fastest(samples, Side.Head)
        return if (head / base <= TOLERANCE) Verdict.Within(base, head) else Verdict.Regressed(base, head)
    }

    /** The markdown the job appends to its step summary, pass or fail. */
    fun summary(verdict: Verdict): String {
        val outcome = when (verdict) {
            is Verdict.Within -> "within tolerance"
            is Verdict.Regressed -> "**regressed**"
        }
        return buildString {
            appendLine("| Measure | Value |")
            appendLine("| --- | --- |")
            appendLine("| Base, fastest sample | ${verdict.baseEstimate.inWholeMilliseconds} ms |")
            appendLine("| Head, fastest sample | ${verdict.headEstimate.inWholeMilliseconds} ms |")
            appendLine("| Head / base | ${ratio(verdict.ratio)} |")
            appendLine("| Tolerance | ${ratio(TOLERANCE)} |")
            appendLine("| Verdict | $outcome |")
        }
    }

    private fun fastest(samples: List<Sample>, side: Side): Duration {
        val times = samples.filter { it.side == side }.map { it.elapsed }
        require(times.size >= MIN_SAMPLES_PER_SIDE) {
            "${times.size} ${side.label} sample(s); the comparison needs at least " +
                "$MIN_SAMPLES_PER_SIDE of each side, because fewer is one draw from the runner's " +
                "noise read as a property of the commit"
        }
        return times.min()
    }

    /** Three decimals, with a point on every locale, so the summary reads the same on any runner. */
    private fun ratio(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    private val ROW = Regex("""^(base|head) (\d+)$""")
}
