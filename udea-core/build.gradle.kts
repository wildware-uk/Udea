import dev.wildware.udea.build.UdeaModuleRegistry
import dev.wildware.udea.build.registerNetProtocolLock
import dev.wildware.udea.build.udeaModule
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    // Every target, iOS included (issue #215). Fleks publishes no iOS variant at any version, so
    // it is built from source in `udea-fleks` rather than resolved from Maven; with the Maven
    // artifact back, the iOS targets here fail dependency resolution on every machine, Linux too.
    id("dev.wildware.udea.kotlin-multiplatform")
    // The Replicator contract ships an executable specification: TransformReplicator and
    // ArrayFieldStore. udea-codegen's golden tests consume them, so they have to be a
    // published variant rather than this module's private test source (issue #28 scope).
    // `udea.jvm-test-fixtures` rather than Gradle's `java-test-fixtures`, which cannot be applied
    // beside the multiplatform plugin; every consumer's `testFixtures(project(":udea-core"))` is
    // unchanged (issue #203).
    id("udea.jvm-test-fixtures")
    // Level files (issue #191). The serialization plugin gives this module's components their
    // serializers, and KSP runs `udea-codegen` over them to generate `CoreModuleRegistry`, whose
    // level-component list a level file's polymorphic component section is built from.
    alias(libs.plugins.kotlinSerialization)
    id("com.google.devtools.ksp") version libs.versions.ksp.get()
}

// Declares this module to every launcher that has it on its runtime classpath (issue #202), and
// hands the processor the list its own `CoreUdeaRegistry` names - the kernel alone.
val udeaRegistry = udeaModule("Core")

// `udea.projectComponents` - the project-wide `@Replicated` id space, which `udea-core` joined
// with the physics components (`PhysicsBody` and the shapes, every field `@Sim`) so that a
// snapshot carries physics state at all - is **not** here. `udea.kotlin-base` reads the reviewed
// `net-components.lock` and passes it to every module that runs KSP, because this block used to
// be eight copy-pasted lines per module and a game outside this repository had no copy to make
// (issue #274).
ksp {
    arg(UdeaModuleRegistry.MODULE_NAME_OPTION, udeaRegistry.name)
    arg(UdeaModuleRegistry.REGISTRY_MODULES_OPTION, udeaRegistry.registryModules)
}

kotlin {
    // Two shared source sets beside the default hierarchy, for the few `expect` declarations whose
    // `actual`s split along the JVM line rather than per target: `jvmAndAndroidMain` reaches
    // `java.lang`, and `nonJvmMain` (Wasm and native) has only
    // Kotlin's own reflection.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmAndAndroid") {
                // By platform type rather than `withJvm()` and `withAndroidTarget()`: AGP's
                // multiplatform library target is not the `androidTarget()` the latter matches.
                withCompilations {
                    it.target.platformType == KotlinPlatformType.jvm || it.target.platformType == KotlinPlatformType.androidJvm
                }
            }
            group("nonJvm") {
                withWasmJs()
                withNative()
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":udea-annotations"))

                // `api`: `Drawn`, the simulation's "which model does this entity draw" (issue
                // #270), names a `Ref<Model>` and an `AssetRegistry` in its constructors - a
                // game's blueprints spawn parts and say what each one looks like, and that has
                // to be sayable from the simulation rather than from the renderer. A downward
                // arrow: `udea-assets` is plain data with no Fleks, no GL and the same target
                // set as this module, so the headless guarantee is untouched (both are in
                // `ModuleGraphRules.HEADLESS_PROJECTS`). What crosses the line is asset
                // *identity* - an `AssetIndex` slot - which spec 3.6 already makes the only
                // asset identity a snapshot may carry.
                api(project(":udea-assets"))

                // `api`: `LevelComponent` names a `KSerializer`, and every module that declares a
                // saved component compiles `@Serializable` against it. CBOR is the level encoding
                // and nothing outside `dev.wildware.udea.core.level` names it, so it stays
                // `implementation`.
                api(libs.kotlinx.serialization.core)
                implementation(libs.kotlinx.serialization.cbor)

                // `api`, not `implementation`: SimSystem extends Fleks' IntervalSystem and
                // NetIdIndex resolves to a Fleks Entity, so both are part of udea-core's public
                // surface. Fleks is headless - this does not put GL on anyone's classpath (spec 4).
                // Fleks' own source, vendored (issue #215): see `udea-fleks/NOTICE.md`.
                api(project(":udea-fleks"))

                // The lock around `SimBarrier`'s inbox, the one queue another thread writes to.
                implementation(libs.kotlinx.atomicfu)
            }
            // The registry KSP generates from the common source set (issue #202), compiled into
            // every target from there. See the `kspCommonMainMetadata` wiring below.
            kotlin.srcDir(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
        }
    }
}

