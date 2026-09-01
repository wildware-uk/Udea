package dev.wildware.udea.assets.compiler.daemon

import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.SpriteSheet
import dev.wildware.udea.assets.compiler.AssetCompiler
import dev.wildware.udea.assets.compiler.TestPaths
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
 * One warm edit of `moba`'s real asset corpus, driven end to end: the file is written, the daemon
 * recompiles it, the candidate graph packs, the delta is decided, and the new typed value is the
 * one the graph serves.
 *
 * ## Why the harness is separate from either test (issue #182)
 *
 * This edit is two claims that need different homes. *"The delta an `AssetHotReload` would push
 * names the five sheets that share `orcScale`, and the graph then serves the new scale"* is a
 * correctness claim: it gives the same answer on a busy machine as on an idle one, so it belongs
 * on `check` where every build runs it. *"It happens in under three seconds"* is a stopwatch, and
 * a stopwatch read inside a parallel build reads the build - so it belongs on
 * `udeaLatencyBudgets`, measured by a CI job that has the runner to itself.
 *
 * That is the same split issue #175 made between `udeaPackGate` and `udeaGraphBudget`, and this is
 * the shared half, so that splitting the gate does not fork the thing being gated into two copies
 * that drift.
 *
 * [edit] is exactly the region the budget times. The checks on what came back are [verify]'s, and
 * they are deliberately outside it: a benchmark whose measured region includes its own assertions
 * is measuring the assertions too.
 *
 * The corpus is **copied** into `build/tmp/scratch` first. A run that edited `moba/assets` would
 * leave the game's own asset tree modified when it failed halfway.
 */
internal class MobaWarmEdit(label: String) {

    /** The sheet whose authored scale every iteration moves. */
    private val probed = AssetId("character/orc_idle_sheet")

    private val root: Path = copyCorpus(label)

    private val script: Path = root.resolve("character/orc.udea.kts")

    private val original: String = script.readText()

    val daemon: AssetDaemon = AssetDaemon(
        repoRoot = TestPaths.repoRoot,
        assetRoot = root,
        scriptClasspath = TestPaths.compilerClasspath,
        cacheDirectory = TestPaths.scratch("daemon/$label/cache"),
    )

    /** How many assets the daemon loaded; the corpus this walks is what the cost tracks. */
    val assetCount: Int get() = daemon.ids.size

    /** Starts the daemon, refusing to go on unless the game's own corpus is valid and non-empty. */
    fun start() {
        val started = daemon.start()
        assertTrue(
            started.ok,
            "the game's own corpus must be valid before it is timed:\n" +
                started.diagnostics.joinToString("\n") { "${it.ruleId} ${it.message}" },
        )
        // Non-empty, not an exact count. The cost tracks whatever the corpus currently holds, so
        // an exact number here would be a second thing to edit every time somebody authors an
        // asset, and would fail for a reason that is not a regression.
        assertTrue(
            daemon.ids.isNotEmpty(),
            "the daemon loaded no assets, so this would be timing an empty walk",
        )
    }

    /**
     * One edit, and nothing else: write, reload, commit, read the new value back.
     *
     * [iteration] picks a different scale every time, so the compiled-script jar cache can never
     * answer the edit and turn this into a measurement of a hash lookup.
     */
    fun edit(iteration: Int): Edit {
        val scale = "1.${70 + iteration}F"
        val edited = original.replace("val orcScale = 1.88F", "val orcScale = $scale")
        assertTrue(edited != original, "the harness's own edit did not change the file")

        script.writeText(edited)
        val applied = assertIs<ReloadOutcome.Applied>(
            daemon.reload(listOf(script)),
            "a scale change is an ordinary value change, not a shape change",
        )
        daemon.commit()
        val observed = assertIs<SpriteSheet>(daemon.value(probed.value))
        return Edit(
            requestedScale = scale.removeSuffix("F").toFloat(),
            observedScale = observed.scale,
            changedIds = applied.changedIds,
        )
    }

    /** What an [edit] must have done, asserted outside whatever measured it. */
    fun verify(edit: Edit) {
        assertEquals(edit.requestedScale, edit.observedScale)
        assertTrue(
            probed in edit.changedIds,
            "the delta an `AssetHotReload` would push does not name $probed: ${edit.changedIds}",
        )
        // Five sheets share `orcScale`, so the delta is the five of them and nothing else.
        assertEquals(5, edit.changedIds.size, "changed: ${edit.changedIds}")
    }

    /** What one [edit] produced. */
    class Edit(
        val requestedScale: Float,
        val observedScale: Float,
        val changedIds: Collection<AssetId>,
    )

    private companion object {

        /**
         * `moba/assets`, copied whole into scratch.
         *
         * Whole and not scripts-only: the daemon's reference walk is over ids, but a corpus
         * missing its PNGs would be a different tree from the one this claims to exercise the day
         * a validator that reads files is added to the reload path.
         */
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        fun copyCorpus(label: String): Path {
            val source = TestPaths.repoRoot.resolve("moba/assets")
            assertTrue(
                source.exists() && AssetCompiler.scriptsUnder(source).isNotEmpty(),
                "this is about the game's one asset root; $source is not it",
            )
            val target = TestPaths.scratch("daemon/$label/assets")
            for (file in source.walk().filter { it.isRegularFile() }) {
                val destination = target.resolve(file.relativeTo(source).toString())
                destination.parent?.createDirectories()
                file.copyTo(destination, overwrite = true)
            }
            return target
        }
    }
}
