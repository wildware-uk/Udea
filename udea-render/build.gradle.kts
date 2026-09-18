import dev.wildware.udea.build.ModuleGraphRules
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

plugins {
    // Kotlin Multiplatform on `jvm` and `android` (issue #211). The convention states why the
    // other two targets are missing: Kool has no iOS backend (spec D2) and publishes no wasmJs
    // artifact yet (issue #223).
    id("udea.kotlin-multiplatform-render")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":udea-core"))
                implementation(project(":udea-assets"))

                // Kool draws, and nothing else in the engine may name it: UDEA-MG-002 bans
                // `de.fabmax.kool:*` from every headless module, and UDEA-MG-008 bans LibGDX from
                // this one. `implementation`, so a consumer's compile classpath carries no Kool
                // type - the public surface of this module is Udea's own (`SpriteBatch2D`,
                // `SpriteTexture`, `Rgba`), which is what lets `moba` and the agent host draw
                // without being able to reach past it.
                implementation(libs.kool.core)

                // `PresentationControl.capture` answers with a `Deferred`, the common-code future
                // (spec section 6: `java.util.concurrent` is replaced by coroutines). `api`
                // because the type is on this module's public surface.
                api(libs.kotlinx.coroutines.core)

                // The capture queue is the one structure another thread writes to: an agent's
                // HTTP thread submits, the render thread drains. atomicfu's `locks` API is an
                // ordinary class on every target, the same choice `udea-core` made for the
                // `SimBarrier` inbox (issue #203).
                implementation(libs.kotlinx.atomicfu)
            }
        }
        jvmTest {
            dependencies {
                // Test-only, deliberately. `udeaVerifyHeadless` reports through the one
                // UdeaDiagnostic (spec 5) so its output has the same rule ids, spans and cap as
                // every other producer; nothing shipped here needs diagnostics.
                // RenderModuleGraphTest asserts it stays test-only.
                implementation(project(":udea-diagnostics"))

                // Fleks worlds and a wired GameContext, so the pipeline tests drive the real
                // kernel rather than a mock of it.
                implementation(testFixtures(project(":udea-core")))

                // The bytecode gate. A class-file parser is a check, not a runtime feature.
                implementation(libs.asm)

                // The GL tests name Kool directly: they build scenes and read pixels back.
                implementation(libs.kool.core)
            }
        }
    }
}

/** The JVM test compilation every `Test` task below runs from. */
val jvmTestCompilation: KotlinCompilation<*> =
    (the<KotlinMultiplatformExtension>().targets.getByName("jvm") as KotlinJvmTarget)
        .compilations.getByName("test")

val jvmTestRuntime: FileCollection =
    files(jvmTestCompilation.output.allOutputs, jvmTestCompilation.runtimeDependencyFiles)

// --- udeaVerifyHeadless (issue #117) -----------------------------------------------------
//
// The bytecode half of the "no renderer in the kernel" rule. It EXTENDS `UDEA-MG-002`, the
// configuration-level rule owned by `udeaVerifyModuleGraph` in the build tooling, and does not
// restate it: that rule fails when a renderer *dependency* resolves onto a headless module's
// classpath, and this one fails when a compiled class *names* a renderer type, which is the case
// a configuration check structurally cannot see (a transitive type from an allowed jar, or a
// `compileOnly` dependency).
//
// It runs as a Test task rather than a bespoke one so that the scan itself has unit tests that can
// fail (`HeadlessScanTest`), which a `doLast` block would not.

/** Modules that must stay free of GL, read from the one place that decides it. */
val headlessModules: List<String> =
    ModuleGraphRules.HEADLESS_PROJECTS.map { it.removePrefix(":") }.sorted()

/** How [headlessModules] reaches `HeadlessScan`, which cannot see `build-logic`. */
val headlessModulesProperty: String = ModuleGraphRules.HEADLESS_MODULES_PROPERTY

/**
 * The compiled output the scan reads, narrowed to each module's shipped bytecode: `<lang>/main`
 * for a JVM module, `<lang>/jvm/main` and `<lang>/android/main` for a multiplatform one
 * (`RepoLayout.classFiles` reads the same three).
 */
