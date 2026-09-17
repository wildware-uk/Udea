import com.google.devtools.ksp.gradle.KspAATask
import dev.wildware.udea.build.UdeaModuleRegistry
import dev.wildware.udea.build.udeaModule
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    // THE iOS SWITCH (issue #215), the same one `udea-core` carries. Multiplatform on jvm, android
    // and wasmJs, and not iOS, because `udea-core` - an `api` dependency below - has no iOS
    // variant while Fleks publishes none. When `udea-core` switches to
    // `id("udea.kotlin-multiplatform")`, this line switches with it.
    id("udea.kotlin-multiplatform-no-ios")
    // The engine's own toolsets go through the same `@AgentTool` KSP pass every game's do.
    // There is one mechanism on the agent surface, not an engine one and a game one - see
    // `EngineToolModules` for what that took and for the one thing it deliberately does not do.
    id("com.google.devtools.ksp") version libs.versions.ksp.get()
}

// What is common and what is JVM (issue #208, spec D8). The tools, the dispatcher, the bridge, the
// digest and the harness are `commonMain`: a game on any platform can be driven in-process. The
// `assets.*` toolset and the daemon threads are `jvmMain`: the toolset compiles against the warm
// asset daemon, which carries the Kotlin compiler and is build-time JVM only, and the thread
// factory exists for the JVM HTTP host (`udea-agent-host`, which stays JVM).
kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            // The few `expect` declarations whose `actual`s split along the JVM line: the heap
            // figures `diag.memory` reports and an enum's constants, both of which the JVM and
            // Android runtimes can answer and Wasm cannot.
            group("jvmAndAndroid") {
                withCompilations {
                    it.target.platformType == KotlinPlatformType.jvm || it.target.platformType == KotlinPlatformType.androidJvm
                }
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":udea-core"))

                // `@AgentTool` and `@Arg`, on the toolsets in `tools/`. `compileOnly` is not an
                // option: the annotations are BINARY-retained, so they are on this module's own
                // bytecode, and a consumer compiling against `WorldToolset` needs them resolvable.
                implementation(project(":udea-annotations"))

                // The stable rule ids, so the runtime description gate in `ToolIndex.Builder.build`
                // reports under exactly the id the KSP checker and the K2 checker report under
                // (spec 5: one defect, one name, wherever it surfaces). `implementation` and not
                // `api`: nothing in this module's public surface names a `UdeaRule`, only its
                // refusal messages quote one.
                implementation(project(":udea-diagnostics"))

                // The runtime asset model, for `AssetHotReload`: a GraphDelta applied to an
                // AssetRegistry through the SimBarrier. `udea-assets` is a leaf (UDEA-MG-006 keeps
                // it one), so this costs a shipped game nothing beyond plain data classes.
                implementation(project(":udea-assets"))

                // The locks around the rings and queues an HTTP thread writes and the simulation
                // thread drains - the same lock `udea-core`'s `SimBarrier` uses (issue #203).
                implementation(libs.kotlinx.atomicfu)

                // Where `editor.save` writes a level file (issue #193): a kotlinx-io `Path` through
                // `SystemFileSystem`, so the save is common code rather than a JVM-only toolset.
                // `api`, because `EditorLevelStore`'s constructor names that `Path`, and a host
                // building one has to resolve it. The same library `udea-assets` reads a
                // `.udeapak` through (issue #205), so it adds no new artifact to a game.
                api(libs.kotlinx.io.core)
            }
            // The tool definitions, manifest fragment and registry KSP generates from the common
            // source set, compiled into every target from there. See the KSP wiring below.
            kotlin.srcDir(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
            // The manifest fragment, built by the common KSP run - which every target's resource
            // processing therefore has to wait on.
            resources.srcDir(
                files(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/resources"))
                    .builtBy("kspCommonMainKotlinMetadata"),
            )
        }
        jvmMain {
            dependencies {
                // The warm daemon behind the `assets` toolset, **compileOnly** and deliberately so.
                //
                // `udea-assets-compiler` carries kotlin-compiler-embeddable and the scripting
                // host, and `UDEA-MG-005` forbids `kotlin-scripting-*` on `:moba`'s runtime
                // classpath - the shipped game compiles no scripts, which is the entire point of
                // spec 3.6. A plain `implementation` here would put it there through `udea-agent`
                // and fail that gate.
                //
                // So `AssetsToolset` and `AssetToolModule` compile against the daemon and are
                // simply unloadable in a process that has no daemon on its classpath. That is the
                // correct behaviour rather than a compromise: only the `udeaDev` daemon and a dev
                // host serve these tools, and `EngineToolModules` deliberately does not name them,
                // so nothing a shipped game touches can reach the missing classes.
                // `AssetToolSurfaceTest` runs with the daemon present.
                compileOnly(project(":udea-assets-compiler"))
            }
        }
    }
}

