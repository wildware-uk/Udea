import dev.wildware.udea.build.UdeaBuildFlags
import dev.wildware.udea.build.UdeaKotlinPin
import dev.wildware.udea.build.UdeaStdlibPin
import dev.wildware.udea.build.UdeaVersions
import dev.wildware.udea.build.udeaLibrary
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

/**
 * The base convention for every new `udea-*` module and for `moba`.
 *
 * Deliberately contains NO graphics dependency: a module on this convention cannot see
 * GL. Modules that legitimately touch GL apply `udea.kotlin-library-gl` instead, which
 * is the only place LWJGL3/GL enters the build (spec 4, spec 3.5).
 *
 * It is also the single place the resolved `kotlin-stdlib` is pinned to the catalog's
 * Kotlin version — see [UdeaStdlibPin] for why, and for the one recorded exemption.
 */

plugins {
    kotlin("jvm")
}

group = "dev.wildware.udea"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(UdeaVersions.JVM_TOOLCHAIN)
    // Cheap now, painful to retrofit: Replicator<T> and friends are cross-module contracts.
    explicitApi()
}

dependencies {
    testImplementation(udeaLibrary("kotlin-test"))
    testImplementation(udeaLibrary("junit5-jupiter"))
    testRuntimeOnly(udeaLibrary("junit5-platform-launcher"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

/**
 * `-Pudea.compilerPlugin.enabled=false` (spec 7). Read here, in the convention every module
 * is on, so that the CI job proving the build is green with the K2 plugin disabled needs no
 * change to this file when `udea-compiler-plugin` gains a plugin to apply. Deliberately a
 * no-op today beyond validating the value and publishing it — see [UdeaBuildFlags].
 */
extensions.extraProperties[UdeaBuildFlags.COMPILER_PLUGIN_ENABLED] =
    UdeaBuildFlags.compilerPluginEnabled(
        providers.gradleProperty(UdeaBuildFlags.COMPILER_PLUGIN_ENABLED).orNull,
    )

// --- kotlin-stdlib pin (spec 7) ------------------------------------------------------

/** The classpaths of *this* module the pin covers, after [UdeaStdlibPin.EXEMPTIONS]. */
val pinnedConfigurationNames: Set<String> = UdeaStdlibPin.pinnedConfigurationsFor(path)

/**
 * Resolve `kotlin-stdlib` at the catalog's Kotlin version whatever a transitive dependency
 * asks for.
 *
 * Downgrading a library's stdlib is normally the unsupported direction. Here it is the
 * honest one: the compiler is ${UdeaVersions.KOTLIN}, so compiling against a 2.3 stdlib
 * only means resolving call sites against signatures that may not exist on the stdlib the
 * classloader actually provides. Raising the catalog version is how you get a newer stdlib;
 * a transitive dependency is not.
 */
configurations.matching { it.name in pinnedConfigurationNames }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-stdlib")) {
            useVersion(UdeaVersions.KOTLIN)
            because(
                "the project compiles with Kotlin ${UdeaVersions.KOTLIN} (gradle/libs.versions.toml); " +
                    "a transitive request must not drag the stdlib past the compiler (spec 7)",
            )
        }
    }
}

/**
 * `<configuration> <module>:<version>` for every `kotlin-stdlib*` artifact resolved on a
 * pinned classpath.
 *
 * Accumulated through a [ListProperty] rather than a fixed list of `configurations.named(...)`
 * lookups because `testFixturesRuntimeClasspath` and friends only exist once the owning
 * module applies `java-test-fixtures`, which happens after this convention. Everything is
 * mapped to plain strings at the provider level so the task holds nothing the configuration
 * cache cannot serialise.
 */
val resolvedStdlibs: ListProperty<String> = objects.listProperty(String::class.java)

