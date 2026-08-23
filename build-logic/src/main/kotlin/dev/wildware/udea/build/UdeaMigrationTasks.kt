package dev.wildware.udea.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Shared plumbing for the two migration gates: turn a file collection into repo-relative
 * [SourceFile]s, and write a report whether the gate passes or fails.
 *
 * Repo-relative and forward-slashed, always. Spec section 5 makes "`SourceSpan` (repo-relative,
 * never absolute)" a contract, and a gate that prints `C:\Users\...` in CI output is a gate
 * whose findings cannot be pasted into an issue.
 */
public abstract class UdeaMigrationTask : DefaultTask() {

    /** `docs/migration/ledger.md`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val ledger: RegularFileProperty

    /**
     * The repository root. `@Internal` because it is not part of what is checked — it only
     * shortens the paths in the messages, and declaring it as an input would make every
     * checkout location a different cache key.
     */
    @get:Internal
    public abstract val repoRoot: DirectoryProperty

    /** What was scanned, written on success too, so a green run still shows its working. */
    @get:OutputFile
    public abstract val report: RegularFileProperty

    /** [file] as a path relative to the repository root, forward slashes. */
    protected fun relativise(file: File): String =
        file.absoluteFile.toRelativeString(repoRoot.get().asFile.absoluteFile).replace('\\', '/')

    /** Reads [files] into memory, sorted by path so failures are reported in a stable order. */
    protected fun read(files: ConfigurableFileCollection): List<SourceFile> =
        files.files.filter { it.isFile }
            .map { SourceFile(relativise(it), it.readText()) }
            .sortedBy { it.path }

    /** Parses the ledger, or fails naming the file rather than a stack trace out of the parser. */
    protected fun rows(): List<LedgerRow> =
        try {
            MigrationLedger.parse(ledger.get().asFile.readText())
        } catch (e: IllegalArgumentException) {
            throw GradleException("${relativise(ledger.get().asFile)} could not be read: ${e.message}", e)
        }

    /** Writes [text] to the report file, creating its directory. */
    protected fun writeReport(text: String) {
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(text)
        }
    }
}

/**
 * Fails if a legacy Kotlin file has no ledger row, or a ledger row names a file that is gone.
 *
 * The tracking artefact the Phase 6 final gate closes out. It also prints per-module
 * remaining/deleted counts, so "how much of the old tree is left" is one command away rather
 * than a `find` somebody has to remember the shape of.
 */
public abstract class UdeaLegacyReportTask : UdeaMigrationTask() {

    /** Every Kotlin file in the old tree. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val legacySources: ConfigurableFileCollection

    /**
     * Old modules that are fully gone, printed at zero.
     *
     * They have no ledger rows — a row naming a file that no longer exists is precisely what
     * this task rejects — so without naming them the progress line would silently stop
     * mentioning the modules that were actually finished, which is the wrong direction for a
     * report whose job is showing progress.
     */
    @get:Input
    public abstract val retiredModules: ListProperty<String>

    /** Checks coverage both ways, prints the counts, and fails on any gap. */
    @TaskAction
    public fun verify() {
        val rows = rows()
        val present = read(legacySources).map { it.path }.toSet()
        val counts = MigrationLedger.moduleCounts(rows, present)
        val findings = MigrationLedger.coverageFindings(rows, present)

        val summary = buildString {
            appendLine("ledger rows: ${rows.size}")
            appendLine("legacy Kotlin files in the tree: ${present.size}")
            appendLine()
            appendLine("module           remaining  deleted  total")
            counts.forEach { (module, count) ->
                appendLine("%-15s  %9d  %7d  %5d".format(module, count.remaining, count.deleted, count.total))
            }
            retiredModules.get().sorted().forEach {
                appendLine("%-15s  %9d  %7s  %5s".format(it, 0, "-", "-") + "  (retired)")
            }
        }
        writeReport(summary)
        logger.lifecycle(summary)

        MigrationLedger.report(name, findings)?.let { throw GradleException(it) }
    }
}