dependencies {
    // `LatencyBudget`, the contention note the two budget tests below end their failure
    // messages with (issue #175). Test scope, and `udea-diagnostics` is the zero-dependency
    // leaf, so this adds the fixture and nothing else.
    "jvmTestImplementation"(testFixtures(project(":udea-diagnostics")))

    // Real Fleks components on real entities, a wired GameContext, and the ArrayFieldStore /
    // ArrayBitWriter pair, so the hand-written test replicators can implement the whole frozen
    // contract rather than only the two methods the agent surface calls.
    "jvmTestImplementation"(testFixtures(project(":udea-core")))
    // Compile-time only, and that is the whole point. `AgentModuleBoundaryTest` scans the test
    // JVM's own classpath and bans `kotlin-scripting-*` from it, because this module is compiled
    // into every game and anything on its classpath is on the game's. A `jvmTestImplementation`
    // here would put the scripting host on `jvmTestRuntimeClasspath` and break that gate rather
    // than satisfy it. The asset-toolset tests get the daemon at run time from their own Test
    // task below, whose classpath is assembled separately and never becomes this module's.
    "jvmTestCompileOnly"(project(":udea-assets-compiler"))

    // The processor over *this module's* common source set, once, rather than once per target:
    // every engine toolset is common code, so one generated copy compiled everywhere cannot
    // disagree between targets about what a tool is called or what it says. The reverse edge
    // exists too - `udea-codegen`'s tests compile against `udea-agent` so the generated code they
    // exercise is dispatched through the real `ToolIndex` - and the two do not form a task cycle:
    // `:udea-codegen:compileKotlin` needs nothing from here.
    add("kspCommonMainMetadata", project(":udea-codegen"))

    // And over `jvmMain`, for the one toolset that cannot be common: `AssetsToolset` compiles
    // against the warm asset daemon. KSP hands this run the common sources too, so it is scoped
    // to its own source set below, or it would generate every engine tool and the registry a
    // second time and the JVM compilation would fail on the redeclarations.
    add("kspJvm", project(":udea-codegen"))
}

// Every compilation reads the generated code, and so does each per-target KSP run the plugin
// registers beside the common one, so none of them may start before it has been written.
val kspCommonMain = "kspCommonMainKotlinMetadata"
tasks.withType<KotlinCompilationTask<*>>().configureEach { dependsOn(kspCommonMain) }
tasks.matching { it.name.startsWith("ksp") && it.name != kspCommonMain }.configureEach { dependsOn(kspCommonMain) }

// `udea.sourceSet` is `udea-codegen`'s `CodegenOptions.SOURCE_SET`: the JVM run processes only
// `src/jvmMain/` and writes no registry and no manifest, which the common run already wrote.
//
// Declared as an input as well, because the option on its own is not one: measured on this module,
// removing it left `kspKotlinJvm` UP-TO-DATE, and even FROM-CACHE, holding the other setting's
// generated files.
tasks.withType<KspAATask>().matching { it.name == "kspKotlinJvm" }.configureEach {
    kspConfig.processorOptions.put("udea.sourceSet", "jvmMain")
    inputs.property("udea.sourceSet", "jvmMain")
}

