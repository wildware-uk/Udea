package dev.wildware.udea.build

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * The targets a multiplatform runtime module of Udea compiles for (spec section 3, issue #201).
 *
 * Shared by the two conventions so that "the render variant is the runtime set without iOS" is
 * true by construction rather than by two lists somebody keeps in step.
 */
internal object UdeaMultiplatform {

    /**
     * Every target name the two conventions can declare, plus `metadata`, the compilation of
     * common code the Kotlin plugin adds to every multiplatform module. `android` is the name
     * AGP's multiplatform library plugin gives its target.
     */
    private val TARGET_NAMES = listOf("jvm", "android", "wasmJs", "iosArm64", "iosSimulatorArm64", "metadata")

    /**
     * `<target><compilation?><kind>`: the classpath names the Kotlin plugin and AGP give a
     * target's compilations - `jvmRuntimeClasspath`, `androidHostTestCompileClasspath`,
     * `iosArm64TestCompileKlibraries`. Anchored to the target names above, so a JVM module's own
     * source-set classpaths (`agentCompileClasspath` on `moba`) never match.
     */
    private val TARGET_CLASSPATH = Regex(
        "^(?:${TARGET_NAMES.joinToString("|")})(Main|HostTest|TestFixtures|Test)?" +
            "(CompileClasspath|RuntimeClasspath|CompileKlibraries)$",
    )

    /**
     * The JVM classpath a multiplatform one stands for, or `null` when [configurationName] is not
     * a target's compile or runtime classpath (issue #201).
     *
     * The classpath gates - the module graph, the old-tree ban, the stdlib pin - name what they
     * govern as a JVM module spells it. A multiplatform module has a copy of each per target, and
     * every copy is governed the same way: a GL backend on `wasmJsRuntimeClasspath` is the same
     * defect as one on `runtimeClasspath`. A Kotlin/Native target has no runtime classpath, and
     * its `CompileKlibraries` stands for `compileClasspath`.
     */
    fun jvmRole(configurationName: String): String? {
        val match = TARGET_CLASSPATH.matchEntire(configurationName) ?: return null
        val (compilation, kind) = match.destructured
        val classpath = if (kind == "RuntimeClasspath") "RuntimeClasspath" else "CompileClasspath"
        return when (compilation) {
            "", "Main" -> classpath.replaceFirstChar { it.lowercase() }
            "Test", "HostTest" -> "test$classpath"
            else -> "testFixtures$classpath"
        }
    }

    /**
     * Declares the targets on [project], which must already have the Kotlin multiplatform plugin
     * and AGP's multiplatform library plugin applied.
     *
     * @param ios whether to add `iosArm64` and `iosSimulatorArm64`. `false` only for the render
     *   variant: Kool has no iOS backend (spec D2), so the module that draws cannot target iOS
     *   until it has one. Every other runtime module builds and tests for iOS.
     */
    fun configure(project: Project, ios: Boolean) {
        val kotlin = project.extensions.getByType<KotlinMultiplatformExtension>()

        kotlin.jvm()

        // Node rather than a browser: logic tests run on Node (spec section 7's table). A module
        // that needs a browser for its tests adds `browser()` itself.
        kotlin.wasmJs { nodejs() }

        if (ios) {
            // No `iosX64`: spec section 3 names the two ARM targets.
            kotlin.iosArm64()
            kotlin.iosSimulatorArm64()
        }

        // AGP registers `androidLibrary` on the Kotlin extension; it is the Android target.
        (kotlin as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget> {
            namespace = androidNamespace(project.path)
            compileSdk = project.udeaCatalogVersion("androidCompileSdk").toInt()
            minSdk = project.udeaCatalogVersion("androidMinSdk").toInt()
            // Host tests - the Android compilation run on the development JVM - are what makes
            // `commonTest` run against the Android variant at all. They are off by default.
            withHostTestBuilder { }
        }

        kotlin.sourceSets.getByName("commonTest").dependencies {
            implementation(project.udeaLibrary("kotlin-test"))
        }
        kotlin.sourceSets.getByName("jvmTest").dependencies {
            implementation(project.udeaLibrary("junit5-jupiter"))
            runtimeOnly(project.udeaLibrary("junit5-platform-launcher"))
        }

        // The JVM leg runs on JUnit 5 like every `udea.kotlin-library` module. Only `jvmTest`:
        // Android host tests are also a `Test` task, and their runner is AGP's to choose.
        project.tasks.named<Test>("jvmTest") { useJUnitPlatform() }

        // Both targets that compile to bytecode; wasm and iOS produce klibs.
        project.tasks.register(ModuleGraphRules.MAIN_BYTECODE_TASK) {
            description = "Compiles the bytecode this module's main code ships as, for the JVM and Android."
            dependsOn("jvmMainClasses", "compileAndroidMain")
        }
    }

    /**
     * The Android namespace for a Gradle path: `:udea-annotations` is
     * `dev.wildware.udea.annotations`, `:moba:game` is `dev.wildware.udea.moba.game`.
     *
     * AGP requires every library in an app to have a distinct one.
     */
    fun androidNamespace(projectPath: String): String =
        projectPath.split(':', '-')
            .filter { it.isNotEmpty() && it != "udea" }
            .joinToString(separator = ".", prefix = "dev.wildware.udea.")
}

/** A `[versions]` entry of the catalog, failing loudly when it is absent. */
private fun Project.udeaCatalogVersion(alias: String): String =
    udeaCatalog.findVersion(alias).orElseThrow {
        IllegalStateException("No version '$alias' in gradle/libs.versions.toml.")
    }.requiredVersion
