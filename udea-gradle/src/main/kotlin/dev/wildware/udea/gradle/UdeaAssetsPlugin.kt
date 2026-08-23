package dev.wildware.udea.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.CopySpec
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * How a game declares where its assets live and what they compile against.
 *
 * ## `assetRoots`, and why assets stop being jar resources
 *
 * The old tree kept `.udea.kts` under `src/main/resources`, so every script and every PNG was
 * copied into the jar verbatim *and* read at runtime by a script host. Spec 3.6 deletes the
 * second half; this deletes the first. An asset root is an input to a build step, and its
 * output - one `.udeapak` - is what ships. A tree under `src/main/resources` cannot express
 * that, because `processResources` would package the sources next to the artifact built from
 * them.
 *
 * Exactly one root is supported today and the plugin says so rather than silently packing the
 * first: ids are relative to a root, so two roots need a rule for what happens when both
 * declare `character/orc`, and inventing one before a game needs it is how a build tool grows a
 * feature nobody can explain.
 */
public abstract class UdeaAssetsExtension {

    /** The asset tree. `udea { assetRoots.from("assets") }`. */
    public abstract val assetRoots: ConfigurableFileCollection

    /**
     * Base name of the packed bundle, without the extension. Defaults to `assets`.
     *
     * It reaches the jar as `udea/<name>.udeapak`, which is the resource the game opens.
     */
    public abstract val bundleName: Property<String>

    /**
     * The Kotlin the `.udea.kts` are compiled by, as an explicit task input.
     *
     * Redundant in principle - the compiler classpath is `@Classpath`, so the version is already
     * in the cache key by way of the jar's contents - and declared anyway, because "redundant in
     * principle" is what a cache key is until somebody replaces a jar with a directory. Issue #90
     * asks for it by name.
     */
    public abstract val kotlinVersion: Property<String>
}

/** Everything the forked pipeline needs that is not one command's own arguments. */
public abstract class UdeaAssetTask : DefaultTask() {

    /** The classpath [dev.wildware.udea.gradle.UdeaAssetsPlugin.CLI] is loaded from. */
    @get:Classpath
    public abstract val compilerClasspath: ConfigurableFileCollection

    /**
     * The asset root, as an absolute path.
     *
     * `@Internal` on purpose, and it is the single most important annotation in this file. The
     * root is where the checkout happens to be; putting it in the cache key would make every
     * task miss in a differently named directory, which is the exact property issue #90 asks
     * two checkouts to demonstrate. What *is* an input is [sources], with `RELATIVE` path
     * sensitivity - the file tree under the root, addressed by its shape rather than its
     * location.
     */
    @get:Internal
    public abstract val assetRoot: DirectoryProperty

    /** The repository root every emitted span is relative to. `@Internal` for [assetRoot]'s reason. */
    @get:Internal
    public abstract val repoRoot: DirectoryProperty

    /** Every file under the asset root: scripts, images and audio alike. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:IgnoreEmptyDirectories
    public abstract val sources: ConfigurableFileCollection

    @get:Inject
    protected abstract val execOperations: ExecOperations

    /**
     * Runs one subcommand of the pipeline in a forked JVM.
     *
     * Forked, not a `WorkAction` with classloader isolation, and the reason is written down in
     * `AssetPipelineCli`: the script compiler and the PSI parser both hold state that must not
     * outlive one invocation inside a long-lived Gradle daemon.
     */
    protected fun run(subcommand: String, scriptClasspath: FileCollection, vararg options: String) {
        val result = execOperations.javaexec(
            gradleAction { spec: org.gradle.process.JavaExecSpec ->
                spec.mainClass.set(UdeaAssetsPlugin.CLI)
                spec.classpath = compilerClasspath
                spec.args(listOf(subcommand) + options)
                spec.systemProperty(UdeaAssetsPlugin.SCRIPT_CLASSPATH_PROPERTY, scriptClasspath.asPath)
                spec.isIgnoreExitValue = true
            },
        )
        if (result.exitValue != 0) {
            throw GradleException(
                "$name failed: the asset pipeline exited ${result.exitValue}. The diagnostics " +
                    "above name the file, the line and the column.",
            )
        }
    }

