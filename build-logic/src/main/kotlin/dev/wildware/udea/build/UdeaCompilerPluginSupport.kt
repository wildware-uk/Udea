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
 * was read by `udea.kotlin-library`, stored in `extraProperties` and consumed by nobody, so
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
 * into `project.dependencies.add(pluginConfigurationName, "group:name:version")`. Those
 * coordinates are never published anywhere — the plugin is built by this build — so [apply]
 * substitutes them back to `:udea-compiler-plugin` on every compiler-plugin classpath. The
 * substitution is what makes `compileKotlin` depend on `:udea-compiler-plugin:jar`, which is
 * how a plugin edit is picked up by the next build instead of by the next publish.
 *
 * A plain `dependencies { kotlinCompilerPluginClasspathMain(project(...)) }` would put the jar
 * on the classpath too, but it would leave the `-P plugin:…` options to be hand-encoded into
 * `freeCompilerArgs` as strings, which is the string-concatenation smell §1 of the engineering
 * standards names, and it would not survive a new source set.
 */
public class UdeaCompilerPluginSupport : KotlinCompilerPluginSupportPlugin {

    /**
     * Registers the substitution described above on this project's compiler-plugin classpaths.
     *
     * Lazy on purpose: the configurations are created by the Kotlin Gradle plugin, one per
     * compilation, and `moba`'s and `udea-core`'s sets differ (`java-test-fixtures` adds
     * another). `matching { }.configureEach { }` covers whichever ones come to exist without
     * this plugin having to know their names.
     */
    override fun apply(target: Project) {
        val classpaths = target.configurations.matching {
            it.name.startsWith(UdeaCompilerPluginWiring.PLUGIN_CLASSPATH_PREFIX)
        }
        // Loud, and named, rather than Gradle's bare "Project with path ... not found" from
        // deep inside a resolution. A build that carries this convention without the project
        // that builds the plugin has no wiring at all, and every checker is silently off.
        requireNotNull(target.rootProject.findProject(UdeaCompilerPluginWiring.PLUGIN_PROJECT_PATH)) {
            "${target.path} is on the udea.kotlin-library convention, which applies the K2 " +
                "compiler plugin, but this build has no " +
                "${UdeaCompilerPluginWiring.PLUGIN_PROJECT_PATH} project to apply. Include it " +
                "in settings.gradle.kts, or the FIR checkers are off with nothing saying so."
        }
        val coordinates = "${UdeaCompilerPluginWiring.ARTIFACT_GROUP}:" +
            UdeaCompilerPluginWiring.ARTIFACT_NAME
        classpaths.configureEach {
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

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = UdeaCompilerPluginWiring.ARTIFACT_GROUP,
        artifactId = UdeaCompilerPluginWiring.ARTIFACT_NAME,
        version = UdeaCompilerPluginWiring.ARTIFACT_VERSION,
    )
}

/**
 * `-Pudea.compilerPlugin.enabled`, validated by [UdeaBuildFlags] and absent meaning enabled.
 *
 * A `Provider` rather than the `extraProperties` entry `udea.kotlin-library` publishes: an
 * extra property is untyped and can be absent, and reading the property directly means this
 * plugin behaves identically whether or not it is applied through that convention.
 */
internal fun Project.udeaCompilerPluginEnabled(): Boolean =
    UdeaBuildFlags.compilerPluginEnabled(
        providers.gradleProperty(UdeaBuildFlags.COMPILER_PLUGIN_ENABLED).orNull,
    )
