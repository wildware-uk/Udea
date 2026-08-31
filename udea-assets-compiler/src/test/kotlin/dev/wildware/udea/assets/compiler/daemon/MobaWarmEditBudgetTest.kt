package dev.wildware.udea.assets.compiler.daemon

import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.SpriteSheet
import dev.wildware.udea.assets.compiler.AssetCompiler
import dev.wildware.udea.assets.compiler.TestPaths
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
 * - **the real tree.** `moba/assets` is the game's whole authored corpus - its characters, its
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
 * Inside: the file write, `reload`, one script compile, the scan, the reference walk, `PackedValues`
 * over the whole graph, the structural-change check, the diff, `commit`, and reading the new
 * `SpriteSheet.scale` back out.
 *
 * Outside, and stated rather than hidden: the last leg into the running process. `AssetHotReload`
 * takes the same `GraphDelta` this produces and swaps it into the live `AssetRegistry` at a barrier
 * inside `Simulation.step` - one tick, 16ms at 60Hz - and `Phase2ExitTest` gates *that* leg end to
 * end over HTTP at one second. So the number here is the part this repository had never measured
 * over the corpus a person actually edits.
 *
 * The corpus is **copied** into `build/tmp/scratch` first. A benchmark that edited `moba/assets`
 * would leave the game's own asset tree modified when it failed halfway.
 *
 * ## Why it is a range and a median, not one run
 *
 * A single sample of a JIT-warming, disk-touching operation is a number, not a measurement. Six
 * edits are made, the first is discarded as the warm-up (it pays for classloading the scripting
 * host - about two seconds on this machine, which is start-up and not the editing loop), and the
 * **maximum** of the rest is what the budget is asserted against. Median would let one edit in
 * three miss the budget and still be reported green.
 */
class MobaWarmEditBudgetTest {

    /** Spec 6 Phase 2: an asset edit is observed in under three seconds. */
    private val budgetMs = 3_000L

    /** Six edits, five counted. */
    private val iterations = 6

    /** The sheet whose authored scale every iteration moves. */
    private val probed = AssetId("character/orc_idle_sheet")

    @Test
    fun `a warm edit of the real moba corpus is observed in under three seconds`() {
        val root = copyCorpus()
        val daemon = AssetDaemon(
            repoRoot = TestPaths.repoRoot,
            assetRoot = root,
            scriptClasspath = TestPaths.compilerClasspath,
            cacheDirectory = TestPaths.scratch("daemon/moba-warm-edit/cache"),
        )

        val started = daemon.start()
        assertTrue(
            started.ok,
            "the game's own corpus must be valid before it is timed:\n" +
                started.diagnostics.joinToString("\n") { "${it.ruleId} ${it.message}" },
        )
        // Non-empty, not an exact count. The budget is about the *cost of walking the real
        // corpus*, and that cost tracks whatever the corpus currently holds - so an exact number
        // here would be a second thing to edit every time somebody authors an asset, and would
        // fail for a reason that is not a regression. It was 127 when this was written and 147
        // once the shop's twenty items landed.
        assertTrue(
            daemon.ids.isNotEmpty(),
            "the daemon loaded no assets, so this would be timing an empty walk",
        )

        val script = root.resolve("character/orc.udea.kts")
        val original = script.readText()
        val samples = mutableListOf<Long>()

        repeat(iterations) { iteration ->
            // A different number every time, so the compiled-script jar cache can never answer
            // the edit and turn this into a measurement of a hash lookup.
            val scale = "1.${70 + iteration}F"
            val edited = original.replace("val orcScale = 1.88F", "val orcScale = $scale")
            assertTrue(edited != original, "the benchmark's own edit did not change the file")

            val began = System.nanoTime()
            script.writeText(edited)
            val outcome = daemon.reload(listOf(script))
            val applied = assertIs<ReloadOutcome.Applied>(
                outcome,
                "a scale change is an ordinary value change, not a shape change: $outcome",
            )
            daemon.commit()
            val observed = assertIs<SpriteSheet>(daemon.value(probed.value))
            val elapsedMs = (System.nanoTime() - began) / 1_000_000

            assertEquals(scale.removeSuffix("F").toFloat(), observed.scale)
            assertTrue(
                probed in applied.changedIds,
                "the delta an `AssetHotReload` would push does not name $probed: ${applied.changedIds}",
            )
            // Five sheets share `orcScale`, so the delta is the five of them and nothing else.
            assertEquals(5, applied.changedIds.size, "changed: ${applied.changedIds}")

            if (iteration > 0) samples += elapsedMs
        }

        println(
            "moba warm edit -> observed: max ${samples.max()}ms, median ${samples.sorted()[samples.size / 2]}ms, " +
                "min ${samples.min()}ms over ${samples.size} samples $samples " +
                "(budget ${budgetMs}ms, corpus ${daemon.ids.size} assets)",
        )
        assertTrue(
            samples.max() <= budgetMs,
            "spec 6 Phase 2 gates an asset edit at ${budgetMs}ms; the slowest of $samples missed it",
        )
    }

    /**
     * `moba/assets`, copied whole into scratch.
     *
     * Whole and not scripts-only: the daemon's reference walk is over ids, but a corpus missing its
     * PNGs would be a different tree from the one this claims to measure the day a validator that
     * reads files is added to the reload path.
     */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun copyCorpus(): Path {
        val source = TestPaths.repoRoot.resolve("moba/assets")
        assertTrue(
            source.exists() && AssetCompiler.scriptsUnder(source).isNotEmpty(),
            "this benchmark is about the game's one asset root; $source is not it",
        )
        val target = TestPaths.scratch("daemon/moba-warm-edit/assets")
        for (file in source.walk().filter { it.isRegularFile() }) {
            val destination = target.resolve(file.relativeTo(source).toString())
            destination.parent?.createDirectories()
            file.copyTo(destination, overwrite = true)
        }
        return target
    }
}