    /** `--key=value`, the only shape [UdeaAssetsPlugin.CLI] accepts. */
    protected fun option(key: String, value: File): String = "--$key=${value.absolutePath}"
}

/** Pass 1: the PSI declaration scan, into `declarations.json`. */
@CacheableTask
public abstract class UdeaScanAssetsTask : UdeaAssetTask() {

    /** Pass 1's output: every declaration and every reference site, with repo-relative spans. */
    @get:OutputFile
    public abstract val declarations: RegularFileProperty

    /** Scans. */
    @TaskAction
    public fun scan() {
        run(
            "scan",
            compilerClasspath,
            option("repoRoot", repoRoot.get().asFile),
            option("assetRoot", assetRoot.get().asFile),
            option("out", declarations.get().asFile),
        )
    }
}

/**
 * Pass 5: `GameAssets` and `META-INF/udea/asset-index.json`.
 *
 * Its input is [declarations] and **not** the asset tree, which is what makes the split into two
 * tasks worth having: an edit that moves a declaration within its file changes the scan's spans
 * and produces byte-identical accessors, so nothing downstream recompiles.
 */
@CacheableTask
public abstract class UdeaGenerateAccessorsTask : UdeaAssetTask() {

    /** Pass 1's output. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val declarations: RegularFileProperty

    /** The generated Kotlin, one file per top-level asset group plus the aggregate. */
    @get:OutputDirectory
    public abstract val generatedSources: DirectoryProperty

    /** The generated resources, holding `META-INF/udea/asset-index.json`. */
    @get:OutputDirectory
    public abstract val generatedResources: DirectoryProperty

    /** Generates. */
    @TaskAction
    public fun generate() {
        run(
            "accessors",
            compilerClasspath,
            option("declarations", declarations.get().asFile),
            option("srcOut", generatedSources.get().asFile),
            option("resourceOut", generatedResources.get().asFile),
        )
    }
}

/**
 * Passes 2 and 3: compile every script, run the validator suite, write `diagnostics.json`.
 *
 * ## Why there is no separate reporter task
 *
 * Issue #90 asks for a `finalizedBy` reporter so `diagnostics.json` is written even when
 * validation fails. A `finalizedBy` task can only write what the failing task left behind, so
 * the document has to be produced before the failure either way - and once it is, the reporter
 * has nothing to do. So the *pipeline process* writes the file and then exits non-zero, and this
 * task fails on that exit code. The file is on disk in both outcomes, which is the property the
 * reporter existed for, with one fewer task to keep in step.
 */
@CacheableTask
public abstract class UdeaValidateAssetsTask : UdeaAssetTask() {

    /** The classpath the `.udea.kts` are compiled against. Never the generated accessors. */
    @get:Classpath
    public abstract val scriptClasspath: ConfigurableFileCollection

    /** The Kotlin the scripts are compiled by. */
    @get:Input
    public abstract val kotlinVersion: Property<String>

    /** Ranked, collapsed and capped, exactly as an agent's `assets.validate` sees them. */
    @get:OutputFile
    public abstract val diagnostics: RegularFileProperty

    /**
     * Where compiled-script jars are cached.
     *
     * `@Internal`: it is a cache and not a result, its contents are keyed by a hash the compiler
     * computes, and declaring it as an output would make every task that warmed it dirty.
     */
    @get:Internal
    public abstract val scriptCache: DirectoryProperty

    /** Validates. */
    @TaskAction
    public fun validate() {
        run(
            "validate",
            scriptClasspath,
            option("repoRoot", repoRoot.get().asFile),
            option("assetRoot", assetRoot.get().asFile),
            option("cache", scriptCache.get().asFile),
            option("out", diagnostics.get().asFile),
        )
    }
}

/** Pass 4: the deterministic atlas and the `.udeapak`. */
@CacheableTask
public abstract class UdeaPackBundleTask : UdeaAssetTask() {

