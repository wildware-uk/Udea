package dev.wildware.udea.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.io.File

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

        AgentsMd.report(name, findings)?.let { throw GradleException(it) }
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

        TrelloMap.report(name, findings)?.let { throw GradleException(it) }
    }
}

/**
 * Fails if a page in `docs/wiki/` links to a page, a repository path or a Gradle project that
 * does not exist.
 *
 * See [WikiCheck] for the rules. A page may name any file in the repository, so every file is in
 * effect an input; rather than declare the whole tree, the task is untracked and runs every time,
 * which costs one read of the wiki and a few directory lookups.
 */
@UntrackedTask(because = "a wiki page may name any file in the repository, so every file is an input")
public abstract class UdeaVerifyWikiTask : DefaultTask() {

    /** The repository root, which backticked paths are resolved against. */
    @get:Internal
    public abstract val repoRoot: DirectoryProperty

    /** `docs/wiki/`. */
    @get:Internal
    public abstract val wikiDirectory: DirectoryProperty

    /** `settings.gradle.kts`, the only authority on which projects exist. */
    @get:Internal
    public abstract val settingsScript: RegularFileProperty

    /** What was checked, written on success so a green run still shows its working. */
    @get:Internal
    public abstract val report: RegularFileProperty

    /** Checks every page and fails naming every finding. */
    @TaskAction
    public fun verify() {
        val wiki = wikiDirectory.get().asFile
        val pages = wiki.listFiles { file -> file.isFile && file.name.endsWith(".md") }
            .orEmpty()
            .associate { it.name to it.readText() }
        val pendingFile = File(wiki, WikiCheck.PENDING_FILE)
        val pending = WikiCheck.pendingPages(if (pendingFile.isFile) pendingFile.readText() else "")
        val findings = WikiCheck.findings(
            pages = pages,
            pending = pending,
            repo = RepoFiles(repoRoot.get().asFile),
            projects = WikiCheck.projects(settingsScript.get().asFile.readText()),
        )

        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                buildString {
                    appendLine("pages checked: ${pages.keys.sorted().joinToString()}")
                    appendLine("pending pages: ${pending.sorted().joinToString().ifEmpty { "(none)" }}")
                    appendLine("findings: ${findings.size}")
                },
            )
        }

        WikiCheck.report(name, findings)?.let { throw GradleException(it) }
    }
}
