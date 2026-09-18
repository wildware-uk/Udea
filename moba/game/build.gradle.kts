import dev.wildware.udea.build.UdeaModuleRegistry
import dev.wildware.udea.build.UdeaNetComponents
import dev.wildware.udea.build.registerCharacterArtStaging
import dev.wildware.udea.build.registerNetProtocolLock
import dev.wildware.udea.build.udeaModule
import dev.wildware.udea.gradle.UdeaAssetsPlugin
import dev.wildware.udea.gradle.UdeaGenerateAccessorsTask
import dev.wildware.udea.gradle.UdeaPackBundleTask
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

/**
 * The game: components, systems, assets and what it draws (spec D12, issue #212).
 *
 * ## Why this is a library and not a launcher
 *
 * `:moba` was one module with three `main`s in it, and the split is the spec's: the game is what
 * every platform shares, and a launcher is a project whose targets are decided by the platform it
 * launches on. Nothing here has an entry point, opens a window or reads a system property -
 * `:moba:desktop` does all three, `:moba:android` will, and the game cannot tell which one it is
 * inside. That is what makes "the server and the agent's instance simulate identically" a fact
 * about the module graph rather than a promise in a KDoc.
 */

plugins {
    // `jvm` and `android`, and no more, because this module draws: it depends on `udea-render`,
    // which is on the same convention for the two reasons that convention states - Kool has no
    // iOS backend (spec D2) and publishes no wasmJs artifact (issue #223). The game is otherwise
    // ready for all four; `udea.kotlin-multiplatform` is the one-line change that follows #223
    // and #226.
    id("udea.kotlin-multiplatform-render")

    // `@Replicated` on this game's components. The processor runs **once**, and every target
    // compiles the one copy it writes - see `kspJvm` below for why the run is hung off the JVM
    // target rather than off a metadata compilation this module does not have.
    id("com.google.devtools.ksp") version libs.versions.ksp.get()

    // Level files (issue #191): every component a match holds is `@Serializable`, and the same
    // KSP run lists them on the generated `MobaModuleRegistry`.
    alias(libs.plugins.kotlinSerialization)

    // The build-time asset pipeline of spec 3.6. It travels with the assets, which are the game's
    // and not a launcher's: an Android build and a desktop build load the same `.udeapak`.
    id("dev.wildware.udea.assets")
}

// --- the asset pipeline, wired to a multiplatform module ---------------------------------------
//
// `UdeaAssetsPlugin` wires its own outputs into `main` when the `java` plugin is applied, and this
// project has no `java` plugin and no `main` source set. So the two hand-offs are made here, where
// the Kotlin multiplatform types are nameable, rather than by teaching a plugin that compiles
// against `gradleApi()` alone to reach into a Kotlin extension it cannot see.

/** Pass 5's generated `GameAssets`, as a file collection that carries its producing task. */
val accessorsTask = tasks.named<UdeaGenerateAccessorsTask>(UdeaAssetsPlugin.ACCESSORS_TASK)
val generatedAccessors: FileCollection =
    files(accessorsTask.map { it.generatedSources }).builtBy(accessorsTask)

/**
 * The packed bundle, laid out as the classpath resource the game opens.
 *
 * A `Sync` into `udea/` and not a bare `resources.srcDir`, because the file has to be *renamed
 * into a folder*: `MobaAssets` opens `udea/assets.udeapak` off the classloader, and a source
 * directory holding the bundle at its root would put it where another module's bundle would
 * collide with it.
 */
val bundleResources = tasks.register<Sync>("udeaBundleResources") {
    group = UdeaAssetsPlugin.GROUP
    description = "Lays the packed .udeapak out as the classpath resource the game opens."
    from(tasks.named<UdeaPackBundleTask>(UdeaAssetsPlugin.PACK_TASK).map { it.bundle }) {
        into(UdeaAssetsPlugin.BUNDLE_RESOURCE_DIRECTORY)
    }
    into(layout.buildDirectory.dir("generated/udea/bundle-resources"))
}