    /** @see UdeaValidateAssetsTask.scriptClasspath */
    @get:Classpath
    public abstract val scriptClasspath: ConfigurableFileCollection

    /** @see UdeaValidateAssetsTask.kotlinVersion */
    @get:Input
    public abstract val kotlinVersion: Property<String>

    /**
     * The `.udeapak` format version this build writes.
     *
     * An explicit input so that a bundle written by an older format stays out of a cache hit for
     * a build that now reads a newer one - the failure otherwise is a `BundleVersionException`
     * at game launch from an artifact Gradle believes is up to date.
     */
    @get:Input
    public abstract val bundleFormatVersion: Property<Int>

    /** The bundle. */
    @get:OutputFile
    public abstract val bundle: RegularFileProperty

    /** What packing reported. Written whether or not packing succeeded. */
    @get:OutputFile
    public abstract val diagnostics: RegularFileProperty

    /** @see UdeaValidateAssetsTask.scriptCache */
    @get:Internal
    public abstract val scriptCache: DirectoryProperty

    /** Packs. */
    @TaskAction
    public fun pack() {
        run(
            "pack",
            scriptClasspath,
            option("repoRoot", repoRoot.get().asFile),
            option("assetRoot", assetRoot.get().asFile),
            option("cache", scriptCache.get().asFile),
            option("out", bundle.get().asFile),
            option("diagnostics", diagnostics.get().asFile),
        )
    }
}

/**
 * Fails when a build output names the directory this build happens to live in.
 *
 * ## Why this is a task and not a note in a review checklist
 *
 * A leaked absolute path is invisible in every way that matters: the build is green, the game
 * runs, the artifact is byte-identical to itself, and the only symptom is that a second machine
 * gets a cache miss - or worse, a *hit* on a bundle that names a directory it has not got.
 * Issue #90 asks for a CI grep; a grep in a CI script only runs on CI, so this runs on `check`.
 *
 * It reads the text-shaped outputs only. A `.udeapak` is binary and its own reproducibility gate
 * (`udeaPackGate`) packs from two different checkout roots, which is the stronger check of the
 * two for that artifact and the one that would catch a path embedded in it.
 */
public abstract class UdeaVerifyRelocatableTask : DefaultTask() {

    /** The generated documents to scan. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val outputs_: ConfigurableFileCollection

    /** The absolute path that must not appear in any of them. */
    @get:Input
    public abstract val forbidden: Property<String>

    /** A marker, so the task is up to date rather than rerun on every `check`. */
    @get:OutputFile
    public abstract val report: RegularFileProperty

    /** Scans. */
    @TaskAction
    public fun verify() {
        val needle = forbidden.get()
        // Both separators: a Windows build writes `C:\Users\...` into a message and
        // `C:/Users/...` into a path, and a scan that knew only one of them would pass on the
        // machine most likely to leak.
        val needles = listOf(needle, needle.replace(File.separatorChar, '/'), needle.replace('/', '\\'))
            .distinct()
        val offenders = outputs_.files.filter { it.isFile }.mapNotNull { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
            val hit = needles.firstOrNull { it in text } ?: return@mapNotNull null
            "${file.name} contains the checkout path $hit"
        }
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                if (offenders.isEmpty()) "ok: ${outputs_.files.size} output(s) name no checkout path\n"
                else offenders.joinToString("\n", postfix = "\n"),
            )
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "a build output names this checkout's directory, so it cannot be reused from a " +
                    "build cache on another machine:\n" + offenders.joinToString("\n"),
            )
        }
    }
}