// --- the agent surface's own codegen ---------------------------------------------------------
//
// The module's name and launcher list are the only options set, and the omissions are the design:
//
// - **no `udea.toolModuleService`**, so `UdeaAgentModuleRegistry` does not implement `ToolModule`
//   for the engine's toolsets. `ToolIndex.Builder.registry()` would then pick them up in *every*
//   game whose registry lists this module, and `build()` refuses a tool whose toolset instance was
//   never registered - so a host that wires two toolsets, and `udea-codegen`'s own fixture tests
//   which wire a `Playground` and nothing else, would fail at start-up. `EngineToolModules`
//   assembles the modules from the generated objects by hand instead, which is what lets a host
//   take four of the five.
// - **no `udea.stateModuleService`**, because this module declares no `@AgentState`.
// - **no `udea.projectComponents`**, because it declares no `@Replicated` component either, so
//   it mints no component type id and is not a participant in the wire contract. The processor
//   only demands the id space of a module that emits protocol identity.
//
// The tool manifest fragment IS emitted - `udea/UdeaAgent-agent-tools.json` - and
// `EngineToolSurfaceTest` reads it, so a reworded engine tool description is a reviewable diff
// exactly as it is for a game's own tools.
val udeaRegistry = udeaModule("UdeaAgent")
ksp {
    arg(UdeaModuleRegistry.MODULE_NAME_OPTION, udeaRegistry.name)
    arg(UdeaModuleRegistry.REGISTRY_MODULES_OPTION, udeaRegistry.registryModules)
}

// --- Phase 1 budget gate (spec 6, Phase 1 exit) ----------------------------------------------
//
// "digest <0.3ms at 500 entities" is an exit criterion, so it is a CI gate and not an advisory
// print, wired exactly like the Phase 0 budgets in `udea-core`: its own task on `check`, and
// excluded from `test` so a normal run does not pay for it twice. The numbers live in
// `DigestBudgets`, where moving one is a diff a reviewer sees.
//
// If it fails on slower hardware the remedy is `DigestBudgets.REBUILD_INTERVAL_TICKS` - build
// the digest less often - never a wider budget. A budget that moves when it is missed measures
// nothing.

// --- the asset toolset's tests run in their own JVM, on their own classpath ------------------
//
// `AssetsToolset` needs a real `AssetDaemon`, and a real daemon needs the Kotlin scripting host.
// That host may not be on this module's ordinary test classpath - `AgentModuleBoundaryTest` bans
// it there, and rightly, since udea-agent is compiled into every game. So the daemon reaches these
// tests through a configuration of their own and a Test task of their own, and never through
// `testRuntimeClasspath`.
//
// The `kotlin-reflect` force is not tidiness. `udea-assets-compiler` drags in kotlin-reflect at a
// version *newer* than the kotlin-stdlib every module is forced down to, and a newer reflect over
// an older stdlib is a `ClassNotFoundException: kotlin.jvm.internal.KotlinGenericDeclaration` from
// the first class whose initialiser touches reflection - which is `CoreModule`, so every test in
// the JVM dies with it. Left unforced it fails loudly here and silently anywhere else that ever
// combines the two.
val assetToolsRuntime: Configuration by configurations.creating {
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-reflect:${libs.versions.kotlin.get()}")
}

dependencies {
    assetToolsRuntime(project(":udea-assets-compiler"))
}

/** The tool names this task owns, excluded from `test` so they run once, in the right JVM. */
val assetToolTests = "dev.wildware.udea.agent.assets.*"

/** The JVM target's test compilation: where every JVM test here, the budgets included, is compiled. */
val jvmTestCompilation: KotlinCompilation<*> =
    (the<KotlinMultiplatformExtension>().targets.getByName("jvm") as KotlinJvmTarget).compilations.getByName("test")

/** What `jvmTest` runs on. `udeaAssetTools` adds the daemon to it; the budgets take it as is. */
val jvmTestRuntime: FileCollection =
    files(jvmTestCompilation.output.allOutputs, jvmTestCompilation.runtimeDependencyFiles)

