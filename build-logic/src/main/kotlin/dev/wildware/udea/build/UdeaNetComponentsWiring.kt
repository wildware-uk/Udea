package dev.wildware.udea.build

import java.io.File
import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.findByType

/**
 * How a build - this one, or a game's own - hands `udea-codegen` the project's component id space
 * (issue #274).
 *
 * [UdeaNetComponents] has held the rule since Phase 0, and until this issue **nothing published
 * called it**. Every module that needed the KSP option wrote the same eight lines into its own
 * build script, and those scripts are all inside this repository, so a game applying the
 * published conventions had no way to pass the list at all: its first `@Replicated` component
 * failed the build with a message telling it to "let the build pass the list in", and there was
 * no surface that did. robot-game ended up re-implementing the parse *and the sorting* - and the
 * sorting is precisely what makes an id a function of the set of components rather than of how
 * the file was edited.
 *
 * So the conventions do it. [applyNetComponentsToKsp] runs on every module `udea.kotlin-base`
 * covers, which is every Udea module and every module of a game on the published conventions.
 *
 * ## Where the file is, and how to say otherwise
 *
 * The default is [UdeaNetComponents.FILE_NAME] in the **root** project, because the id space is
 * the whole build's and a per-module answer could only disagree with itself. A build that keeps
 * it somewhere else says so once, in its root build script:
 *
 * ```kotlin
 * udeaNetComponents {
 *     registry = layout.projectDirectory.file("wire/components.lock")
 * }
 * ```
 *
 * Root rather than per-module for the same reason, and because Gradle evaluates the root project
 * before any subproject - so a module reading it in `afterEvaluate` can never see it half-set.
 *
 * The block is `udeaNetComponents` and not the `udea` the issue sketched, because `udea` is
 * already taken: `dev.wildware.udea.assets` registers an extension of that name for a module's
 * asset roots, and a one-project game applying both plugins would have failed on the collision.
 * `udeaGates` and `udeaAgent` are the same shape.
 *
 * ## Why the KSP extension is reached by name
 *
 * `build-logic` deliberately does not put the KSP Gradle plugin on its classpath
 * ([UdeaModuleRegistry] says why: every module applies that plugin by id at the catalog's
 * version, and a second copy here would be a version the catalog does not govern). So there is
 * no `KspExtension` type to configure, and [KspArgs] invokes `arg(String, String)` on whatever
 * object the project registered under `ksp`.
 *
 * That has a second, useful consequence: the wiring is keyed on the extension rather than on the
 * plugin id, so `NetComponentsWiringTest` can drive it with a stand-in object and prove the whole
 * path - file, parse, override, option - without a KSP plugin, a network or a Kotlin compile.
 */
public abstract class UdeaNetComponentsExtension @Inject constructor() {

    /**
     * The reviewed component id space this build numbers from.
     *
     * Defaults to [UdeaNetComponents.FILE_NAME] in the root project. An absent file is not an
     * error here: a build with no `@Replicated` component anywhere needs no id space, and the
     * processor is what refuses a module that emits a wire protocol without one - which is the
     * only place that can tell the two apart.
     */
    public abstract val registry: RegularFileProperty
}

/** The name the extension is registered under, and the block a root build script writes. */
public const val UDEA_NET_COMPONENTS_EXTENSION: String = "udeaNetComponents"

/**
 * This build's [UdeaNetComponentsExtension], creating it if `dev.wildware.udea.game-gates` has not already.
 *
 * Create-or-return for the reason [udeaGates] is: a plugin that failed unless another had been
 * applied first would be an ordering rule nobody can see.
 */
public fun Project.udeaNetComponents(): UdeaNetComponentsExtension =
    extensions.findByType<UdeaNetComponentsExtension>()
        ?: extensions.create(UDEA_NET_COMPONENTS_EXTENSION, UdeaNetComponentsExtension::class.java)

/**
 * The reviewed component registry this build numbers from, whether or not anybody declared one.
 *
 * Read off the **root** project, so every module of a build agrees. A build that never applied
 * `dev.wildware.udea.game-gates` has no extension to read and gets the default path, which is
 * the same path the extension would have defaulted to.
 */
public fun Project.netComponentsFile(): File =
    rootProject.extensions.findByType<UdeaNetComponentsExtension>()?.registry?.orNull?.asFile
        ?: rootProject.layout.projectDirectory.file(UdeaNetComponents.FILE_NAME).asFile

/**
 * Hands this module's KSP run the project's component id space, if the module runs KSP at all.
 *
 * In `afterEvaluate` because the root project's `udeaNetComponents { }` block and this module's own
 * `ksp { }` block both run after the conventions do, and because a module that applies the KSP
 * plugin does so in its `plugins { }` block - so the extension exists by the time this reads for
 * it and does not yet when the convention is applied.
 *
 * A module with no KSP plugin gets nothing, silently, which is correct: most modules do not run
 * the processor. A module that runs it and has no registry file gets no option, also silently -
 * and `UdeaSymbolProcessor` is what turns that into a located build failure, but only for a
 * module that actually emits a wire protocol.
 */