/**
 * The five passes of spec 3.6, as the tasks a real build runs.
 *
 * ## What was here before
 *
 * Nothing. `AssetPackCli` said it in its own KDoc: *"This is not the shipped asset pipeline.
 * `UdeaAssetsPlugin` ... lives in `:udea-gradle` and is not written. Nothing in `:moba`'s build
 * produces or consumes a bundle yet."* Every pass existed and was tested; no build ran them, so
 * the game loaded a PNG at runtime and sliced it, and the deterministic packer was a proven,
 * unused object.
 *
 * ## The pass order, and where the two classpaths go
 *
 * ```
 * udeaScanAssets -----> declarations.json --+--> udeaGenerateAccessors --> GameAssets (.kt srcDir)
 *                                           |                             asset-index.json
 * udeaValidateAssets --> diagnostics.json   |  (check depends on this)
 * udeaPackBundle -----> assets.udeapak -----+--> processResources --> udea/assets.udeapak
 * ```
 *
 * Two classpaths, and they must not be the same one:
 *
 * - **`udeaAssetsCompiler`** loads the pipeline itself. It carries `kotlin-compiler-embeddable`,
 *   which is why the whole thing is a forked process and why `:udea-gradle` names the entry point
 *   as a string rather than importing it.
 * - **`udeaAssetScript`** is what `.udea.kts` compile *against*. It must never contain the
 *   generated accessors. That is spec 3.6's rule and it is not a style preference: the accessors
 *   are generated *from* the scripts, so putting them on the script classpath makes an asset
 *   rename invalidate that classpath and recompile every script in the tree. Nothing in this
 *   plugin adds the generated source to it, and [UdeaGenerateAccessorsTask]'s output goes onto
 *   the *main* source set alone.
 *
 * ## Nothing here reaches the game's runtime classpath
 *
 * The same argument [UdeaAgentPlugin] makes. `gradleApi()` is `compileOnly` on this module and no
 * game module depends on it; the compiler reaches the build only through a resolvable
 * configuration and a forked JVM, so `kotlin-compiler-embeddable` cannot end up in a shipped
 * game - which `StartupClasspathTest` checks from the other end.
 */