/**
 * Which KSP run this module has, and why it is the JVM target's rather than the common one.
 *
 * Every other multiplatform module here processes `commonMain` once through
 * `kspCommonMainMetadata`, and reads the result back from
 * `build/generated/ksp/metadata/commonMain/kotlin`. **That task does not exist in this project**,
 * and the reason is a property of the target list rather than of the wiring: KSP hangs its
 * metadata task off a metadata compilation named `commonMain`, and the Kotlin plugin only creates
 * per-source-set metadata compilations for a project whose targets are not all JVM-family. This
 * module is `jvm` and `android` - both JVM - so its metadata target has the single legacy `main`
 * compilation and KSP registers nothing against it. `udea-core`, `udea-gas` and the rest each have
 * a wasm or a native target, which is the whole of the difference.
 *
 * So the processor runs on the JVM target and the output is put on `commonMain`, where both
 * targets compile it. **`jvm` in the path names where the run was hung, not what it produced**:
 * the processor reads `@Replicated` off common declarations and emits common Kotlin, and the two
 * targets compile the identical file, so the "one id space across every target" guarantee the
 * metadata run gave is unchanged. Running it twice - `kspJvm` and `kspAndroid` - would be two
 * copies of one registry, and adding the second to `commonMain` as well would be a redeclaration.
 *
 * `udea-render` gaining a wasm target (issue #223) or this module gaining iOS restores the
 * metadata compilation, and this becomes the four lines every other module has.
 */
@Suppress("ktlint:standard:property-naming")
val KSP_TARGET: String = "jvm/jvmMain"

/** The single KSP task, by name, for the dependencies Gradle cannot infer. */
val kspRun: String = "kspKotlinJvm"

/** The generated registry, carrying the task that writes it. */
val generatedRegistry: Provider<Directory> =
    layout.buildDirectory.dir("generated/ksp/$KSP_TARGET/kotlin")

/**
 * The generated sources belong to `commonMain` and to nothing else.
 *
 * KSP puts its output on the source set it ran for, which is `jvmMain`. [generatedRegistry] then
 * puts the same directory on `commonMain`, so that the Android target and the metadata
 * compilation see it too - and one directory in two source sets of one compilation is not a
 * duplicate the compiler tolerates:
 *
 * ```
 * e: Files '.../MobaUdeaRegistry.kt', ... can be a part of only one module, but is listed as a
 * source for both `jvmMain` and `commonMain`
 * ```
 *
 * So the automatic wiring is taken back off `jvmMain`, leaving exactly one owner. In
 * `afterEvaluate` because KSP adds it during its own `afterEvaluate`, and by path prefix rather
 * than by index because the position in the list is not a thing this build should depend on.
 */
afterEvaluate {
    val generated = generatedRegistry.get().asFile
    kotlin.sourceSets.named("jvmMain") {
        kotlin.setSrcDirs(kotlin.srcDirs.filterNot { it == generated })
    }
}

kotlin {
    // One shared source set beside the default hierarchy, for the two `expect` declarations whose
    // `actual`s need `java.*`: reading the packed bundle off the classpath, and inflating a
    // zlib stream so a PNG atlas page can be decoded. Both targets of this module are JVM-family,
    // so this is one implementation rather than two - the same arrangement `udea-core` uses and
    // for the same reason.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmAndAndroid") {
                // By platform type rather than `withJvm()` and `withAndroidTarget()`: AGP's
                // multiplatform library target is not the `androidTarget()` the latter matches.
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
                // `api`, not `implementation`: a launcher builds `MobaGame.definition()` and hands
                // it to a `GameHost`, so `UdeaGameDef`, `GameContext` and `NetId` are on this
                // module's public surface.
                api(project(":udea-core"))

                // `@Replicated`, `@Net` and `@Sim` on this game's components. BINARY-retained, so
                // they are on this module's own bytecode and cannot be `compileOnly`.
                implementation(project(":udea-annotations"))

                implementation(project(":udea-gas"))
                implementation(project(":udea-net"))
                implementation(project(":udea-assets"))

                // `api`: `MobaScene` hands back a `RenderRegistry` and a `PresentationControl`,
                // both of which a launcher names. Kool does not come with them - `udea-render`
                // takes Kool as `implementation` precisely so a game can draw without being able
                // to reach past it (UDEA-MG-009 is the rule that keeps the other renderer out).
                api(project(":udea-render"))

                // The cue drain and the mixer. Presentation, with no GL in it.
                implementation(project(":udea-audio"))
            }
            // The registry KSP generates (issue #202), on `commonMain` so that every target
            // compiles the same one. See `generatedRegistry` for why the directory has `jvm` in
            // its path and why that is not a JVM-only registry.
            kotlin.srcDir(files(generatedRegistry).builtBy(kspRun))
            // The asset accessors pass 5 generates. On `commonMain` because a `GameAssets` entry
            // is a slot into the packed graph and holds no platform type; the script classpath is
            // a separate configuration nothing adds this directory to, which is spec 3.6's rule.
            kotlin.srcDir(generatedAccessors)
        }
    }
}

