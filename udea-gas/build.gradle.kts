import dev.wildware.udea.build.UdeaModuleRegistry
import dev.wildware.udea.build.udeaModule
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    // Every target, iOS included: `udea-core` gained its iOS targets when Fleks was vendored
    // (issue #215).
    id("udea.kotlin-multiplatform")
    // Level files (issue #191): `Attributes`, `Abilities` and `GameplayEffects` are saved, so
    // they need serializers, and `udea-codegen` lists them in the generated `GasModuleRegistry`.
    alias(libs.plugins.kotlinSerialization)
    id("com.google.devtools.ksp") version libs.versions.ksp.get()
}

// Declares this module to every launcher that has it on its runtime classpath (issue #202).
val udeaRegistry = udeaModule("Gas")
ksp {
    arg(UdeaModuleRegistry.MODULE_NAME_OPTION, udeaRegistry.name)
    arg(UdeaModuleRegistry.REGISTRY_MODULES_OPTION, udeaRegistry.registryModules)
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":udea-core"))
            }
            // The registry KSP generates from the common source set (issue #202), compiled into
            // every target from there. See the `kspCommonMainMetadata` wiring below.
            kotlin.srcDir(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
        }
    }
}

dependencies {
    // The Replicator contract's executable specification: ArrayFieldStore and ArrayBitIo. The
    // attribute replication tests measure a real payload through them rather than asserting on a
    // mock, and udea-core publishes them as a JVM variant for exactly this, so those tests are
    // `jvmTest` rather than `commonTest`.
    "jvmTestImplementation"(testFixtures(project(":udea-core")))

    // KSP over the common source set, once, rather than once per target: the registry it writes
    // names only common declarations, as udea-core's does (issue #203).
    add("kspCommonMainMetadata", project(":udea-codegen"))
}

// Every compilation reads the generated registry, and so does each per-target KSP run the plugin
// registers beside the common one, so none of them may start before it has been written.
val kspCommonMain = "kspCommonMainKotlinMetadata"
tasks.withType<KotlinCompilationTask<*>>().configureEach { dependsOn(kspCommonMain) }
tasks.matching { it.name.startsWith("ksp") && it.name != kspCommonMain }.configureEach { dependsOn(kspCommonMain) }

// --- Phase 3 time gate (spec 5, issue #95) ----------------------------------------------------
//
// Tick is the universal unit. A seconds-denominated duration or a wall-clock read inside GAS is not
// a style problem: it is a value that will not survive a rewind and will not agree across two
// machines, and it will present as a desync a long way from its cause. So it is a build failure,
// not a review note.
//
// Everything the task needs is captured as a plain value before `doLast`, and the scan itself is
// written inside it: a `doLast` that called a script-level function would be a Gradle script object
// reference, which the configuration cache refuses to serialise.