public class UdeaAssetsPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("udea", UdeaAssetsExtension::class.java)
        extension.bundleName.convention(DEFAULT_BUNDLE_NAME)
        extension.kotlinVersion.convention(UNDECLARED_KOTLIN_VERSION)

        val compilerClasspath = project.configurations.create(
            COMPILER_CONFIGURATION,
            gradleAction { configuration: Configuration ->
                configuration.description = "The udea asset pipeline, run as a forked process."
                configuration.isCanBeConsumed = false
                configuration.isCanBeResolved = true
            },
        )
        val scriptClasspath = project.configurations.create(
            SCRIPT_CONFIGURATION,
            gradleAction { configuration: Configuration ->
                configuration.description =
                    "What .udea.kts compile against. Never the generated accessors (spec 3.6)."
                configuration.isCanBeConsumed = false
                configuration.isCanBeResolved = true
                // The pipeline is on it by default because `AssetScope` - the receiver every
                // script is compiled against - is in it. A game adds its own types on top.
                configuration.extendsFrom(compilerClasspath)
            },
        )

        val assetRoot: Provider<File> = project.provider {
            val roots = extension.assetRoots.files
            when (roots.size) {
                1 -> roots.single()
                0 -> throw GradleException(
                    "${project.path} applies ${PLUGIN_ID} but declares no asset root. Add " +
                        "`udea { assetRoots.from(\"assets\") }`.",
                )
                else -> throw GradleException(
                    "${project.path} declares ${roots.size} asset roots and the pipeline supports " +
                        "one: ${roots.joinToString { it.name }}. Asset ids are relative to a root, " +
                        "so two roots need a rule for a colliding id and there is not one yet.",
                )
            }
        }
        // Every file under the root, and not just the scripts: a `spriteSheet` names a PNG that
        // the atlas is packed from, so a changed image must make the pack out of date. A tree
        // that watched only `**/*.udea.kts` would serve a stale atlas from the cache forever.
        val sources: FileCollection = project.files(extension.assetRoots).asFileTree

        val output = project.layout.buildDirectory.dir("udea")
        val scriptCache = project.layout.buildDirectory.dir("udea/script-cache")
        val repoRoot = project.rootProject.layout.projectDirectory

        val scan = project.tasks.register(SCAN_TASK,
            UdeaScanAssetsTask::class.java,
            gradleAction { task: UdeaScanAssetsTask ->
            task.group = GROUP
            task.description = "Scans .udea.kts declarations and reference spans with PSI only."
            task.compilerClasspath.from(compilerClasspath)
            task.assetRoot.fileProvider(assetRoot)
            task.repoRoot.set(repoRoot)
            task.sources.from(sources)
            task.declarations.set(output.map { it.file("scan/declarations.json") })
            },
        )

        val accessors = project.tasks.register(ACCESSORS_TASK,
            UdeaGenerateAccessorsTask::class.java,
            gradleAction { task: UdeaGenerateAccessorsTask ->
            task.group = GROUP
            task.description = "Emits GameAssets and META-INF/udea/asset-index.json."
            task.compilerClasspath.from(compilerClasspath)
            task.assetRoot.fileProvider(assetRoot)
            task.repoRoot.set(repoRoot)
            // Deliberately NOT the asset tree: this task's input is the scan, which is what lets
            // an edit that changes no id leave the generated sources byte-identical.
            task.sources.setFrom(scan.map { it.declarations })
            task.declarations.set(scan.flatMap { it.declarations })
            task.generatedSources.set(output.map { it.dir("generated/kotlin") })
            task.generatedResources.set(output.map { it.dir("generated/resources") })
            },
        )

        val validate = project.tasks.register(VALIDATE_TASK,
            UdeaValidateAssetsTask::class.java,
            gradleAction { task: UdeaValidateAssetsTask ->
            task.group = GROUP
            task.description = "Compiles and validates the asset graph; writes diagnostics.json."
            task.compilerClasspath.from(compilerClasspath)
            task.scriptClasspath.from(scriptClasspath)
            task.kotlinVersion.set(extension.kotlinVersion)
            task.assetRoot.fileProvider(assetRoot)
            task.repoRoot.set(repoRoot)
            task.sources.from(sources)
            task.scriptCache.set(scriptCache)
            task.diagnostics.set(output.map { it.file("diagnostics.json") })
            },
        )

        val pack = project.tasks.register(PACK_TASK,
            UdeaPackBundleTask::class.java,
            gradleAction { task: UdeaPackBundleTask ->
            task.group = GROUP
            task.description = "Packs the deterministic atlas and writes the .udeapak."
            task.compilerClasspath.from(compilerClasspath)
            task.scriptClasspath.from(scriptClasspath)
            task.kotlinVersion.set(extension.kotlinVersion)
            task.bundleFormatVersion.set(BUNDLE_FORMAT_VERSION)
            task.assetRoot.fileProvider(assetRoot)
            task.repoRoot.set(repoRoot)
            task.sources.from(sources)
            task.scriptCache.set(scriptCache)
            task.bundle.set(
                extension.bundleName.flatMap { name -> output.map { it.file("pack/$name.udeapak") } },
            )
            task.diagnostics.set(output.map { it.file("pack/diagnostics.json") })
            },
        )

        val relocatable = project.tasks.register(RELOCATABLE_TASK,
            UdeaVerifyRelocatableTask::class.java,
            gradleAction { task: UdeaVerifyRelocatableTask ->
            task.group = GROUP
            task.description = "Fails when a generated asset document names this checkout's path."
            task.outputs_.from(
                scan.map { it.declarations },
                accessors.map { it.generatedSources },
                accessors.map { it.generatedResources },
                validate.map { it.diagnostics },
                pack.map { it.diagnostics },
            )
            task.forbidden.set(project.rootProject.layout.projectDirectory.asFile.absolutePath)
            task.report.set(output.map { it.file("relocatable.txt") })
            },
        )

        project.pluginManager.withPlugin("java") {
            wireSourceSets(project, accessors, pack)
        }

        project.tasks.named(
            CHECK_TASK,
            gradleAction { task: Task -> task.dependsOn(validate, relocatable) },
        )
    }

    /**
     * Puts the generated accessors on `main` and the bundle into the packaged resources.
     *
     * `main` only, and that is the whole of the enforcement issue #90 asks for: the script
     * classpath is a separate configuration that nothing here adds this directory to, so a
     * `.udea.kts` naming `GameAssets` fails to compile. There is no flag to turn that off,
     * because there is no code path that would read one.
     *
     * The generated Kotlin goes on the source set's **java** roots rather than a Kotlin one, for
     * the reason [UdeaAgentPlugin] gives: this module compiles against `gradleApi()` alone and
     * cannot name a Kotlin Gradle plugin type, and the Kotlin compilation the KGP creates for a
     * source set already sources that source set's java directories.
     */
    private fun wireSourceSets(
        project: Project,
        accessors: TaskProvider<UdeaGenerateAccessorsTask>,
        pack: TaskProvider<UdeaPackBundleTask>,
    ) {
        val java = project.extensions.getByType(JavaPluginExtension::class.java)
        val main = java.sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)

        main.java.srcDir(project.files(accessors.map { it.generatedSources }).builtBy(accessors))
        main.resources.srcDir(project.files(accessors.map { it.generatedResources }).builtBy(accessors))

        // The bundle, into the jar under `udea/`. A `Copy` spec rather than another resources
        // srcDir because the file has to be *renamed into a folder* - the game opens
        // `udea/<name>.udeapak` off the classpath, and a bare srcDir would put it at the root
        // where any other module's bundle would collide with it.
        project.tasks.named(
            PROCESS_RESOURCES_TASK,
            Copy::class.java,
            gradleAction { task: Copy ->
                task.from(
                    pack.map { it.bundle },
                    gradleAction { spec: CopySpec -> spec.into(BUNDLE_RESOURCE_DIRECTORY) },
                )
            },
        )
    }

    public companion object {

        /** The plugin id, as declared in `META-INF/gradle-plugins`. */
        public const val PLUGIN_ID: String = "dev.wildware.udea.assets"

        /** The task group every task here is in. */
        public const val GROUP: String = "udea"

        /** Pass 1. */
        public const val SCAN_TASK: String = "udeaScanAssets"

        /** Pass 5. */
        public const val ACCESSORS_TASK: String = "udeaGenerateAccessors"

        /** Passes 2 and 3. */
        public const val VALIDATE_TASK: String = "udeaValidateAssets"

        /** Pass 4. */
        public const val PACK_TASK: String = "udeaPackBundle"

        /** The relocatability gate. */
        public const val RELOCATABLE_TASK: String = "udeaVerifyRelocatable"

        /** The configuration the forked pipeline is loaded from. */
        public const val COMPILER_CONFIGURATION: String = "udeaAssetsCompiler"

        /** The configuration `.udea.kts` are compiled against. */
        public const val SCRIPT_CONFIGURATION: String = "udeaAssetScript"

        /** The entry point of the forked pipeline. A string, never an import - see the class KDoc. */
        public const val CLI: String = "dev.wildware.udea.assets.compiler.pipeline.AssetPipelineCli"

        /** Where the script compile classpath is handed to it. Must match `AssetPipelineCli`. */
        public const val SCRIPT_CLASSPATH_PROPERTY: String = "udea.assetsCompiler.classpath"

        /** Folder inside the jar the bundle is packaged under. Must match what the game opens. */
        public const val BUNDLE_RESOURCE_DIRECTORY: String = "udea"

        /** Base name of the bundle when a game does not choose one. */
        public const val DEFAULT_BUNDLE_NAME: String = "assets"

        /**
         * What `kotlinVersion` reads when a game does not set it.
         *
         * A string and not an empty default, so that the cache key still changes if a game
         * starts declaring one - and so that a reader of a task's inputs can tell "nobody said"
         * from "2.2.10".
         */
        public const val UNDECLARED_KOTLIN_VERSION: String = "undeclared"

        /** Must match `BundleFormat.VERSION` in `udea-assets`. */
        public const val BUNDLE_FORMAT_VERSION: Int = 1

        private const val CHECK_TASK: String = "check"

        private const val PROCESS_RESOURCES_TASK: String = "processResources"
    }
}