val udeaAssetTools = tasks.register<Test>("udeaAssetTools") {
    group = "verification"
    description = "The assets.* toolset against a real warm daemon and a real headless game."
    testClassesDirs = jvmTestCompilation.output.classesDirs
    classpath = jvmTestRuntime + assetToolsRuntime
    // `jvmTest` is on JUnit 5 by convention and a `Test` task registered here is not: left on
    // Gradle's default runner it finds no test and fails "No tests found for given includes".
    useJUnitPlatform()
    filter.includeTestsMatching(assetToolTests)
}

tasks.named("check") {
    dependsOn(udeaAssetTools)
}

// --- the asset toolset's tests drive a real daemon --------------------------------------------
//
// `AssetDaemon` takes a repo root, an asset root and a script compile classpath as arguments
// rather than reading its own environment - that is the property that lets one implementation
// serve the Gradle task and the daemon - so a test JVM has to be told all three, exactly as
// `udea-assets-compiler`'s own tests are. Handing the tests a *fake* daemon instead would leave
// the toolset's whole reason for existing (a warm compiler answering in under 300ms) untested.
tasks.withType<Test>().configureEach {
    systemProperty("udea.repoRoot", rootProject.layout.projectDirectory.asFile.absolutePath)
    // **This task's own** classpath, not `jvmTest`'s. The two differ for
    // `udeaAssetTools`, which is the only task that has a daemon at all, and a script compiled
    // against the wrong one fails with "Unresolved reference 'spriteSheet'" - the DSL receiver
    // missing from the classpath the scripts are compiled against.
    doFirst {
        systemProperty("udea.assetsCompiler.classpath", classpath.asPath)
    }
}

val budgetTestClasses = listOf(
    "dev.wildware.udea.agent.state.DigestBudgetTest",
    "dev.wildware.udea.agent.query.EntityQueryBudgetTest",
)

tasks.named<Test>("jvmTest") {
    budgetTestClasses.forEach { filter.excludeTestsMatching(it) }
    // Not a disabled test: `udeaAssetTools` runs every one of these, on `check`, in a JVM whose
    // classpath carries the daemon. Running them here as well would put the scripting host on this
    // task's classpath, which is exactly what `AgentModuleBoundaryTest` exists to forbid.
    filter.excludeTestsMatching(assetToolTests)
}

/** The digest under 0.3ms at 500 entities, and allocating nothing but the document. */
tasks.register<Test>("udeaDigestBudget") {
    group = "verification"
    description = "Gates the Tier-0 digest at 500 entities: <0.3ms median, zero render allocation."
    testClassesDirs = jvmTestCompilation.output.classesDirs
    classpath = jvmTestRuntime
    useJUnitPlatform()
    filter.includeTestsMatching("dev.wildware.udea.agent.state.DigestBudgetTest")
    // The measured numbers are the point of the task, so they go to the build log rather than
    // into a report nobody opens.
    testLogging.showStandardStreams = true
}

/** A query over 500 entities returning 20, under 1ms and with bounded allocation. */
tasks.register<Test>("udeaQueryBudget") {
    group = "verification"
    description = "Gates entity query at 500 entities returning 20: <1ms median, bounded allocation."
    testClassesDirs = jvmTestCompilation.output.classesDirs
    classpath = jvmTestRuntime
    useJUnitPlatform()
    filter.includeTestsMatching("dev.wildware.udea.agent.query.EntityQueryBudgetTest")
    testLogging.showStandardStreams = true
}

// No `check` wiring. Both are wall-clock measurements, so they hang off the root's
// `udeaLatencyBudgets` and are taken by the `latency-budgets` CI job with the runner to itself
// (issue #175). They were not among the gates that job was built for - they had not failed - and
// they are the same class of thing, which is the whole reason they are here: the root's list says
// "every gate in this repository that asserts a number of milliseconds", and a list that quietly
// excludes two is a list nobody can trust.

