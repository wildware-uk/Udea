import dev.wildware.udea.build.ModuleGraphRules
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

plugins {
    // Kotlin Multiplatform on `jvm` and `android` (issue #211). The convention states why the
    // other two targets are missing: Kool has no iOS backend (spec D2) and publishes no wasmJs
    // artifact yet (issue #223).
    id("udea.kotlin-multiplatform-render")

    // The interface is ComposeGL, so this module compiles `@Composable` (spec D5, issue #224).
    // Applied here and in no convention: this is the only module that hosts a UI backend, and a
    // convention would put the plugin on every module that has no composable in it.
    alias(libs.plugins.composeCompiler)
}

kotlin {
    androidLibrary {
        // `AndroidKeyTableTest` reads Kool's Android key map out of `PlatformInputAndroid`, whose
        // static initialiser also builds a `MotionEvent.PointerCoords`. Android's mockable jar throws
        // from every framework method by default; returning defaults lets that class load, and the
        // map it builds is plain Kotlin (issue #228).
        compilations.withType<com.android.build.api.dsl.KotlinMultiplatformAndroidHostTestCompilation>()
            .configureEach { isReturnDefaultValues = true }
    }
    sourceSets {
        commonMain {
            dependencies {
                api(project(":udea-core"))
                // `api` since issue #228: a binding names its keys with the asset model's `InputKey`
                // (`ActionBinding.keys`, `KeyboardState.isKeyDown`), so a caller needs the type.
                api(project(":udea-assets"))

                // The `AudioDevice` SPI `KoolAudioDevice` implements (issue #221). `udea-audio`
                // owns the drain, the routing and the SPI and names no Kool type (UDEA-MG-002 bans
                // it there); this module owns the Kool half. `api`, because `koolAudioDevice`
                // returns an `AudioDevice`, so a caller needs the SPI on its own classpath.
                api(project(":udea-audio"))

                // Kool draws, and nothing else in the engine may name it: UDEA-MG-002 bans
                // `de.fabmax.kool:*` from every headless module, and UDEA-MG-009 bans LibGDX from
                // every project. `implementation`, so a consumer's compile classpath carries no Kool
                // type - the public surface of this module is Udea's own (`SpriteBatch2D`,
                // `SpriteTexture`, `Rgba`), which is what lets `moba` and the agent host draw
                // without being able to reach past it.
                implementation(libs.kool.core)

                // ComposeGL, in two halves that are on opposite sides of UDEA-MG-002 (issue #224).
                //
                // `composegl-ui` is the toolkit - nodes, modifiers, widgets, input events - and it
                // names no GL. `api`, because a game writes its own screens: `UiScreen.content` is
                // `@Composable`, and moba cannot implement one without these types on its compile
                // classpath. The module-graph rule allows exactly that and bans every frontend.
                api(libs.composegl.ui)

                // `composegl-kool` is a frontend: it draws that tree through Kool's GL context.
                // `implementation`, so it stops here - the same reason `kool-core` does.
                implementation(libs.composegl.kool)

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
        jvmMain {
            dependencies {
                // stb_truetype, for `DesktopFonts`. A ComposeGL frontend artefact, so `udea-render`
                // only (UDEA-MG-002), and JVM only: Android rasterises with its own `Typeface`
                // inside `composegl-kool`, which is why `DesktopFonts` is not in `commonMain`.
                implementation(libs.composegl.lwjgl3)
            }
        }
        jvmTest {
            dependencies {
                // Test-only, deliberately. `udeaVerifyHeadless` reports through the one
                // UdeaDiagnostic (spec 5) so its output has the same rule ids, spans and cap as
                // every other producer; nothing shipped here needs diagnostics.
                // RenderModuleGraphTest asserts it stays test-only.
                implementation(project(":udea-diagnostics"))

                // The bytecode gate. A class-file parser is a check, not a runtime feature.
                implementation(libs.asm)

                // The GL tests name Kool directly: they build scenes and read pixels back. It is
                // also what puts LWJGL's GL binding on this classpath, which `GlFixtures` names as
                // the headless bytecode gate's positive control.
                implementation(libs.kool.core)
            }
        }
    }
}

dependencies {
    // `testFixtures(...)` is a `DependencyHandler` extension and is not visible inside the
    // `kotlin.sourceSets { jvmTest { dependencies { ... } } }` block above (its scope is
    // `KotlinDependencyHandler`), so it is wired here by bucket name, the same pattern
    // `udea-net`, `udea-replay`, `udea-gas` and `udea-agent` use for the identical edge.
    //
    // Fleks worlds and a wired GameContext, so the pipeline tests drive the real kernel rather
    // than a mock of it.
    "jvmTestImplementation"(testFixtures(project(":udea-core")))
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

// --- udeaVerifyNoLibGdx (issue #189) -----------------------------------------------------
//
// The bytecode half of `UDEA-MG-009`: no module's main bytecode may name LibGDX, scene2d above
// all. `UDEA-MG-009` matches artifact coordinates, so it cannot see a LibGDX class that arrived
// without one - vendored source, a `files(...)` jar - and this reads the class files instead.
// Unlike `udeaVerifyHeadless` it covers every project, `udea-render` included: `LibGdxScan`.

/**
 * Every project with a build script, as a repository-relative directory: `udea-*` and the game.
 * `UdeaVerifyNoLibGdxTest` checks it against `settings.gradle.kts`, so a module cannot drop out.
 */
val libGdxScanModules: List<String> = rootProject.subprojects
    .filter { it.projectDir.resolve("build.gradle.kts").isFile }
    .map { it.path.removePrefix(":").replace(':', '/') }
    .sorted()

/** Must equal `LibGdxScan.MODULES_PROPERTY`; a mismatch throws rather than scanning nothing. */
val libGdxModulesProperty = "udea.libgdx.modules"

val libGdxGateTestClass = "dev.wildware.udea.render.libgdx.UdeaVerifyNoLibGdxTest"

val udeaVerifyNoLibGdx = tasks.register<Test>("udeaVerifyNoLibGdx") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails if any module's bytecode references LibGDX, scene2d included " +
        "(bytecode extension of UDEA-MG-009)."

    testClassesDirs = jvmTestCompilation.output.classesDirs
    classpath = jvmTestRuntime
    useJUnitPlatform()
    filter { includeTestsMatching(libGdxGateTestClass) }
    systemProperty(libGdxModulesProperty, libGdxScanModules.joinToString(","))

    dependsOn(libGdxScanModules.map { ":${it.replace('/', ':')}:${ModuleGraphRules.MAIN_BYTECODE_TASK}" })
    inputs.files(
        libGdxScanModules.map { module ->
            fileTree(rootDir.resolve("$module/build")) {
                include("classes/*/main/**", "classes/*/jvm/main/**", "classes/*/android/main/**")
                include("tmp/kotlin-classes/release/**")
            }
        },
    ).withPropertyName("libGdxScanClasses").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("settings.gradle.kts"))
        .withPropertyName("settings")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

val glTestPackage = "dev.wildware.udea.render.gl"

/** The retired game's asset tree, not a project: the imported-model test and shot read its models. */
val exampleAssets: Directory = rootProject.layout.projectDirectory.dir("example-assets")
val exampleModels: Directory = exampleAssets.dir("models")

tasks.named<Test>("jvmTest") {
    // The gate is `udeaVerifyHeadless`'s job; running it twice per `check` buys nothing.
    filter { excludeTestsMatching(gateTestClass) }
    filter { excludeTestsMatching(libGdxGateTestClass) }

    // The GL tests belong to `udeaGlTest`, in JVMs of their own - see that task.
    filter { excludeTestsMatching("$glTestPackage.*") }

    // HeadlessScanTest reads the same designated list, so it needs the same hand-off.
    systemProperty(headlessModulesProperty, headlessModules.joinToString(","))

    // `SkinnedPoseTest` poses the committed Khronos Fox's skin without a GL context (issue #242).
    systemProperty("udea.render.exampleAssets", exampleAssets.asFile.absolutePath)
    inputs.dir(exampleModels).withPropertyName("exampleModels").withPathSensitivity(PathSensitivity.RELATIVE)

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
    // The asset root holding the committed Khronos Fox, which `GlImportedModelRenderTest` imports
    // (issue #240). An input, so replacing the file re-runs the test that reads it.
    systemProperty("udea.render.exampleAssets", exampleAssets.asFile.absolutePath)
    inputs.dir(exampleModels).withPropertyName("exampleModels").withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.named("check") {
    dependsOn(udeaVerifyHeadless, udeaVerifyNoLibGdx, udeaGlTest)
}

// --- runModelShot ------------------------------------------------------------------------
//
// Pictures of textured, lit 3D models drawn by `ModelRenderSystem`, captured from the same pass an
// agent's screenshot reads. Run by name and never by `check`: it needs a GL driver, and in `check`
// a missing driver would have to be a skip, which hides the failure it exists to show. The
// assertions about the same path are `GlModelRenderTest`, which `udeaGlTest` runs.
tasks.register<JavaExec>("runModelShot") {
    group = "udea"
    description = "Captures textured, lit 3D models to -Pudea.modelshot.dir (default build/reports/udea/model)."
    mainClass.set("dev.wildware.udea.render.model.ModelShot")
    classpath = jvmTestRuntime
    dependsOn(jvmTestCompilation.compileTaskProvider)
    systemProperty(
        "udea.modelshot.dir",
        providers.gradleProperty("udea.modelshot.dir").orNull
            ?: layout.buildDirectory.dir("reports/udea/model").get().asFile.absolutePath,
    )
    // Where the Khronos Fox the shot imports is (issue #240).
    systemProperty("udea.render.exampleAssets", exampleAssets.asFile.absolutePath)
}