val udeaVerifyGasTime = tasks.register("udeaVerifyGasTime") {
    group = "verification"
    description = "Fails if udea-gas simulation code references seconds, a wall clock or LibGDX."

    /** Forbidden reference, and why it is forbidden — the message a developer actually reads. */
    val forbidden: Map<String, String> = mapOf(
        "kotlin.time.Duration" to "a seconds-denominated duration; use Tick, or an Int tick count",
        "System.nanoTime" to "a wall clock; time comes from SimClock, denominated in Tick",
        "System.currentTimeMillis" to "a wall clock; time comes from SimClock, denominated in Tick",
        "Instant.now" to "a wall clock; time comes from SimClock, denominated in Tick",
        "com.badlogic.gdx" to "LibGDX; udea-gas must never see graphics or audio (spec 3.5)",
        "deltaTime" to "a frame delta; accumulating one is what issue #95 exists to delete",
        // The wall clocks common code can reach (issue #204). The JVM's own are unresolvable in
        // `commonMain`, so without these the gate would police a source set in which the clocks it
        // names cannot even compile.
        "TimeSource" to "a wall clock (kotlin.time); time comes from SimClock, denominated in Tick",
        "Clock.System" to "a wall clock (kotlin.time or kotlinx-datetime); time comes from SimClock, denominated in Tick",
    )

    // Every shipped source set, not only `commonMain` (issue #204): `commonMain` cannot resolve
    // `System.nanoTime`, so a platform source set such as `jvmMain` is exactly where one would be
    // written. Test source sets are not simulation and end in `Test`, so the glob leaves them out.
    val sourceRoot = layout.projectDirectory.dir("src")
    val sources = fileTree(sourceRoot) { include("*Main/kotlin/**/*.kt") }
    inputs.files(sources).withPropertyName("simulationSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("forbidden", forbidden.keys.sorted().joinToString(","))

    // A verification task with no output is never up to date; the report keeps it incremental and
    // is what CI publishes as the gate's artifact.
    val reportFile = layout.buildDirectory.file("reports/udea/gas-time-gate.txt")
    outputs.file(reportFile)

    val sourceRootDir = sourceRoot.asFile
    val report = reportFile.get().asFile

    doLast {
        // Comments are stripped first, because this module's KDoc deliberately *names* the old
        // seconds-denominated API it replaced — a gate that could not tell a citation from a call
        // would force the documentation to go vague about what it fixed. Newlines inside block
        // comments are kept so reported line numbers stay true. It does not understand a `//`
        // inside a string literal, which is a false-negative risk only: a forbidden reference
        // inside a string is not a call, and the alternative is a Kotlin lexer in a build script.
        fun stripComments(text: String): String {
            val out = StringBuilder(text.length)
            var index = 0
            while (index < text.length) {
                when {
                    text.startsWith("/*", index) -> {
                        val end = text.indexOf("*/", index + 2)
                        val stop = if (end < 0) text.length else end + 2
                        for (character in text.substring(index, stop)) if (character == '\n') out.append('\n')
                        index = stop
                    }

                    text.startsWith("//", index) -> {
                        val end = text.indexOf('\n', index)
                        index = if (end < 0) text.length else end
                    }

                    else -> {
                        out.append(text[index])
                        index++
                    }
                }
            }
            return out.toString()
        }

        val violations = mutableListOf<String>()
        var scanned = 0
        sources.files
            .sortedBy { it.path }
            .forEach { file ->
                scanned++
                // Relative to `src`, so the source set is in the message: two platforms' `actual`
                // files may share a name.
                val name = file.relativeTo(sourceRootDir).invariantSeparatorsPath
                stripComments(file.readText()).lineSequence().forEachIndexed { index, line ->
                    forbidden.forEach { (needle, why) ->
                        if (line.contains(needle)) {
                            violations += "$name:${index + 1} references '$needle' — $why"
                        }
                    }
                }
            }

        require(scanned > 0) { "udeaVerifyGasTime scanned no sources; the gate is misaimed at $sourceRootDir/*Main/kotlin" }

        report.parentFile.mkdirs()
        report.writeText("scanned $scanned file(s)\n" + violations.joinToString("\n").ifEmpty { "clean" } + "\n")

        if (violations.isNotEmpty()) {
            throw GradleException(
                "udea-gas simulation code is not tick-denominated:\n" +
                    violations.joinToString("\n") { "  $it" },
            )
        }
    }
}

// --- Phase 3 allocation gate (issue #97) ------------------------------------------------------
//
// The recompute runs for every unit in a 5v5 every tick. `AttributeSystem.kt:23` allocated a sorted
// list per entity per tick; at 500 entities and 60Hz that is 30 000 lists a second, and the GC
// pause it buys is a frame the simulation does not get. Same shape as `udea-core`'s snapshot and
// tick-loop budgets: a separate task so the measurement reaches the build log, and excluded from
// `jvmTest` so a normal run does not pay for it twice. It counts bytes rather than milliseconds, so
// unlike those budgets it is not skewed by a busy machine and stays on `check`.

val allocationTestClass = "dev.wildware.udea.gas.AttributeAllocationTest"

tasks.named<Test>("jvmTest") {
    filter.excludeTestsMatching(allocationTestClass)
}

/** The JVM target's test compilation: where the allocation test is compiled. */
val jvmTestCompilation: KotlinCompilation<*> =
    (the<KotlinMultiplatformExtension>().targets.getByName("jvm") as KotlinJvmTarget).compilations.getByName("test")

val udeaGasAllocationBudget = tasks.register<Test>("udeaGasAllocationBudget") {
    group = "verification"
    description = "Gates the attribute recompute at 500 entities x 8 effects x 600 ticks: zero bytes."
    testClassesDirs = jvmTestCompilation.output.classesDirs
    classpath = files(jvmTestCompilation.output.allOutputs, jvmTestCompilation.runtimeDependencyFiles)
    // `jvmTest` is on JUnit 5 by convention and a `Test` task registered here is not: left on
    // Gradle's default runner it finds no test and fails "No tests found for given includes".
    useJUnitPlatform()
    filter.includeTestsMatching(allocationTestClass)
    testLogging.showStandardStreams = true
}

tasks.named("check") {
    dependsOn(udeaVerifyGasTime, udeaGasAllocationBudget)
}

// The one-line description Maven Central requires of a published artifact (issue #265).
description =
    "Udea's gameplay ability system: abilities, attributes and effects, with every duration " +
    "and cooldown denominated in simulation ticks."