dependencies {
    // The forked asset pipeline: `udeaScanAssets`, `udeaValidateAssets`, `udeaPackBundle` and
    // `udeaGenerateAccessors` all run out of this configuration, in a JVM of their own. It is NOT
    // `implementation`: it carries `kotlin-compiler-embeddable`, and `UDEA-MG-005` forbids a
    // script compiler on a shipped game's runtime classpath.
    udeaAssetsCompiler(project(":udea-assets-compiler"))

    // One KSP run, hung off the JVM target. See `generatedRegistry` above: `kspCommonMainMetadata`
    // is the configuration the other multiplatform modules use, and its task does not exist here.
    add("kspJvm", project(":udea-codegen"))

}

/**
 * Where this game's assets live, and what they are compiled by.
 *
 * One root, `moba/game/assets`, and it moved with the game rather than with either launcher: an
 * Android build and a desktop build read the same `.udeapak`, so an asset root owned by one
 * launcher would be an asset root the other could not see.
 */
udea {
    assetRoots.from("assets")
    kotlinVersion.set(libs.versions.kotlin.get())
}

/**
 * The character art, put where the asset root expects it before anything reads the asset root.
 *
 * It moved to this project with the assets, and the guarantee is unchanged: the sheets under
 * `assets/sprites` are paid-pack art this repository cannot sublicense, so they are gitignored, a
 * clone has none of them, and this copies them out of the tree that already holds them on every
 * build. A clone builds, `git status` stays clean, and a `UDEA0032` about a `spritePath` is a
 * real defect rather than a step somebody forgot. The task is now `:moba:game:udeaStageCharacterArt`.
 */
registerCharacterArtStaging()

/**
 * This game's launcher registry (issue #202): `MobaUdeaRegistry` names the registry of this module
 * and of every Udea module on its runtime classpath, read off the resolved graph rather than
 * written down a second time.
 */
val udeaRegistry = udeaModule("Moba")

/**
 * The project-wide `@Replicated` id space, read from the reviewed `net-components.lock`.
 *
 * Not optional for a module that emits protocol identity. A processor numbering only the symbols
 * in front of it hands out 0, 1, 2 per module, so two modules both mint `ComponentTypeId(0)` and
 * two peers decode each other's packets as the wrong component type - silently, because each
 * module's lock is internally consistent and `protoHash` therefore reports agreement.
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

// Every compilation reads the generated registry out of `commonMain`, so none of them may start
// before it has been written - including the Android one and the metadata one, neither of which
// Gradle would otherwise know about a task on the JVM target.
tasks.withType<KotlinCompilationTask<*>>().configureEach {
    if (name != kspRun) dependsOn(kspRun)
}

/**
 * `moba` emits protocol identity, so it gets the same reviewed lock gate `:udea-codegen` has.
 *
 * One run writes it, so there is one lock and no arrangement in which the JVM and the Android leg
 * could be numbered differently. [generatedRegistry] says why the path has `jvm` in it.
 */
registerNetProtocolLock(
    generatedLock = layout.buildDirectory.file(
        "generated/ksp/$KSP_TARGET/resources/udea/Moba-net-protocol.lock",
    ),
    producingTask = kspRun,
)

kotlin.sourceSets.named("jvmMain") { resources.srcDir(bundleResources) }
kotlin.sourceSets.named("androidMain") { resources.srcDir(bundleResources) }

// --- the source rules that read this project's tree --------------------------------------------

tasks.withType<Test>().configureEach {
    // Where `MobaAssetsTest` finds this module's sources. A relative path would resolve against
    // the project directory under Gradle and against the daemon's working directory under an IDE,
    // and a source scan that silently read nothing would pass.
    systemProperty("udea.moba.projectDir", layout.projectDirectory.asFile.absolutePath)
}
