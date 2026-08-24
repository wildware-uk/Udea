package dev.wildware.udea.build.determinism

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * `udeaVerifyDeterminism` (issue #150).
 *
 * An ASM reference scan over the source sets [DeterminismRules.SIMULATION_SCOPES] **declares**
 * to be simulation, failing on wall clock, unseeded randomness, calendar time, hash-ordered
 * collections, Box2D inside predicted code, and device reads - each under a stable `DET00N` id,
 * with a repo-relative span and the sanctioned replacement.
 *
 * ## It is not the determinism gate, and it says so on every run
 *
 * Spec section 7 calls this a cheap first filter and predicts that its green light will be
 * trusted anyway. [DeterminismScan.NOT_THE_GATE] is printed on pass and on failure for that
 * reason. The gate is `WorldHasher` snapshot equivalence and the cross-OS `replay-equality`
 * job in `ci.yml`; when the two disagree, the replay result wins and this table grows a rule.
 */
public abstract class UdeaVerifyDeterminismTask : DefaultTask() {

    /** Repository root: where module directories and `determinism-allowlist.txt` are found. */
    @get:Input
    public abstract val repoRootPath: org.gradle.api.provider.Property<String>

    /** `determinism-allowlist.txt`. Parsed strictly; see [Allowlist]. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val allowlistFile: RegularFileProperty

    /**
     * The compiled classes of the declared simulation scopes.
     *
     * Declared as an input so the gate is up-to-date-checked against the bytecode it reads
     * rather than passing from cache across exactly the edits it exists to notice.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val simulationClasses: ConfigurableFileCollection

    /**
     * Catalog alias to resolved version, for the audit's `@version` pins.
     *
     * The pin exists because `determinism-audit.md` is a set of claims about a *particular*
     * Fleks and LibGDX source. An upgrade invalidates them silently, so an upgrade fails here.
     */
    @get:Input
    public abstract val resolvedVersions: MapProperty<String, String>

    /** The declared scopes, as strings, so editing the table invalidates the task. */
    @get:Input
    public val declaredScopes: List<String>
        get() = DeterminismRules.SIMULATION_SCOPES.map {
            "${it.project}/${it.sourceSet}${it.packagePrefixes.sorted()}"
        }

    /** The full report, kept on a green run as well as a red one. */
    @get:OutputFile
    public abstract val report: RegularFileProperty

    /** Build directory, only used to place the report. */
    @get:org.gradle.api.tasks.Internal
    public abstract val reportDirectory: DirectoryProperty

    @TaskAction
    public fun verify() {
        val repoRoot = File(repoRootPath.get())
        val allowlistText = allowlistFile.get().asFile.readText()
        val result = DeterminismScan.run(
            inputs = DeterminismRules.SIMULATION_SCOPES.map { scopeInput(repoRoot, it) },
            allowlist = Allowlist.parse(allowlistText),
            repoRoot = repoRoot,
            resolvedVersions = resolvedVersions.get(),
        )
        val text = DeterminismScan.report(result)
        report.get().asFile.apply { parentFile.mkdirs(); writeText(text) }
        if (result.failed) throw GradleException(text)
        // On a green run too. A first filter whose limits are only stated when it fails is a
        // first filter that gets read as a gate on every run that matters.
        logger.lifecycle(text)
    }

    /** Where a scope's classes and sources live, by the layout every module in this build uses. */
    private fun scopeInput(repoRoot: File, scope: SimScope): DeterminismScan.ScopeInput {
        val module = repoRoot.resolve(scope.project.removePrefix(":").replace(':', '/'))
        return DeterminismScan.ScopeInput(
            scope = scope,
            classRoots = LANGUAGES.map {
                module.resolve("build/classes/$it/${scope.sourceSet}")
            },
            sourceRoots = LANGUAGES.map { module.resolve("src/${scope.sourceSet}/$it") },
        )
    }

    public companion object {
        /** The language directories Gradle's JVM layout uses, in the order sources are looked up. */
        public val LANGUAGES: List<String> = listOf("kotlin", "java")

        /** Task name. Referenced by name from `udea-render`'s docs and from `ci.yml`. */
        public const val TASK_NAME: String = "udeaVerifyDeterminism"

        /** The checked-in allowlist, at the repository root. */
        public const val ALLOWLIST_FILE: String = "determinism-allowlist.txt"

        /** The manual Fleks/LibGDX audit (issue #151), at the repository root. */
        public const val AUDIT_FILE: String = "determinism-audit.md"

        /** Catalog aliases the `@version` pins must cover. */
        public val PINNED_ALIASES: List<String> = listOf("fleks", "gdx")
    }
}