public fun Project.applyNetComponentsToKsp() {
    afterEvaluate {
        val ksp = extensions.findByName(KspArgs.EXTENSION) ?: return@afterEvaluate
        val file = netComponentsFile()
        val text = providers
            .fileContents(layout.projectDirectory.file(file.invariantSeparatorsPath))
            .asText
            .orNull
            ?: return@afterEvaluate
        val value = when (val parsed = UdeaNetComponents.parse(text)) {
            is UdeaNetComponents.Parse.Success -> UdeaNetComponents.optionValue(parsed.components)
            is UdeaNetComponents.Parse.Failure -> throw GradleException("${file.absolutePath}: ${parsed.problem}")
        }
        KspArgs.set(path, ksp, UdeaNetComponents.KSP_OPTION, value)
    }
}

/**
 * Calls `arg(key, value)` on a KSP extension this build cannot name a type for.
 *
 * Reflection, at configuration time, over one method whose signature is part of KSP's public
 * Gradle DSL. The alternative was a second KSP Gradle plugin on `build-logic`'s classpath at a
 * version the catalog does not govern, which is the thing [UdeaModuleRegistry] refuses.
 */
internal object KspArgs {

    /** The extension name the KSP Gradle plugin registers, and the block a build script writes. */
    const val EXTENSION: String = "ksp"

    /**
     * @param projectPath the module, for the message.
     * @param extension the object registered under [EXTENSION].
     * @throws GradleException if it has no `arg(String, String)` - which means KSP's DSL moved,
     *   or something else has taken the name. Either way the option would otherwise be dropped
     *   in silence and every module would be numbered from its own symbols again.
     */
    fun set(projectPath: String, extension: Any, key: String, value: String) {
        val method = extension.javaClass.methods.firstOrNull { method ->
            method.name == "arg" &&
                method.parameterCount == 2 &&
                method.parameterTypes.all { it == String::class.java }
        } ?: throw GradleException(
            "$projectPath has an extension called '$EXTENSION' of type " +
                "${extension.javaClass.name}, which has no arg(String, String). Udea passes the " +
                "component id space to the processor through it, and dropping the option would " +
                "number every module from its own symbols starting at 0.",
        )
        method.invoke(extension, key, value)
    }
}

/**
 * Registers [UdeaNetComponents.WRITE_TASK] on the root project of a Udea build.
 *
 * ## Why it depends on no task
 *
 * Because the build it exists to rescue is a build that just failed. The processor reports every
 * `@Replicated` component it saw into [UdeaNetComponents.manifestResourcePath] **before** it
 * checks the id space, so the KSP run that fails for want of a registry still leaves behind the
 * list the registry needs. A `dependsOn` on those KSP tasks would make this task unreachable in
 * exactly that state, which is the only state anybody needs it in.
 *
 * The same reason is why the manifests are not declared as inputs: they are another task's
 * declared outputs, and Gradle would either demand a dependency on the task that cannot run, or
 * refuse the build for using an output without one. So this reads what the last build left, and
 * says so - it prints every manifest it read, so a reader can see which module's list is missing
 * rather than wondering why a name did not appear. A module with no `@Replicated` component
 * writes no manifest at all, which is why an absent one is silence rather than a removal: this
 * task only ever adds, and deleting a name stays a hand edit.
 *
 * @param buildDirectories every project of this build's build directory, captured at
 *   configuration time so the task holds nothing the configuration cache cannot serialise.
 */
public fun Project.registerWriteNetComponents(buildDirectories: List<File>): TaskProvider<Task> =
    tasks.register(UdeaNetComponents.WRITE_TASK) {
        group = "verification"
        description =
            "Writes ${UdeaNetComponents.FILE_NAME} from the @Replicated components the last " +
                "build compiled. Review the diff: a component's position in it is its id on the wire."
        // A registry is a decision, so it is rewritten when asked and never from cache.
        outputs.upToDateWhen { false }
        val directories = buildDirectories
        val target = netComponentsFile()
        doLast {
            val manifests = directories.asSequence()
                .filter(File::isDirectory)
                .flatMap { it.resolve("generated/ksp").walkTopDown() }
                .filter { it.isFile && it.name.endsWith("-${UdeaNetComponents.MANIFEST_NAME}") }
                .sortedBy { it.invariantSeparatorsPath }
                .toList()
            if (manifests.isEmpty()) {
                throw GradleException(
                    "no module reported its @Replicated components, so there is nothing to write " +
                        "and an empty ${UdeaNetComponents.FILE_NAME} would be the per-module " +
                        "numbering this file exists to replace. Run a build first: the processor " +
                        "writes each module's list as it compiles, and it writes it even on the " +
                        "run that then fails for want of this file.",
                )
            }
            val discovered = manifests.flatMap { manifest ->
                manifest.readLines().map(String::trim).filter(String::isNotEmpty)
            }
            manifests.forEach { logger.lifecycle("read ${it.absolutePath}") }
            when (val merged = UdeaNetComponents.merge(target.takeIf(File::isFile)?.readText(), discovered)) {
                is UdeaNetComponents.Merge.Failure -> throw GradleException(merged.problem)
                is UdeaNetComponents.Merge.Rewrite -> {
                    target.parentFile?.mkdirs()
                    target.writeText(merged.text)
                    logger.lifecycle(
                        if (merged.added.isEmpty()) {
                            "${target.absolutePath} already named every component this build compiled"
                        } else {
                            "${target.absolutePath}: added ${merged.added.joinToString()}. Every " +
                                "name at or after the first of those has a new component type id."
                        },
                    )
                }
            }
        }
    }
