package dev.wildware.udea.assets.compiler.migrate

import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * `udeaMigrateAssets`, as a process.
 *
 * A `main` and not a Gradle task type, because the Gradle plugin lives in `udea-gradle` and the
 * rules live here; a task that only forks this keeps the rewriting logic in the module that owns
 * it and testable without a build. `:udea-assets-compiler:udeaMigrateAssets` forks it.
 *
 * ```
 * udeaMigrateAssets <sourceAssetRoot> <destinationAssetRoot> [--dry-run]
 * ```
 *
 * Exit code is 0 whether or not anything was undecided: an undecided span is a
 * `TODO(udea-migrate)` for a human, not a failed migration. It is non-zero only when the
 * migrator itself could not run.
 */
public object AssetMigratorCli {

    @JvmStatic
    public fun main(args: Array<String>) {
        val positional = args.filterNot { it.startsWith("--") }
        if (positional.size != 2) {
            System.err.println("usage: udeaMigrateAssets <sourceAssetRoot> <destinationAssetRoot> [--dry-run]")
            exitProcess(2)
        }
        val source = Path.of(positional[0]).toAbsolutePath().normalize()
        val destination = Path.of(positional[1]).toAbsolutePath().normalize()
        val dryRun = args.contains("--dry-run")
        val report = AssetTreeMigration(source, destination).run(write = !dryRun)
        println("[udeaMigrateAssets] $source -> $destination${if (dryRun) " (dry run)" else ""}")
        println("[udeaMigrateAssets] ${report.results.size} scripts, ${report.changedFiles} changed, " +
            "${report.changedLines}/${report.totalLines} lines touched")
        report.editsByRule.filterValues { it > 0 }.forEach { (rule, count) ->
            println("[udeaMigrateAssets]   $rule: $count")
        }
        if (report.undecided.isEmpty()) {
            println("[udeaMigrateAssets] nothing undecided")
        } else {
            println("[udeaMigrateAssets] ${report.undecided.size} undecided, marked with ${AssetMigrator.TODO_MARKER}")
            report.undecided.forEach { println("[udeaMigrateAssets]   $it") }
        }
    }
}
