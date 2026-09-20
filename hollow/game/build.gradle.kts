import dev.wildware.udea.build.UdeaModuleRegistry
import dev.wildware.udea.build.UdeaNetComponents
import dev.wildware.udea.build.udeaModule
import dev.wildware.udea.gradle.UdeaAssetsPlugin
import dev.wildware.udea.gradle.UdeaGenerateAccessorsTask
import dev.wildware.udea.gradle.UdeaPackBundleTask
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

/**
 * Hollow, the 3D example game (epic #245): components, assets, the clearing level and what it
 * draws. A library, like `:moba:game`, with no entry point in it; `:hollow:desktop` launches it.
 *
 * This script is `moba/game/build.gradle.kts` with the parts Hollow does not use yet left out -
 * the character-art staging (Hollow's art is committed CC0) and the protocol lock (Hollow declares
 * no `@Replicated` component of its own yet: it replicates `udea-core`'s `Transform3D`, see
 * `HollowNet`). Where a line is the same as moba's, moba's script carries the long form of why.
 */

plugins {
    // `jvm` and `android`, because this module draws through `udea-render`, which is on the same
    // convention. There is no Android launcher in the Hollow epic; the target is here because the
    // convention is, and it keeps the game from naming anything desktop-only.
    id("udea.kotlin-multiplatform-render")

    // The generated registry (issue #202): `HollowUdeaRegistry` and the level component list.
    id("com.google.devtools.ksp") version libs.versions.ksp.get()

    // Level files (issue #191): the clearing's components are `@Serializable`.
    alias(libs.plugins.kotlinSerialization)

    // The build-time asset pipeline of spec 3.6: the clearing's models are `model(...)` assets.
    id("dev.wildware.udea.assets")
}

// --- the asset pipeline, wired to a multiplatform module (as moba's is) --------------------------

/** Pass 5's generated `GameAssets`, as a file collection that carries its producing task. */
val accessorsTask = tasks.named<UdeaGenerateAccessorsTask>(UdeaAssetsPlugin.ACCESSORS_TASK)
val generatedAccessors: FileCollection =
    files(accessorsTask.map { it.generatedSources }).builtBy(accessorsTask)

/** The packed bundle and the saved levels, laid out as the classpath resources the game opens. */
val bundleResources = tasks.register<Sync>("udeaBundleResources") {
    group = UdeaAssetsPlugin.GROUP
    description = "Lays the packed .udeapak and the saved levels out as the classpath resources the game opens."
    from(tasks.named<UdeaPackBundleTask>(UdeaAssetsPlugin.PACK_TASK).map { it.bundle }) {
        into(UdeaAssetsPlugin.BUNDLE_RESOURCE_DIRECTORY)
    }
    from(layout.projectDirectory.dir("levels")) { into("levels") }
    into(layout.buildDirectory.dir("generated/udea/bundle-resources"))
}

/**
 * The one KSP run, hung off the JVM target because a project whose targets are all JVM-family has
 * no `commonMain` metadata compilation for KSP to use. `moba/game/build.gradle.kts` says so at
 * length; the arrangement here is the same.
 */
@Suppress("ktlint:standard:property-naming")
val KSP_TARGET: String = "jvm/jvmMain"

/** The single KSP task, by name, for the dependencies Gradle cannot infer. */
val kspRun: String = "kspKotlinJvm"

/** The generated registry, carrying the task that writes it. */
val generatedRegistry: Provider<Directory> =
    layout.buildDirectory.dir("generated/ksp/$KSP_TARGET/kotlin")

// The generated sources belong to `commonMain` alone: KSP also puts them on `jvmMain`, and one
// directory in two source sets of one compilation is a compile error.
afterEvaluate {
    val generated = generatedRegistry.get().asFile
    kotlin.sourceSets.named("jvmMain") {
        kotlin.setSrcDirs(kotlin.srcDirs.filterNot { it == generated })
    }
}

kotlin {
    // `jvmAndAndroid` holds the two `actual`s that read resources off the class loader.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmAndAndroid") {
                withCompilations {
                    it.target.platformType == KotlinPlatformType.jvm ||
                        it.target.platformType == KotlinPlatformType.androidJvm
                }
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                // `api`: a launcher builds `HollowGame.definition()` and hands it to a `GameHost`.
                api(project(":udea-core"))
                implementation(project(":udea-annotations"))
                implementation(project(":udea-assets"))
                // Replication: the server and client sessions (issue #249, multiplayer from H1).
                implementation(project(":udea-net"))
                // `api`: `HollowScene` takes a `RenderRegistry` and makes `ModelRenderer`s, which a
                // launcher names.
                api(project(":udea-render"))
            }
            kotlin.srcDir(files(generatedRegistry).builtBy(kspRun))
            kotlin.srcDir(generatedAccessors)
        }
    }
}

dependencies {
    // The forked asset pipeline; not `implementation`, for UDEA-MG-005 (moba's script says more).
    udeaAssetsCompiler(project(":udea-assets-compiler"))
    add("kspJvm", project(":udea-codegen"))
}

/** Hollow's asset root: the CC0 nature models and the ground (see `assets/models/nature/NOTICE.md`). */
udea {
    assetRoots.from("assets")
    kotlinVersion.set(libs.versions.kotlin.get())
}

/** `HollowUdeaRegistry`: this module's registry and every Udea module's on its runtime classpath. */
val udeaRegistry = udeaModule("Hollow")

/** The project-wide `@Replicated` id space. Hollow adds nothing to it yet; the processor reads it all the same. */
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

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    if (name != kspRun) dependsOn(kspRun)
}

kotlin.sourceSets.named("jvmMain") { resources.srcDir(bundleResources) }
kotlin.sourceSets.named("androidMain") { resources.srcDir(bundleResources) }