/**
 * Fails if a `udea-*` or `moba` source looks copied out of the old tree without a current review.
 *
 * Spec section 4 permits copying forward "file by file, with the copy reviewed". The ledger
 * alone cannot show that, because a wholesale copy and a reviewed port produce identical rows;
 * this compares the bytes. It also catches the case issue #146 calls the one that earns its
 * keep past Phase 2: a copy reviewed against a version of the source that has since changed.
 */
public abstract class UdeaVerifyMigrationTask : UdeaMigrationTask() {

    /** Every Kotlin file in the old tree — the sources a copy could have come from. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val legacySources: ConfigurableFileCollection

    /** Every Kotlin file in the `udea-*` tree and `moba` — the possible copies. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val rewriteSources: ConfigurableFileCollection

    /** Compares every rewrite file against every legacy file and fails on the first gap. */
    @TaskAction
    public fun verify() {
        val legacy = read(legacySources)
        val rewrite = read(rewriteSources)
        val findings = MigrationLedger.copyFindings(legacy, rewrite, rows())

        writeReport(
            buildString {
                appendLine("legacy sources compared against: ${legacy.size}")
                appendLine("rewrite sources scanned: ${rewrite.size}")
                appendLine("similarity threshold: ${MigrationLedger.SIMILARITY_THRESHOLD}")
            },
        )

        MigrationLedger.report(name, findings)?.let { throw GradleException(it) }
    }
}

/**
 * Fails if `AGENTS.md` stops describing the tree it is a brief for.
 *
 * See [AgentsMd] for what "stops describing" means and why both halves are checked. Issue #138
 * asks for this as `scripts/check-agents-md.main.kts`. It is a Gradle task instead: a
 * `.main.kts` needs a Kotlin CLI that neither CI nor a fresh checkout has, whereas every other
 * Phase 0 gate is already a task on `check` with a unit-tested rule behind it, and a gate
 * nobody can run is a gate that is not running.
 */
public abstract class UdeaVerifyAgentsMdTask : DefaultTask() {

    /** `AGENTS.md`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val agentsMd: RegularFileProperty

    /** `settings.gradle.kts` — the only authority on which modules exist. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val settingsScript: RegularFileProperty

    /** What was compared, written on success so a green run still shows its working. */
    @get:OutputFile
    public abstract val report: RegularFileProperty

    /** Compares the two and fails naming every difference. */
    @TaskAction
    public fun verify() {
        val brief = agentsMd.get().asFile.readText()
        val settings = settingsScript.get().asFile.readText()
        val declared = AgentsMd.declaredModules(settings)
        val findings = AgentsMd.findings(brief, settings)

        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                buildString {
                    appendLine("modules in settings.gradle.kts: ${declared.size}")
                    appendLine("modules documented in AGENTS.md: ${AgentsMd.documentedModules(brief).size}")
                    appendLine("spec section 5 contracts checked for: ${AgentsMd.CONTRACTS.size}")
                },
            )
        }

        MigrationLedger.report(name, findings)?.let { throw GradleException(it) }
    }
}

/**
 * Fails if spec section 9 names a Trello card `docs/migration/trello-map.md` does not cover.
 *
 * See [TrelloMap] for why the check runs in exactly one direction.
 */
public abstract class UdeaVerifyTrelloMapTask : DefaultTask() {

    /** The design spec, whose section 9 is the list of cards to account for. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val spec: RegularFileProperty

    /** `docs/migration/trello-map.md`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val map: RegularFileProperty

    /** What was compared, written on success so a green run still shows its working. */
    @get:OutputFile
    public abstract val report: RegularFileProperty

    /** Compares the two and fails naming every uncovered card. */
    @TaskAction
    public fun verify() {
        val specText = spec.get().asFile.readText()
        val mapText = map.get().asFile.readText()
        val findings = TrelloMap.findings(specText, mapText)

        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                buildString {
                    appendLine("cards named in spec section 9: ${TrelloMap.cardsInSpec(specText).size}")
                    appendLine("cards given a disposition in the map: ${TrelloMap.cardsInMap(mapText).size}")
                },
            )
        }

        MigrationLedger.report(name, findings)?.let { throw GradleException(it) }
    }
}
