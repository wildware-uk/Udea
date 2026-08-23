package dev.wildware.udea.assets.compiler.migrate

import dev.wildware.udea.diagnostics.UdeaDiagnostic
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeText

/** What migrating a whole tree did, per file and in total. */
public data class TreeMigrationReport(
    public val results: List<MigrationResult>,
) {
    /** Every span the migrator refused to decide, across the tree. */
    public val undecided: List<UdeaDiagnostic> get() = results.flatMap { it.undecided }

    /** How many files the rules actually changed. */
    public val changedFiles: Int get() = results.count { it.changed }

    /** How many edits each rule made, in enum order. */
    public val editsByRule: Map<MigrationRule, Int>
        get() = MigrationRule.entries.associateWith { rule ->
            results.sumOf { result -> result.edits.count { it.rule == rule } }
        }

    /** Lines that changed, as a fraction of the corpus. The "how mechanical was it" number. */
    public val changedLines: Int
        get() = results.sumOf { result ->
            if (!result.changed) 0 else diffLines(result.original, result.migrated)
        }

    public val totalLines: Int get() = results.sumOf { it.original.lines().size }

    private fun diffLines(before: String, after: String): Int {
        val a = before.lines()
        val b = after.lines()
        // A line-count difference, not a real LCS diff: this is a report number, and an exact
        // edit distance over 1300 lines would be a second algorithm to get wrong. It counts
        // lines present in one and not the other, which for these rewrites is the honest
        // over-estimate rather than an under-estimate.
        val common = a.toMutableList()
        var same = 0
        for (line in b) if (common.remove(line)) same++
        return maxOf(a.size, b.size) - same
    }
}

/**
 * `udeaMigrateAssets` over a tree: migrate every `.udea.kts`, write the result, report.
 *
 * Separate from [AssetMigrator] because the per-file rewriting is the part with rules in it and
 * this is the part with a filesystem in it — and a migrator that cannot be run against a string
 * in a test is a migrator whose rules are only ever exercised through I/O.
 *
 * [destination] may be [source], in which case files are rewritten in place. That is what makes
 * the idempotence claim testable the way the acceptance criteria state it: run twice, second run
 * writes no diff.
 */
public class AssetTreeMigration(
    private val source: Path,
    private val destination: Path,
    /**
     * Where the sheets live, for the frame-count check.
     *
     * Usually [source] itself. It is a separate parameter because a migration that *moves* an
     * asset tree still has to probe the art where it is now, not where the scripts will end up.
     */
    private val probe: SheetProbe = PngSheetProbe(source),
    private val migrator: AssetMigrator = AssetMigrator(source, probe),
) {

    /** Migrates every script under [source], writing each under [destination]. */
    @OptIn(ExperimentalPathApi::class)
    public fun run(write: Boolean = true): TreeMigrationReport {
        val files = source.walk()
            .filter { it.isRegularFile() && it.name.endsWith(SCRIPT_SUFFIX) }
            .sortedBy { it.toString().replace('\\', '/') }
            .toList()
        val results = files.map { file ->
            val relative = file.relativeTo(source).toString().replace('\\', '/')
            val result = migrator.migrateText(relative, file.name, file.readTextNormalized())
            if (write) {
                val target = destination.resolve(relative)
                target.parent?.createDirectories()
                // `\n` and one trailing newline, always: two developers on two platforms
                // migrating the same tree must produce the same bytes, and a platform-default
                // line separator is the one thing that guarantees they do not.
                target.writeText(result.migrated.trimEnd('\n') + "\n")
            }
            result
        }
        return TreeMigrationReport(results)
    }

    private fun Path.readTextNormalized(): String = readText().replace("\r\n", "\n")

    public companion object {
        public const val SCRIPT_SUFFIX: String = ".udea.kts"
    }
}