dependencies {
    // In this block by their `jvmTest` bucket names rather than inside `kotlin.sourceSets`, so each
    // test-only edge is visibly one on its own line - which is what `NoReflectiveRegistrationTest`
    // reads this file for.

    // ReplicatorApiShapeTest asserts the frozen signature exposes FieldMask and never a raw Long.
    // JVM erasure hides a value class, so the check has to run on Kotlin's reflection.
    "jvmTestImplementation"(kotlin("reflect"))

    // `LatencyBudget`: the contention note every budget test in `budgetTestClasses` below ends its
    // failure message with, and the `measuredBy` guard each one opens with, which refuses to let a
    // budget be measured by any task but its own (issues #175 and #182). A test-scope edge to the
    // zero-dependency leaf: it adds the fixture and nothing else, and it puts no GL, no gdx and no
    // new runtime dependency anywhere near the headless kernel.
    "jvmTestImplementation"(testFixtures(project(":udea-diagnostics")))

    // KSP over the common source set, once, rather than once per target: the registry it writes
    // names only common declarations, and one copy compiled everywhere cannot disagree between
    // targets about which components a level file lists.
    add("kspCommonMainMetadata", project(":udea-codegen"))
}

// Every compilation reads the generated registry, and so does each per-target KSP run the plugin
// registers beside the common one, so none of them may start before it has been written.
val kspCommonMain = "kspCommonMainKotlinMetadata"
tasks.withType<KotlinCompilationTask<*>>().configureEach { dependsOn(kspCommonMain) }
tasks.matching { it.name.startsWith("ksp") && it.name != kspCommonMain }.configureEach { dependsOn(kspCommonMain) }

/**
 * `udea-core` emits protocol identity since the physics components became `@Replicated`, so it
 * gets the reviewed lock gate `:udea-codegen` and `:moba:game` have: `udeaCheckProtocolLock` on
 * `check`, and `udeaWriteProtocolLock` to rewrite `net-protocol.lock` deliberately.
 */
registerNetProtocolLock(
    generatedLock = layout.buildDirectory.file(
        "generated/ksp/metadata/commonMain/resources/udea/${udeaRegistry.name}-net-protocol.lock",
    ),
    producingTask = kspCommonMain,
)

// --- Phase 0 budget gates (spec 6 exit criteria, spec 7 risk row) -----------------------------
//
// These are hard CI gates, not aspirations: one structure carries time travel, replication
// baselines and rollback, so a capture that allocates degrades three features at once. Each is
// excluded from `jvmTest` so a normal test run does not pay for it twice.
//
// They hang off the root's `udeaLatencyBudgets` and no longer off `check` (issue #175). Every
// number here is a wall-clock duration, and a wall-clock duration measured while the other
// nineteen modules compile is a measurement of the build: `udeaBenchCharacterMover` medians
// 2.0-2.2ms alone against a 4.0ms budget on this box and blows straight through it inside a
// parallel `build`. The root build script carries the full reasoning, including why this is not
// the same thing as switching the gates off - they are measured on both runner images on every
// push, by a CI job that has the runner to itself.
//
// The documented remedy when one fails on slower hardware is `SnapshotRing.degrade()` — raise
// `sparseInterval`, keeping the full sixty-second rewind window at lower keyframe density.
// Never loosen a number in `SnapshotBudgets` and never disable a task here.