configurations.matching { it.name in pinnedConfigurationNames }.all {
    val configurationName = name
    // Filtered to module components on purpose: an unfiltered `resolvedArtifacts` asks Gradle
    // to build every project artifact on the classpath, which would make this check compile
    // the module it is checking before it could report on it.
    val modulesOnly = incoming.artifactView {
        isLenient = true
        componentFilter { it is ModuleComponentIdentifier }
    }.artifacts.resolvedArtifacts
    resolvedStdlibs.addAll(
        modulesOnly.map { artifacts ->
            artifacts.mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier }
                .filter { it.group == "org.jetbrains.kotlin" && it.module.startsWith("kotlin-stdlib") }
                .map { "$configurationName ${it.module}:${it.version}" }
                .distinct()
                .sorted()
        },
    )
}

/**
 * Every configuration of this module with `canBeResolved = true`, as plain strings.
 *
 * Snapshotted in `afterEvaluate` so that classpaths created by plugins the module applies
 * itself — `java-test-fixtures`, KSP — are already present, and so that `canBeResolved` is
 * read after the creating plugin has finished setting it.
 */
val resolvableConfigurationNames: SetProperty<String> = objects.setProperty(String::class.java)

afterEvaluate {
    resolvableConfigurationNames.set(
        configurations.filter { it.isCanBeResolved }.map { it.name }.toSortedSet(),
    )
}

/**
 * Fails if this module resolves a `kotlin-stdlib` other than the catalog's Kotlin version,
 * or has a resolvable classpath nobody has classified.
 *
 * The second half is what makes the first half honest. The force above and the collection
 * above it both key on [UdeaStdlibPin.PINNED_CONFIGURATIONS], so the version check can only
 * ever inspect classpaths the pin already covers — it is structurally unable to see the
 * drift on any other one. `UdeaKotlinPin.coverageViolation` closes that by requiring every
 * resolvable configuration to be pinned, exempt, or a declared tool classpath, so the next
 * source set somebody adds has to be classified instead of escaping in silence, which is
 * exactly how `udea-codegen`'s tests came to resolve 2.3.20 under a 2.2.10 pin.
 *
 * The rule lives in [UdeaKotlinPin], where `UdeaKotlinPinTest` executes its failure paths;
 * a `doLast` block is not reachable from any test. Deleting the `eachDependency` pin above
 * makes this task red on `udea-core` (Fleks requests 2.3.21) and on `udea-codegen`
 * (KotlinPoet requests 2.3.20), which is how you can tell it is load-bearing rather than
 * decorative.
 */
val udeaVerifyKotlinPin by tasks.registering {
    group = "verification"
    description = "Fails if a module resolves a kotlin-stdlib other than the catalog Kotlin version."

    val stdlibs = resolvedStdlibs
    val resolvable = resolvableConfigurationNames
    val pinned = UdeaVersions.KOTLIN
    val projectPath = project.path
    // `project.path`, not the enclosing task block's `path`, which is the task's own path.
    val exemptions = UdeaStdlibPin.exemptionsFor(projectPath)
        .map { "${it.configuration}: ${it.reason}" }
        .sorted()
    val report = layout.buildDirectory.file("reports/udea/kotlin-stdlib-pin.txt")

    inputs.property("pinnedKotlinVersion", pinned)
    inputs.property("resolvedStdlibs", stdlibs)
    inputs.property("resolvableConfigurations", resolvable)
    inputs.property("exemptions", exemptions)
    outputs.file(report)

    doLast {
        val resolved = stdlibs.get().distinct().sorted()
        val unclassified = UdeaStdlibPin.unclassified(resolvable.get())
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                (
                    listOf("pinned=$pinned") + resolved + exemptions.map { "exempt $it" } +
                        unclassified.map { "unclassified $it" }
                    ).joinToString(separator = "\n", postfix = "\n"),
            )
        }
        // Coverage first: "this module has a classpath nobody pinned" is *why* the version
        // check can stay silent, so reporting the silence before the cause would send the
        // reader to the wrong place.
        UdeaKotlinPin.coverageViolation(projectPath, resolvable.get(), unclassified)
            ?.let { throw GradleException(it) }
        UdeaKotlinPin.violation(projectPath, pinned, resolved)?.let { throw GradleException(it) }
    }
}

tasks.named("check") {
    dependsOn(udeaVerifyKotlinPin)
}
