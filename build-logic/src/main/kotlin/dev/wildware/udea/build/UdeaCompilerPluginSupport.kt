package dev.wildware.udea.build

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/**
 * Applies `udea-compiler-plugin` to every compilation of a `udea-*` module and of `moba`.
 *
 * This is what `-Pudea.compilerPlugin.enabled=false` switches off. Before it existed the flag
 * was read by `dev.wildware.udea.kotlin-library`, stored in `extraProperties` and consumed by nobody, so
 * the CI leg that proves the degrade path works compiled byte for byte what the normal build
 * compiled (issue #164). [isApplicable] is the consumer: with the flag off it returns `false`
 * for every compilation, the Kotlin Gradle plugin adds no dependency and produces no
 * `-Xplugin` argument, and the checkers are genuinely gone.
 *
 * ### Why this lives in `build-logic` and not in `udea-gradle`
 *
 * `udea-gradle` is a *subproject of the build being configured*. Gradle cannot apply a plugin
 * whose implementation is a sibling project of the project applying it — the plugin has to be
 * on the settings classpath, which for this repository means the `build-logic` included build.
 * `udea-gradle` remains the home of the plugin a *consumer game* applies; it is structurally
 * unable to be the home of the plugin this repository applies to itself.
 *
 * ### Why the artifact is substituted rather than resolved
 *
 * [getPluginArtifact] is the only way to tell the Kotlin Gradle plugin what to put on the
 * compiler-plugin classpath, and it takes Maven coordinates: `SubpluginEnvironment` turns it
 * into `project.dependencies.add(pluginConfigurationName, "group:name:version")`. In *this*
 * build the plugin is a project rather than a dependency, so [apply] substitutes those
 * coordinates back to `:udea-compiler-plugin` on every compiler-plugin classpath, and they carry
 * a version no repository can answer so that a substitution which silently stopped being
 * registered fails loudly. The substitution is what makes `compileKotlin` depend on
 * `:udea-compiler-plugin:jar`, which is how a plugin edit is picked up by the next build instead
 * of by the next publish.
 *
 * A game in its own repository (issue #265) has no such project: it applies this convention from
 * a published `udea-build-logic`, and the plugin is a published jar it resolves like any other
 * dependency. Both arrangements are wired here, they are told apart by
 * [Project.buildCompilesCompilerPlugin], and neither is allowed to end in "no plugin at all" -
 * `udeaVerifyCompilerPlugin` fails when nothing landed on the classpath, in either build.
 *
 * A plain `dependencies { kotlinCompilerPluginClasspathMain(project(...)) }` would put the jar
 * on the classpath too, but it would leave the `-P plugin:…` options to be hand-encoded into
 * `freeCompilerArgs` as strings, which is the string-concatenation smell §1 of the engineering
 * standards names, and it would not survive a new source set.
 */
public class UdeaCompilerPluginSupport : KotlinCompilerPluginSupportPlugin {

    /**
     * The project this instance was applied to.
     *
     * [getPluginArtifact] takes no arguments and the answer is not the same for every build, so
     * the project has to be remembered. One instance is created per project that applies the
     * plugin, which is what makes this safe.
     */
    private lateinit var project: Project

