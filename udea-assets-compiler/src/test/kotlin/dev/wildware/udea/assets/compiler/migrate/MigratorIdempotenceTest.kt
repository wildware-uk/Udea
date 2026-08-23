package dev.wildware.udea.assets.compiler.migrate

import dev.wildware.udea.assets.compiler.TestPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

/**
 * Running `udeaMigrateAssets` twice produces no second diff.
 *
 * ## Why the whole tree is copied, art included
 *
 * The migration is in-place on the copy, and the second run has to see the *same* asset root the
 * first one did — sheets and all. A second run over a directory holding only the scripts probes
 * for art that is not there, reports every sheet undecided, and writes a fresh
 * `TODO(udea-migrate)` above each. That is exactly how the comment-stacking bug in `insertTodos`
 * was found: it is a real non-idempotence and not a fixture artefact, and it is guarded now.
 *
 * The copy is a few megabytes of PNG. That is the price of testing the property the acceptance
 * criteria state rather than a weaker one that happens to be cheaper.
 */
class MigratorIdempotenceTest {

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `a second migration of a migrated tree writes no diff`() {
        val working = TestPaths.scratch("migrate-idempotence")
        TestPaths.exampleAssets.copyToRecursively(working, followLinks = false, overwrite = true)

        val first = AssetTreeMigration(working, working).run()
        assertEquals(19, first.results.size)
        assertTrue(first.changedFiles > 0, "the corpus must actually have needed migrating")
        val afterFirst = snapshot(working)

        val second = AssetTreeMigration(working, working).run()
        val afterSecond = snapshot(working)

        val differing = afterFirst.keys.filter { afterFirst[it] != afterSecond[it] }
        assertTrue(
            differing.isEmpty(),
            "the second run rewrote ${differing.sorted()}:\n" +
                differing.sorted().joinToString("\n") { path ->
                    "--- $path\n${diff(afterFirst.getValue(path), afterSecond.getValue(path))}"
                },
        )
        assertEquals(0, second.results.count { it.changed }, "the second run reported an edit")
    }

    /**
     * The same undecided cases are reported both times, and reported once each.
     *
     * A migrator that stopped *reporting* on a second run would also pass the byte-identity
     * assertion above, and would be wrong: the wizard's six missing sheets are still missing, and
     * a build that ran the migrator twice would go quiet about them.
     */
    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `the second run reports the same undecided cases, not none and not double`() {
        val working = TestPaths.scratch("migrate-undecided")
        TestPaths.exampleAssets.copyToRecursively(working, followLinks = false, overwrite = true)

        val first = AssetTreeMigration(working, working).run()
        val second = AssetTreeMigration(working, working).run()

        assertEquals(6, first.undecided.size, first.undecided.joinToString("\n"))
        assertEquals(
            first.undecided.map { it.message }.sorted(),
            second.undecided.map { it.message }.sorted(),
        )
        val wizard = working.resolve("character/wizard.udea.kts").readText()
        assertEquals(
            6,
            wizard.lines().count { it.contains(AssetMigrator.TODO_MARKER) },
            "the TODO comments stacked up:\n$wizard",
        )
    }

    @OptIn(ExperimentalPathApi::class)
    private fun snapshot(root: Path): Map<String, String> = root.walk()
        .filter { it.isRegularFile() && it.name.endsWith(AssetTreeMigration.SCRIPT_SUFFIX) }
        .associate { it.relativeTo(root).toString().replace('\\', '/') to it.readText() }

    private fun diff(before: String, after: String): String {
        val a = before.lines()
        val b = after.lines()
        return (0 until maxOf(a.size, b.size))
            .filter { a.getOrNull(it) != b.getOrNull(it) }
            .take(6)
            .joinToString("\n") { "  ${it + 1}: '${a.getOrNull(it)}' -> '${b.getOrNull(it)}'" }
    }

    init {
        // `scratch` deletes and recreates, so the copy target exists before `copyToRecursively`.
        TestPaths.repoRoot.resolve("udea-assets-compiler/build/tmp/scratch").createDirectories()
    }
}