val headlessModuleClasses = files(
    headlessModules.map { module ->
        fileTree(rootDir.resolve("$module/build/classes")) {
            include("*/main/**", "*/jvm/main/**", "*/android/main/**")
        }
    },
)

/**
 * Build scripts the module-graph tests read; without these they would be checked stale.
 * `settings.gradle.kts` is in here because `UdeaVerifyHeadlessTest` re-derives the designated
 * module set from it.
 */
val moduleBuildScripts = fileTree(rootDir) {
    include("udea-*/build.gradle.kts", "moba/build.gradle.kts", "settings.gradle.kts")
}

val gateTestClass = "dev.wildware.udea.render.headless.UdeaVerifyHeadlessTest"

val udeaVerifyHeadless = tasks.register<Test>("udeaVerifyHeadless") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails if any headless module's bytecode references a renderer type " +
        "(bytecode extension of UDEA-MG-002)."

    testClassesDirs = jvmTestCompilation.output.classesDirs
    classpath = jvmTestRuntime
    useJUnitPlatform()
    filter { includeTestsMatching(gateTestClass) }
    systemProperty(headlessModulesProperty, headlessModules.joinToString(","))

    dependsOn(headlessModules.map { ":$it:${ModuleGraphRules.MAIN_BYTECODE_TASK}" })
    inputs.files(headlessModuleClasses)
        .withPropertyName("headlessModuleClasses")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(moduleBuildScripts)
        .withPropertyName("moduleBuildScripts")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

val glTestPackage = "dev.wildware.udea.render.gl"

tasks.named<Test>("jvmTest") {
    // The gate is `udeaVerifyHeadless`'s job; running it twice per `check` buys nothing.
    filter { excludeTestsMatching(gateTestClass) }

    // The GL tests belong to `udeaGlTest`, in JVMs of their own - see that task.
    filter { excludeTestsMatching("$glTestPackage.*") }

    // HeadlessScanTest reads the same designated list, so it needs the same hand-off.
    systemProperty(headlessModulesProperty, headlessModules.joinToString(","))

    // HeadlessScanTest and RenderModuleGraphTest read the compiled output and the build scripts
    // of modules this one does not depend on.
    dependsOn(headlessModules.map { ":$it:${ModuleGraphRules.MAIN_BYTECODE_TASK}" })
    inputs.files(headlessModuleClasses)
        .withPropertyName("headlessModuleClasses")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(moduleBuildScripts)
        .withPropertyName("moduleBuildScripts")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// --- udeaGlTest (issues #118, #121, #211) ------------------------------------------------
//
// The tests that need a real Kool context, each test class in a JVM of its own.
//
// Two reasons, and the first is not a preference. Kool allows **one context per process**:
// `createContext` refuses a second call ("Context was already created") and GLFW's window
// subsystem keeps its primary window for the life of the JVM. A test class that starts a backend
// therefore owns its JVM, and `forkEvery = 1` is what gives it one. `PureSimulationTest`, which
// asserts that no Kool context exists, runs in `jvmTest` where no test ever creates one.
//
// The second: a driver that segfaults takes down a JVM that contains only the tests that asked
// for a driver.

val udeaGlTest = tasks.register<Test>("udeaGlTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the render tests that need a real Kool context and a display."

    testClassesDirs = jvmTestCompilation.output.classesDirs
    classpath = jvmTestRuntime
    useJUnitPlatform()
    filter { includeTestsMatching("$glTestPackage.*") }
    forkEvery = 1

    // A machine with no display cannot run these, and they say so out loud and skip. Set this on
    // any CI job that *does* have one, so that a backend which quietly stops booting fails the
    // build instead of hiding behind a skip forever.
    systemProperty(
        "udea.render.requireGl",
        providers.gradleProperty("udea.render.requireGl").getOrElse("false"),
    )
    // Where the GL tests write the frames they read back, so a person can look at them.
    systemProperty(
        "udea.render.glReportDir",
        layout.buildDirectory.dir("reports/udea/gl").get().asFile.absolutePath,
    )
}

tasks.named("check") {
    dependsOn(udeaVerifyHeadless, udeaGlTest)
}
