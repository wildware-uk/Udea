import com.google.devtools.ksp.gradle.KspAATask
import dev.wildware.udea.build.UdeaModuleRegistry
import dev.wildware.udea.build.UdeaNetComponents
import dev.wildware.udea.build.udeaModule
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    // Every target, iOS included. Pathfinding is arithmetic over integer arrays: it names no
    // device, no clock and no platform API, so there is nothing here that a target could lack.
    id("udea.kotlin-multiplatform")
    // Level files (issue #191): a building's footprint is level content, so `NavObstacle` and
    // `NavAgent` are `@Serializable` and `udea-codegen` lists them in `NavModuleRegistry`.
    alias(libs.plugins.kotlinSerialization)
    id("com.google.devtools.ksp") version libs.versions.ksp.get()
}

// Declares this module to every launcher that has it on its runtime classpath (issue #202).
val udeaRegistry = udeaModule("Nav")

/**
 * The project-wide `@Replicated` id space, read from the reviewed `net-components.lock`.
 *
 * `NavAgent` and `NavObstacle` are in it because an order and a footprint have to survive a
 * `time.rewind`: a component outside this space is not partly captured but invisible to capture,
 * so a rewound unit would keep walking to a goal the restored world never gave it. A module that
 * names itself and emits a `Replicator` must be numbered from the project's space, or its first
 * id is also another module's first id - the processor refuses to run without it.
 */
val projectComponents: Provider<String> =
    providers.fileContents(rootProject.layout.projectDirectory.file(UdeaNetComponents.FILE_NAME))
        .asText
        .map { text ->
            when (val parsed = UdeaNetComponents.parse(text)) {
                is UdeaNetComponents.Parse.Success -> UdeaNetComponents.optionValue(parsed.components)
                is UdeaNetComponents.Parse.Failure -> throw GradleException(parsed.problem)
            }
        }

ksp {
    arg(UdeaModuleRegistry.MODULE_NAME_OPTION, udeaRegistry.name)
    arg(UdeaModuleRegistry.REGISTRY_MODULES_OPTION, udeaRegistry.registryModules)
    arg(UdeaNetComponents.KSP_OPTION, projectComponents.get())
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                // `Transform3D`, `NetIdIndex`, `SimSystem`, `UdeaModule`, `ServiceKey`. Nothing
                // else: navigation is a consumer of the kernel and of nothing above it.
                api(project(":udea-core"))
            }
            // The registry and the replicators KSP generates from the common source set (issue
            // #202), compiled into every target from there. See the `kspCommonMainMetadata`
            // wiring below.
            kotlin.srcDir(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
            // The tool manifest fragment, written by the common KSP run, which every target's
            // resource processing therefore has to wait on.
            resources.srcDir(
                files(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/resources"))
                    .builtBy("kspCommonMainKotlinMetadata"),
            )
        }
        jvmMain {
            dependencies {
                // `AgentResult`, `AgentToolDef`, `ToolModule`: the `nav.path` tool of issue #264.
                //
                // **compileOnly, and that is a release gate rather than a preference.**
                // `ReleaseRules.CLASSPATH_RULE` (UDEA-REL-002) forbids `:udea-agent` on a shipped
                // game's `runtimeClasspath`, because the agent surface mutates the live
                // simulation. A game ships the *navigation* half of this module, so an `api` or
                // `implementation` edge here would drag the agent surface into every release and
                // fail that gate. `udea-replay` strikes the same bargain for `replay.*`.
                //
                // The consequence, stated plainly: `NavToolset` and `NavToolModules` are
                // unloadable in a process with no `udea-agent` on its classpath, and only a debug
                // host serves `nav.path`.
                //
                // `jvmMain` rather than `commonMain` because `udea-agent` has no iOS target
                // (issue #208) and this module does; a `compileOnly` in `commonMain` would fail
                // to resolve on the iOS compilations. The day `udea-agent` gains them, the
                // toolset moves to `commonMain` and the `kspJvm` run below goes with it.
                compileOnly(project(":udea-agent"))
            }
        }
    }
}

dependencies {
    // Real Fleks components on real entities, a wired `GameContext` and a real snapshot ring:
    // the determinism test rewinds a live world rather than asserting on a double of one.
    "jvmTestImplementation"(testFixtures(project(":udea-core")))
    // The agent surface is `compileOnly` above, so this module's own tests have to put it back on
    // the classpath they run against - otherwise `nav.path` would be proven by nothing.
    "jvmTestImplementation"(project(":udea-agent"))

    // The processor over this module's common source set, once rather than once per target: the
    // components and the module registry are common code, so one generated copy compiled
    // everywhere cannot disagree between targets about a component's fields.
    add("kspCommonMainMetadata", project(":udea-codegen"))

    // And over `jvmMain`, for the one toolset that cannot be common (see the `compileOnly` note
    // above). KSP hands this run the common sources too, so it is scoped to its own source set
    // below, or it would generate every component's replicator and the registry a second time
    // and the JVM compilation would fail on the redeclarations.
    add("kspJvm", project(":udea-codegen"))
}

// Every compilation reads the generated code, and so does each per-target KSP run the plugin
// registers beside the common one, so none of them may start before it has been written.
val kspCommonMain = "kspCommonMainKotlinMetadata"
tasks.withType<KotlinCompilationTask<*>>().configureEach { dependsOn(kspCommonMain) }
tasks.matching { it.name.startsWith("ksp") && it.name != kspCommonMain }.configureEach { dependsOn(kspCommonMain) }

// `udea.sourceSet` is `udea-codegen`'s `CodegenOptions.SOURCE_SET`: the JVM run processes only
// `src/jvmMain/` and writes no registry, no lock and no replicator, which the common run already
// wrote. Declared as an input as well, because the option on its own is not one - measured on
// `udea-agent`, removing it left `kspKotlinJvm` UP-TO-DATE holding the other setting's files.
tasks.withType<KspAATask>().matching { it.name == "kspKotlinJvm" }.configureEach {
    kspConfig.processorOptions.put("udea.sourceSet", "jvmMain")
    inputs.property("udea.sourceSet", "jvmMain")
}

// --- the picture (issue #264) -----------------------------------------------------------------
//
// A PNG per scenario: the grid, the buildings, an A* path, the shared flow field, and a hundred
// units crossing the map at known ticks. It renders with `java.awt` from the JVM test source set,
// which is why it is a `JavaExec` over that compilation rather than a main class shipped in
// `jvmMain`: a screenshot writer is not part of the navigation library, and `udea-nav` must not
// grow a JVM-only image encoder for a picture a reviewer looks at once.
//
// Not on `check`. It writes files and asserts nothing; what asserts is `jvmTest`.

/** The JVM target's test compilation: where the tests and the shot main are compiled. */
val jvmTestCompilation: KotlinCompilation<*> =
    (the<KotlinMultiplatformExtension>().targets.getByName("jvm") as KotlinJvmTarget).compilations.getByName("test")

tasks.register<JavaExec>("udeaNavShot") {
    group = "verification"
    description = "Renders the nav grid, an A* path, the flow field and a 100-unit crowd to PNGs."
    classpath = files(jvmTestCompilation.output.allOutputs, jvmTestCompilation.runtimeDependencyFiles)
    mainClass.set("dev.wildware.udea.nav.shot.NavShotMain")
    val out = layout.buildDirectory.dir("reports/udea/nav")
    outputs.dir(out)
    argumentProviders.add { listOf("--out", out.get().asFile.absolutePath) }
}
