package dev.wildware.udea.assets.compiler.daemon

import dev.wildware.udea.diagnostics.bench.LatencyBudget
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Spec 6, Phase 2: **an asset edit reaches the graph the running game reads in under three
 * seconds**, over the game's real corpus.
 *
 * ## Why this exists beside `DaemonLatencyBudgetTest`
 *
 * That test gates a warm `assets.validate` at 300ms over a **three-script synthetic** corpus, and
 * it measures the *decision* half only - the daemon says "this compiles" and touches nothing. Two
 * things spec 6's Phase 2 number is about are missing from it:
 *
 * - **the real tree.** `moba/game/assets` is the game's whole authored corpus - its characters, its
 *   abilities, its effects, its controls, its shop, and a level with twenty-seven entities in it.
 *   Every reload re-walks that graph, re-packs every value and diffs the result against the
 *   last-good one, and none of that appears in a three-script fixture. What the corpus is *made
 *   of* is the point here; how many files it happens to be is not, and a count in this paragraph
 *   would go stale on the next asset somebody authored.
 * - **the whole edit-to-observe path.** Validate is the first of four steps. What an agent waits
 *   for is: the file is written, the daemon recompiles it, the candidate graph packs, the delta is
 *   decided, and the *new typed value* is the one the graph serves.
 *
 * ## What is inside the measurement and what is not
 *
 * Inside, and nothing else: [MobaWarmEdit.edit] - the file write, `reload`, one script compile, the
 * scan, the reference walk, `PackedValues` over the whole graph, the structural-change check, the
 * diff, `commit`, and reading the new `SpriteSheet.scale` back out.
 *
 * Outside, and stated rather than hidden: the last leg into the running process. `AssetHotReload`
 * takes the same `GraphDelta` this produces and swaps it into the live `AssetRegistry` at a barrier
 * inside `Simulation.step` - one tick, 16ms at 60Hz - and `Phase2ExitTest` gates *that* leg end to
 * end over HTTP at one second. Also outside: everything [MobaWarmEdit.verify] asserts, which
 * `MobaWarmEditTest` runs on `check`.
 *
 * ## Why it is the maximum, and why that survived issue #182
 *
 * Six edits are made, the first is discarded as the warm-up (it pays for classloading the
 * scripting host - about two seconds on this machine, which is start-up and not the editing loop),
 * and the **maximum** of the rest is what the threshold is asserted against.
 *
 * Issue #182 asked whether that should become a median, because #175 made exactly that change to
 * `DaemonLatencyBudgetTest`'s reload gate - where the maximum of five was "the worst scheduling
 * hiccup in a two-minute window rather than anything about the daemon", measured at 172ms alone
 * and 528ms beside a full build. It should not, for two reasons that are specific to this gate:
 *
 * - **the tail here is not noise.** Measured on this repository's box at a load average of 1.17,
 *   the five counted samples were `[147, 131, 132, 127, 142]` - a 16% spread between fastest and
 *   slowest, not the two-to-four times that made the maximum meaningless there.
 * - **the criterion is a deadline, not a throughput.** "An asset edit is observed in under three
 *   seconds" is a claim about every edit a person makes. A median would let one edit in three miss
 *   the deadline and still report green, which is a different and weaker claim than the one spec 6
 *   states.
 *
 * A median would also have made the gate strictly easier to pass, and this repository does not buy
 * that without a demonstration that it still catches the regression it is for.
 *
 * ## Two numbers, one assertion (issue #184)
 *
 * [CONTRACT_MS] is spec 6's promise to a person editing assets, and it is not this file's to move.
 * [REGRESSION_MS] is what the gate fails at. They were one constant until #184, and the constant
 * did the second job badly: measured alone, a warm edit's slowest sample is a few hundred
 * milliseconds, so three seconds let through a regression of more than tenfold.
 *
 * That is not hypothetical. Making every reload recompile the whole corpus with the compiled-script
 * jar cache switched off - a daemon that has lost both of the things that make the warm path warm -
 * measured a slowest sample of 2 649, 2 948 and 2 976ms in three solo runs on this repository's
 * 24-processor box at one-minute load averages of 6.55 to 7.13, and all three were green against
 * three seconds.
 *
 * There is deliberately no second assertion at [CONTRACT_MS]. [REGRESSION_MS] is below it, so any
 * run that passes the threshold has passed the deadline, and an assertion that can only fail when
 * the one before it already has is an assertion that cannot fail. The failure message says which
 * of the two a red run missed. That reasoning holds only while the threshold is the lower number,
 * which is why the remedy below rules out raising it to the deadline or past it.
 *
 * The threshold is 1 500ms because it has to sit between two measurements, both in
 * `docs/budgets.md`: the slowest warm edit CI has recorded on either runner image, and the fastest
 * sample of the regression above on this repository's own box.
 *
 * ## Where it is measured
 *
 * On `udeaLatencyBudgets`, through `:udea-assets-compiler:udeaWarmEditBudget`, and no longer inside
 * `check` (issue #182). [LatencyBudget.measuredBy] refuses to let it run anywhere else.
 *
 * If it fails, the remedy is the daemon's incremental scope - re-walk less of the graph - never a
 * wider threshold, and never a threshold at or above the deadline.
 */
class MobaWarmEditBudgetTest {

    @Test
    fun `a warm edit of the real moba corpus is observed well inside the three-second deadline`() {
        LatencyBudget.measuredBy(TASK)

        val harness = MobaWarmEdit("moba-warm-edit")
        harness.start()

        val samples = mutableListOf<Long>()
        repeat(ITERATIONS) { iteration ->
            val began = System.nanoTime()
            val edit = harness.edit(iteration)
            val elapsedMs = (System.nanoTime() - began) / 1_000_000

            harness.verify(edit)
            if (iteration > 0) samples += elapsedMs
        }

        val slowest = samples.max()
        println(
            "moba warm edit -> observed: max ${slowest}ms, " +
                "median ${samples.sorted()[samples.size / 2]}ms, min ${samples.min()}ms over " +
                "${samples.size} samples $samples " +
                "(threshold ${REGRESSION_MS}ms, deadline ${CONTRACT_MS}ms, " +
                "corpus ${harness.assetCount} assets)",
        )
        assertTrue(
            slowest <= REGRESSION_MS,
            "a warm edit of moba's corpus is gated at ${REGRESSION_MS}ms to catch a regression " +
                "long before it reaches spec 6's ${CONTRACT_MS}ms deadline; the slowest of " +
                "$samples missed it" +
                (if (slowest > CONTRACT_MS) ", and missed the deadline too. " else ". ") +
                LatencyBudget.contentionNote(TASK),
        )
    }

    private companion object {

        /** The task that measures this, and the one to re-run alone before believing a red. */
        const val TASK = ":udea-assets-compiler:udeaWarmEditBudget"

        /** Spec 6 Phase 2: an asset edit is observed in under three seconds. A product promise. */
        const val CONTRACT_MS = 3_000L

        /**
         * What this gate fails at: a regression threshold inside [CONTRACT_MS] (issue #184).
         * The class KDoc says where the number comes from.
         */
        const val REGRESSION_MS = 1_500L

        /** Six edits, five counted. */
        const val ITERATIONS = 6
    }
}
