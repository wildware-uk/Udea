package dev.wildware.udea.build

import dev.wildware.udea.build.determinism.SimScope
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.kotlin.dsl.findByType
import javax.inject.Inject

/**
 * What a build tells Udea's gates about itself (issue #265).
 *
 * The gates ask three questions that only the build being gated can answer: which of its
 * projects are simulation, which of them ships a runnable process, and - implicitly, by being
 * applied - which projects exist at all. This repository used to answer all three by writing
 * `:moba`'s paths into `build-logic`, which made every answer a statement about this repository
 * and left a game in its own repository governed by nothing at all.
 *
 * So the answers are configuration now, and `moba` gives them through the same extension a game
 * outside this tree does. There is one code path, and this repository's own build is what
 * exercises it on every run.
 *
 * ```kotlin
 * plugins { id("dev.wildware.udea.game-gates") }
 *
 * udeaGates {
 *     ships(":desktop")
 *     simulation(
 *         project = ":game",
 *         packagePrefixes = listOf("com.example.robot.sim"),
 *         why = "...",
 *     )
 * }
 * ```
 */
public abstract class UdeaGatesExtension @Inject constructor(private val project: Project) {

    /**
     * The simulation scopes this build declares, on top of the engine's own
     * ([dev.wildware.udea.build.determinism.DeterminismRules.SIMULATION_SCOPES], which are added
     * when the engine's modules are part of the build).
     */
    public abstract val simulationScopes: ListProperty<SimScope>

    /**
     * Declares a source set of [project] to be simulation, so `udeaVerifyDeterminism` scans it.
     *
     * @param project Gradle path, e.g. `:moba:game`.
     * @param sourceSet the source set name. Only `main` is ever simulation; a test may plant a
     *   clock read on purpose, and one of this gate's own tests does.
     * @param packagePrefixes dotted package prefixes inside it, or empty for the whole source
     *   set. A game that keeps a HUD beside its rules narrows here rather than switching the
     *   gate off.
     * @param why the argument for calling this simulation. Read in review; see [SimScope].
     */
    @JvmOverloads
    public fun simulation(
        project: String,
        sourceSet: String = "main",
        packagePrefixes: List<String> = emptyList(),
        why: String,
    ) {
        val scope = SimScope(project = project, sourceSet = sourceSet, packagePrefixes = packagePrefixes, why = why)
        val existing = simulationScopes.get()
        require(existing.none { it.project == scope.project && it.sourceSet == scope.sourceSet }) {
            "${scope.project} (${scope.sourceSet}) is declared simulation twice; two scopes over " +
                "one source set would scan it twice and report every finding twice."
        }
        requireNotNull(this.project.rootProject.findProject(scope.project)) {
            "udeaGates.simulation names '${scope.project}', which this build does not include. A " +
                "scope over a project that does not exist is a scan of nothing, and a scan of " +
                "nothing passes forever."
        }
        simulationScopes.add(scope)
    }

    /**
     * Declares [projectPaths] to be the projects that ship a runnable process, and applies the
     * release gate to each.
     *
     * The gate goes on the project that produces the artifact a player runs - `:moba:desktop`
     * here - rather than on a library it packages, because the classpath `UDEA-REL-002` is about
     * is the one that process starts with.
     */
    public fun ships(vararg projectPaths: String) {
        projectPaths.forEach { path ->
            val target = requireNotNull(project.rootProject.findProject(path)) {
                "udeaGates.ships names '$path', which this build does not include, so the " +
                    "release gate would be registered on nothing."
            }
            target.pluginManager.apply(RELEASE_CHECK_PLUGIN)
        }
        shipping += projectPaths
    }

    /** The paths passed to [ships], for the aggregate task the gates plugin registers. */
    internal val shipping: MutableSet<String> = linkedSetOf()

    private companion object {
        const val RELEASE_CHECK_PLUGIN: String = "dev.wildware.udea.release-check"
    }
}

/** The name the extension is registered under, and the block a build script writes. */
public const val UDEA_GATES_EXTENSION: String = "udeaGates"

/**
 * This build's [UdeaGatesExtension], creating it if `dev.wildware.udea.game-gates` has not already.
 *
 * Create-or-return, because `dev.wildware.udea.determinism-check` reads the extension and can be applied on
 * its own - a build that wants the determinism scan and nothing else is a reasonable thing, and
 * a plugin that failed unless another one had been applied first would be an ordering rule
 * nobody can see.
 */
public fun Project.udeaGates(): UdeaGatesExtension =
    extensions.findByType<UdeaGatesExtension>()
        ?: extensions.create(UDEA_GATES_EXTENSION, UdeaGatesExtension::class.java, this)