/**
 * The `CoreModule` system-order golden pins which systems run and in what order, so it has to
 * be regenerable on purpose and never by accident. `./gradlew :udea-core:jvmTest
 * -Dupdate.goldens=true` rewrites it; without the flag an order change is a failing diff.
 *
 * Gradle has no `--update-goldens` option for a plain `Test` task, so the flag is a system
 * property, matching `:udea-net`. `udea.projectDir` gives the test the source path to rewrite,
 * which the classpath alone cannot provide.
 */
val updateGoldens: Provider<String> = providers.systemProperty("update.goldens").orElse("false")

tasks.withType<Test>().configureEach {
    systemProperty("udea.projectDir", projectDir.absolutePath)
    systemProperty("update.goldens", updateGoldens.get())
}

/**
 * The sibling module sources `ReplicatorApiShapeTest` reads, declared as inputs.
 *
 * Found the hard way: adding `private val cached: FieldMask` to `udea-gas`'s
 * `AttributesReplicator` left `:udea-core:test` UP-TO-DATE and the build green. No sibling
 * module is on this module's test classpath, so nothing else makes the task rerun - the rule
 * only fired when some unrelated change happened to invalidate it. A source rule that reads a
 * tree it has not declared is a rule that reports whatever it last saw.
 *
 * A glob rather than a `listFiles()` scan so a module added later is covered without anyone
 * remembering this line. `.kts` is deliberately not matched: the asset corpus under
 * `moba/game/assets` is not Kotlin the rule reads.
 */
val fieldMaskScanSources: ConfigurableFileTree = fileTree(rootProject.layout.projectDirectory) {
    include("udea-*/src/main/**/*.kt")
    include("udea-*/src/testFixtures/**/*.kt")
    // `moba` is three nested projects since issue #212 (`game`, `desktop`, `android`), so the
    // glob has a level in it that `udea-*` does not.
    include("moba/*/src/main/**/*.kt")
    include("moba/*/src/testFixtures/**/*.kt")
    // A multiplatform module's shipped source sets (issue #201): `commonMain`, `jvmMain`,
    // `jvmTestFixtures` and the like.
    include("udea-*/src/*Main/**/*.kt")
    include("udea-*/src/*TestFixtures/**/*.kt")
    include("moba/*/src/*Main/**/*.kt")
    include("moba/*/src/*TestFixtures/**/*.kt")
}