    /**
     * Registers the substitution described above on this project's compiler-plugin classpaths.
     *
     * Lazy on purpose: the configurations are created by the Kotlin Gradle plugin, one per
     * compilation, and `moba`'s and `udea-core`'s sets differ (`java-test-fixtures` adds
     * another). `matching { }.configureEach { }` covers whichever ones come to exist without
     * this plugin having to know their names.
     */
    override fun apply(target: Project) {
        project = target
        val classpaths = target.configurations.matching {
            it.name.startsWith(UdeaCompilerPluginWiring.PLUGIN_CLASSPATH_PREFIX)
        }
        // Substituted only when this build is the one that compiles the plugin. A game in its own
        // repository (issue #265) has no `:udea-compiler-plugin` project, and a substitution
        // naming a project path the build does not have fails at configuration time; there the
        // coordinate is a published artifact and is meant to resolve.
        val substituteToProject = target.buildCompilesCompilerPlugin()
        val coordinates = "${UdeaCompilerPluginWiring.ARTIFACT_GROUP}:" +
            UdeaCompilerPluginWiring.ARTIFACT_NAME
        classpaths.configureEach {
            // The Kotlin Gradle plugin creates a Kotlin/Native compilation's plugin classpath
            // intransitive, where a JVM or Wasm one is transitive, so this plugin's jar reached the
            // native compiler without `udea-diagnostics` and `udea-annotations`, and the first
            // checker to touch one of their classes failed the compilation with a
            // `NoClassDefFoundError` rather than a diagnostic. `udea-assets`, the first module with
            // both this plugin and an iOS target, met it compiling its iOS tests (issue #205).
            // Set when the classpath is about to resolve, because the Kotlin plugin clears the flag
            // after the configuration is created and so after this block first runs.
            val classpath = this
            withDependencies { classpath.isTransitive = true }
            if (substituteToProject) {
                resolutionStrategy.dependencySubstitution {
                    substitute(module(coordinates))
                        .using(project(UdeaCompilerPluginWiring.PLUGIN_PROJECT_PATH))
                        .because(
                            "udea-compiler-plugin is built by this build and published nowhere; " +
                                "getPluginArtifact() can only name Maven coordinates, so they are " +
                                "substituted back to the project that produces them.",
                        )
                }
            }
        }
    }

    /**
     * True when [UdeaCompilerPluginWiring.appliesTo] says so for the owning project.
     *
     * Every compilation of an applicable module gets the plugin — `main`, `test`,
     * `testFixtures` alike. A `@Net val` in a test source is the same defect as one in
     * production code, and a checker that stopped at `main` would be a rule with a hole in it
     * exactly where the fixtures live.
     */
    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean {
        val project = kotlinCompilation.project
        return UdeaCompilerPluginWiring.appliesTo(project.path, project.udeaCompilerPluginEnabled())
    }

    /** The `-P plugin:dev.wildware.udea:<key>=<value>` arguments, from [UdeaCompilerPluginWiring.OPTIONS]. */
    override fun applyToCompilation(
        kotlinCompilation: KotlinCompilation<*>,
    ): Provider<List<SubpluginOption>> = kotlinCompilation.project.provider {
        UdeaCompilerPluginWiring.OPTIONS.map { SubpluginOption(it.key, it.value) }
    }

    override fun getCompilerPluginId(): String = UdeaCompilerPluginWiring.PLUGIN_ID

    /**
     * The coordinate the Kotlin Gradle plugin puts on each compiler-plugin classpath.
     *
     * Its version depends on which build is asking - see
     * [UdeaCompilerPluginWiring.artifactVersion]. In this repository it is the unresolvable
     * sentinel that the substitution in [apply] replaces; in a game's own repository it is the
     * engine version that game builds against, and there is nothing to substitute.
     */
    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = UdeaCompilerPluginWiring.ARTIFACT_GROUP,
        artifactId = UdeaCompilerPluginWiring.ARTIFACT_NAME,
        version = UdeaCompilerPluginWiring.artifactVersion(
            buildCompilesPlugin = project.buildCompilesCompilerPlugin(),
            udeaVersion = project.providers.gradleProperty(UdeaVersion.PROPERTY).orNull,
        ),
    )
}

/**
 * Whether this build is the one that compiles `udea-compiler-plugin`.
 *
 * The question every decision about the plugin coordinate turns on, asked in one place so the
 * substitution, the artifact version and `udeaVerifyCompilerPlugin` cannot answer it differently.
 * `false` is a game in its own repository (issue #265), where the plugin is a published artifact.
 */
public fun Project.buildCompilesCompilerPlugin(): Boolean =
    rootProject.findProject(UdeaCompilerPluginWiring.PLUGIN_PROJECT_PATH) != null

/**
 * `-Pudea.compilerPlugin.enabled`, validated by [UdeaBuildFlags] and absent meaning enabled.
 *
 * A `Provider` rather than the `extraProperties` entry `dev.wildware.udea.kotlin-library` publishes: an
 * extra property is untyped and can be absent, and reading the property directly means this
 * plugin behaves identically whether or not it is applied through that convention.
 */
internal fun Project.udeaCompilerPluginEnabled(): Boolean =
    UdeaBuildFlags.compilerPluginEnabled(
        providers.gradleProperty(UdeaBuildFlags.COMPILER_PLUGIN_ENABLED).orNull,
    )