tasks.named<Test>("jvmTest") {
    inputs.files(fieldMaskScanSources)
        .withPropertyName("fieldMaskScanSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

/** The JVM target's test compilation: where the budget tests below are compiled. */
val jvmTestCompilation: KotlinCompilation<*> =
    (the<KotlinMultiplatformExtension>().targets.getByName("jvm") as KotlinJvmTarget).compilations.getByName("test")

/**
 * Points a budget task at [jvmTestCompilation]'s classes, on the runner `jvmTest` uses.
 *
 * `useJUnitPlatform()` is not decoration. The multiplatform convention sets it on `jvmTest` alone,
 * and a `Test` task left on Gradle's default runner finds no JUnit 5 test and fails with "No tests
 * found for given includes" - which is what all four of these did after issue #203 first moved
 * this module, while `build` stayed green because none of them is on `check`.
 */
fun Test.runsJvmTestClasses() {
    testClassesDirs = jvmTestCompilation.output.classesDirs
    classpath = files(jvmTestCompilation.output.allOutputs, jvmTestCompilation.runtimeDependencyFiles)
    useJUnitPlatform()
}

val budgetTestClasses = listOf(
    "dev.wildware.udea.core.snapshot.SnapshotBudgetTest",
    "dev.wildware.udea.core.snapshot.TickLoopBudgetTest",
    "dev.wildware.udea.core.movement.CharacterMoverBudgetTest",
    // Split out of `PhysicsRebuildTest` by issue #182. It had never been listed as a latency
    // budget by anybody, so a 2ms line was read inside every parallel `build` - and passed, which
    // is why nobody noticed. The rest of that class is reproducibility and stays on `check`.
    "dev.wildware.udea.core.physics.PhysicsRebuildBudgetTest",
)

tasks.named<Test>("jvmTest") {
    budgetTestClasses.forEach { filter.excludeTestsMatching(it) }
}

/** Capture under 1ms at 1000 entities, allocation-free, ring under 64MB. */
tasks.register<Test>("udeaSnapshotBudget") {
    group = "verification"
    description = "Gates snapshot capture at 1000 entities: <1ms median, zero allocation, <64MB ring."
    runsJvmTestClasses()
    filter.includeTestsMatching("dev.wildware.udea.core.snapshot.SnapshotBudgetTest")
    // The measured numbers are the point of the task, so they go to the build log rather than
    // into a report nobody opens.
    testLogging.showStandardStreams = true
}

/** The Phase 0 demo: 200 entities, 600 ticks, <50ms, zero allocation, identical hash stream. */
tasks.register<Test>("udeaBenchTickLoop") {
    group = "verification"
    description =
        "Gates the assembled tick loop at 200 entities and 600 ticks: <50ms median, zero " +
        "steady-state allocation, identical hash stream across a snapshot restore."
    runsJvmTestClasses()
    filter.includeTestsMatching("dev.wildware.udea.core.snapshot.TickLoopBudgetTest")
    testLogging.showStandardStreams = true
    // Published by the Phase 0 CI job as the gate's artifact.
    outputs.file(layout.buildDirectory.file("reports/udea/tick-loop.json"))
}

/**
 * The Phase 3 movement gate: 200 movers replayed 60 times inside a quarter of a 60Hz frame.
 *
 * Its own task for the same reason the two above are: it is a timing measurement, it belongs in
 * the build log of whichever machine is slow, and a normal `test` run should not pay for it
 * twice. `CharacterMoverBudgetTest`'s KDoc has the remedy when it fails, and the remedy is never
 * a larger constant.
 */
tasks.register<Test>("udeaBenchCharacterMover") {
    group = "verification"
    description =
        "Gates CharacterMover at 200 movers x 60 replay steps: under a quarter of a 60Hz frame."
    runsJvmTestClasses()
    filter.includeTestsMatching("dev.wildware.udea.core.movement.CharacterMoverBudgetTest")
    testLogging.showStandardStreams = true
}

/**
 * The spec 3.4 restore gate: 500 bodies rebuilt from their components inside an eighth of a frame.
 *
 * Its own task for the reason the three above have theirs, and it took issue #182 to give it one:
 * it asserts a number of microseconds, and it was doing that from inside `:udea-core:test`.
 * `PhysicsRebuildBudgetTest`'s KDoc has the remedy when it fails, and the remedy is never a wider
 * budget.
 */
tasks.register<Test>("udeaPhysicsRebuildBudget") {
    group = "verification"
    description = "Gates the physics rebuild at 500 bodies: under 2ms median."
    runsJvmTestClasses()
    filter.includeTestsMatching("dev.wildware.udea.core.physics.PhysicsRebuildBudgetTest")
    testLogging.showStandardStreams = true
}

// No `check` wiring. The four tasks above are reached through the root's `udeaLatencyBudgets`,
// which the `latency-budgets` CI job runs serially on both runner images. Putting one back here
// puts a millisecond measurement back inside a parallel build, which is issue #175.

// The one-line description Maven Central requires of a published artifact (issue #265).
description =
    "Udea's headless kernel: the fixed 60Hz simulation, the entity component system, NetId " +
    "identity, the tick-denominated clock, the between-tick barrier, seeded randomness and " +
    "the snapshot model. It runs with no graphics context at all."
