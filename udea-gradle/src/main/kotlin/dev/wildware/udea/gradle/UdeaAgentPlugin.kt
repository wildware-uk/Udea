package dev.wildware.udea.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction
import org.gradle.process.CommandLineArgumentProvider

/**
 * How a project overrides the generated launch declaration and the agent wiring.
 *
 * Every field has a default derived from the project, so the common case is applying the plugin
 * and writing nothing. The overrides exist because "how is this game started" is a per-project
 * fact that a convention can get wrong - a project with a bespoke `run` task, or one that has to
 * pass `--console=plain` for its output to be readable in a launcher log.
 */
public abstract class UdeaAgentExtension {

    /** How the game names itself to `list_instances`. Defaults to the project name. */
    public abstract val name: Property<String>

    /** Ports `launch_instance` may claim. Defaults to [LaunchDeclaration.DEFAULT_PORT_RANGE]. */
    public abstract val portRange: Property<String>

    /** How long the bridge waits for `/health`. Defaults to 180000. */
    public abstract val readyTimeoutMs: Property<Long>

    /** The command line, containing `{port}`. Defaults to `<wrapper> <path>:run -PdebugPort={port}`. */
    public abstract val command: Property<String>

    /** Extra environment for the launched process. */
    public abstract val env: MapProperty<String, String>

    /** The Gradle property a launcher passes the port in. Defaults to `debugPort`. */
    public abstract val portProperty: Property<String>

    /**
     * `RenderMode` a launched instance runs in. Defaults to `Offscreen`.
     *
     * Overridable because a machine with no GL driver - a container, a CI worker, this
     * repository's own smoke check - cannot create the hidden LWJGL3 context `Offscreen` needs,
     * and `Headless` is the honest answer there rather than a launch failure. It is also
     * overridable per invocation with `-Pudea.render.mode=`.
     */
    public abstract val renderMode: Property<String>

    /**
     * Name of the debug-only source set the plugin creates. Defaults to `agent`.
     *
     * Its output and its classpath are the *only* place `udea-agent-host` appears, which is what
     * keeps `runtimeClasspath` - the classpath `ReleaseRules.CLASSPATH_RULE` scans and the jar is
     * packaged from - free of it.
     */
    public abstract val sourceSetName: Property<String>

    /** Package the generated [AgentBuildFlagsSource] object is declared in. */
    public abstract val flagsPackage: Property<String>
}

/** Writes `gamebridge.json`. Cacheable, and deterministic to the byte. */
@CacheableTask
public abstract class UdeaGenerateLaunchDeclarationTask : DefaultTask() {

    /** How the game names itself. */
    @get:Input
    public abstract val gameName: Property<String>

    /** The command line, containing `{port}`. */
    @get:Input
    public abstract val command: Property<String>

    /** Working directory, relative to the declaration file. */
    @get:Input
    public abstract val cwd: Property<String>

    /** Ports the launcher may claim. */
    @get:Input
    public abstract val portRange: Property<String>

    /** How long the bridge waits for `/health`. */
    @get:Input
    public abstract val readyTimeoutMs: Property<Long>

    /** Extra environment for the launched process. */
    @get:Input
    public abstract val env: MapProperty<String, String>

    /** `gamebridge.json`, at the project root. */
    @get:OutputFile
    public abstract val declaration: RegularFileProperty

    /** Renders and writes. */
    @TaskAction
    public fun generate() {
        val document = LaunchDeclaration(
            name = gameName.get(),
            command = command.get(),
            cwd = cwd.get(),
            portRange = portRange.get(),
            readyTimeoutMs = readyTimeoutMs.get(),
            env = env.get(),
        ).render()
        declaration.get().asFile.apply {
            parentFile.mkdirs()
            writeText(document)
        }
    }
}

/**
 * Writes the per-variant `UdeaAgentBuildFlags` object into the debug source set.
 *
 * See [AgentBuildFlagsSource] for why the flag is generated into the game rather than left as
 * the hand-written `true` in `udea-agent-host`.
 */
@CacheableTask
public abstract class UdeaGenerateAgentBuildFlagsTask : DefaultTask() {

    /** Package to declare. */
    @get:Input
    public abstract val flagsPackage: Property<String>

    /** `false` for `-Pudea.release=true`. */
    @get:Input
    public abstract val agentAllowed: Property<Boolean>

    /** Root of the generated source tree; the package directories are made under it. */
    @get:OutputDirectory
    public abstract val outputDirectory: DirectoryProperty

    /** Renders and writes. */
    @TaskAction
    public fun generate() {
        val root = outputDirectory.get().asFile
        root.deleteRecursively()
        val packageName = flagsPackage.get()
        val directory = root.resolve(packageName.replace('.', '/'))
        directory.mkdirs()
        directory.resolve(AgentBuildFlagsSource.FILE_NAME)
            .writeText(AgentBuildFlagsSource.render(packageName, agentAllowed.get()))
    }
}

/**
 * The launch half of `game-bridge-mcp` conformance: `gamebridge.json`, the debug-only source set
 * the agent entry point lives in, and the wiring that makes `{port}` reach the game.
 *
 * ## The three parts are one mechanism
 *
 * A declaration without the run wiring produces a command line the bridge will happily execute
 * and a game that binds nothing, which is reported as a boot failure. The wiring without a
 * declaration leaves `launch_instance` with nothing to launch. An entry point that can reach
 * `AgentHost` on `runtimeClasspath` fails `ReleaseRules.CLASSPATH_RULE` and cannot ship. So all
 * three are applied together, by one plugin, and the property name that carries the port between
 * the first two is [UdeaAgentExtension.portProperty] in both places rather than a string written
 * twice.
 *
 * ## Nothing here reaches the game's runtime classpath
 *
 * `gradleApi()` is `compileOnly` on this module (the `udea.gradle-plugin` convention) and no game
 * module depends on this project, which is what stops the defect the old `gradle-plugin` had: it
 * declared `gradleApi()` as `implementation` and games depended on it, so the whole Gradle API
 * shipped on the game's runtime classpath.
 *
 * ## The debug source set
 *
 * `udea-agent-host` is added to a **source set** of its own - `agent` by default - rather than to
 * a configuration bolted onto the main one. That is deliberate and it is stronger than
 * `runtimeOnly` or than a bespoke resolvable configuration:
 *
 * - `runtimeClasspath` never resolves it, so `ReleaseRules.CLASSPATH_RULE` passes for a reason
 *   rather than by omission;
 * - the **jar** never contains the agent entry point either, because `jar` packages `main`. A
 *   `compileOnly` arrangement would have kept the classpath clean and still shipped a `main`
 *   class whose first statement is a `NoClassDefFoundError`;
 * - `src/agent/kotlin` is where a reader looks to find every line of this game that knows the
 *   agent surface exists.
 */
public class UdeaAgentPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("udeaAgent", UdeaAgentExtension::class.java)
        extension.name.convention(project.name)
        extension.portRange.convention(LaunchDeclaration.DEFAULT_PORT_RANGE)
        extension.readyTimeoutMs.convention(LaunchDeclaration.DEFAULT_READY_TIMEOUT_MS)
        extension.portProperty.convention(DEFAULT_PORT_PROPERTY)
        extension.renderMode.convention(DEFAULT_RENDER_MODE)
        extension.sourceSetName.convention(DEFAULT_SOURCE_SET)
        extension.flagsPackage.convention(
            AgentBuildFlagsSource.defaultPackage("${project.group}", project.name),
        )
        extension.command.convention(
            extension.portProperty.map { property ->
                "${GradleWrapperCommand.current()} ${project.path}:$RUN_TASK " +
                    "-P$property=${LaunchDeclaration.PORT_PLACEHOLDER} --console=plain"
            },
        )

        val generate = project.tasks.register(GENERATE_TASK, UdeaGenerateLaunchDeclarationTask::class.java)
        generate.configure(
            gradleAction { task: UdeaGenerateLaunchDeclarationTask ->
                task.group = "udea"
                task.description = "Writes gamebridge.json so game-bridge-mcp can launch this project."
                task.gameName.set(extension.name)
                task.command.set(extension.command)
                task.portRange.set(extension.portRange)
                task.readyTimeoutMs.set(extension.readyTimeoutMs)
                task.env.set(extension.env)
                // `.` always, and relative to the declaration rather than to wherever the MCP
                // client started the bridge - which is almost never the project directory.
                task.cwd.set(".")
                task.declaration.set(
                    project.rootProject.layout.projectDirectory.file(LaunchDeclaration.FILE_NAME),
                )
            },
        )

        // A declaration that describes a run task nobody has regenerated since it changed is worse
        // than none, so assembling or running regenerates it.
        project.tasks
            .matching(gradleSpec { task: Task -> task.name == "assemble" || task.name == RUN_TASK })
            .configureEach(gradleAction { task: Task -> task.dependsOn(generate) })

        // Reacted to rather than assumed: the source set, and so the run wiring, needs the
        // `java` plugin's SourceSetContainer. `getByType` at apply time would order this plugin
        // after the Kotlin/Java one forever, which is the sort of rule a build script breaks by
        // sorting its `plugins {}` block alphabetically.
        project.pluginManager.withPlugin("java") {
            val agentSourceSet = createAgentSourceSet(project, extension)
            project.tasks.withType(JavaExec::class.java).configureEach(
                gradleAction { task: JavaExec ->
                    if (task.name == RUN_TASK) {
                        task.classpath(agentSourceSet.runtimeClasspath)
                        task.jvmArgumentProviders.add(AgentJvmArguments(project, extension))
                    }
                },
            )
        }
    }

    /**
     * Creates the debug-only source set, its dependency on `udea-agent-host`, and the generated
     * flags on its source roots.
     *
     * The generated directory goes on the source set's `java` roots rather than a Kotlin one,
     * because this module compiles against `gradleApi()` alone and has no Kotlin Gradle plugin
     * types to name. The Kotlin compilation the KGP creates for a source set sources that source
     * set's java directories as well as `src/<name>/kotlin`, so a generated `.kt` there is
     * compiled - which `:moba:compileAgentKotlin` demonstrates rather than assumes.
     */
    private fun createAgentSourceSet(project: Project, extension: UdeaAgentExtension): SourceSet {
        val java = project.extensions.getByType(JavaPluginExtension::class.java)
        val name = extension.sourceSetName.get()
        val main = java.sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        val agent = java.sourceSets.create(name)

        // Compiles against the game, and runs with it. `main.output` rather than a project
        // dependency because it is the same project: a self-dependency would be a cycle.
        agent.compileClasspath += main.output + project.configurations.getByName(main.compileClasspathConfigurationName)
        agent.runtimeClasspath += main.output + project.configurations.getByName(main.runtimeClasspathConfigurationName)

        // Only inside this repository. An external game applying the published plugin has no
        // `:udea-agent-host` project, and adding an unresolvable project dependency would fail
        // its build at resolution time with a message about a project path it has never heard of.
        // Such a game declares `agentImplementation("dev.wildware.udea:udea-agent-host:<version>")`
        // itself; here the project dependency is what keeps a source change visible without a
        // publish.
        if (project.rootProject.findProject(AGENT_HOST_PROJECT) != null) {
            project.dependencies.add(
                agent.implementationConfigurationName,
                project.dependencies.project(mapOf("path" to AGENT_HOST_PROJECT)),
            )
        }

        // `-Pudea.release=true` is the same switch `udeaVerifyRelease` keys on. Read through the
        // provider API so the configuration cache tracks it as an input rather than baking in
        // whichever invocation stored the cache.
        val releaseBuild = project.providers.gradleProperty(RELEASE_PROPERTY).map { it == "true" }.orElse(false)
        val generatedRoot = project.layout.buildDirectory.dir("generated/sources/udea-agent-flags")

        val flags = project.tasks.register(FLAGS_TASK, UdeaGenerateAgentBuildFlagsTask::class.java)
        flags.configure(
            gradleAction { task: UdeaGenerateAgentBuildFlagsTask ->
                task.group = "udea"
                task.description = "Writes the per-variant ${AgentBuildFlagsSource.CLASS_NAME}."
                task.flagsPackage.set(extension.flagsPackage)
                task.agentAllowed.set(releaseBuild.map { allowed -> !allowed })
                task.outputDirectory.set(generatedRoot)
            },
        )

        agent.java.srcDir(flags)
        return agent
    }

    /**
     * `-Dudea.agent.port=<port>` and `-Dudea.render.mode=<mode>`, or nothing at all.
     *
     * A [CommandLineArgumentProvider] rather than `systemProperty(...)` at configuration time,
     * because the port arrives as a Gradle property on the command line of *this* invocation:
     * reading it eagerly would bake whichever value the configuration cache was stored with into
     * every later run, and two `launch_instance` calls would then fight over one port.
     *
     * `Offscreen` rather than `Windowed` when a port is passed, because a launched instance is
     * being driven by an agent: it needs a real GL context so captures work, and does not need a
     * window on the developer's desktop. A `run` with no port is a developer's own run and is left
     * exactly as it was.
     */
    private class AgentJvmArguments(
        project: Project,
        extension: UdeaAgentExtension,
    ) : CommandLineArgumentProvider {

        private val port = extension.portProperty.flatMap { property ->
            project.providers.gradleProperty(property)
                .orElse(project.providers.gradleProperty(ALTERNATE_PORT_PROPERTY))
        }

        private val renderMode = project.providers.gradleProperty(RENDER_MODE_PROPERTY)
            .orElse(extension.renderMode)

        override fun asArguments(): Iterable<String> {
            val chosen = port.orNull ?: return emptyList()
            return listOf(
                "-D$AGENT_PORT_PROPERTY=$chosen",
                "-D$RENDER_MODE_PROPERTY=${renderMode.get()}",
            )
        }
    }

    public companion object {

        /** The plugin id, as declared in `META-INF/gradle-plugins`. */
        public const val PLUGIN_ID: String = "dev.wildware.udea.agent"

        /** The task that writes `gamebridge.json`. */
        public const val GENERATE_TASK: String = "udeaGenerateLaunchDeclaration"

        /** The task that writes the per-variant agent flag. */
        public const val FLAGS_TASK: String = "udeaGenerateAgentBuildFlags"

        /** The source set the agent entry point and `udea-agent-host` are confined to. */
        public const val DEFAULT_SOURCE_SET: String = "agent"

        /** The `JavaExec` task the port and the render mode are wired into. */
        public const val RUN_TASK: String = "run"

        /** The Gradle property a launcher passes the port in, by default. */
        public const val DEFAULT_PORT_PROPERTY: String = "debugPort"

        /** Accepted alongside `debugPort`, because both spellings are in circulation. */
        public const val ALTERNATE_PORT_PROPERTY: String = "agentPort"

        /** What the game reads. Must match `BuildFlags.PORT_PROPERTY`. */
        public const val AGENT_PORT_PROPERTY: String = "udea.agent.port"

        /**
         * What the host reads to choose a `RenderMode`, as a system property on the game and as a
         * Gradle property on the build that launches it. One spelling, both sides.
         */
        public const val RENDER_MODE_PROPERTY: String = "udea.render.mode"

        /** What a launched instance renders as unless told otherwise. */
        public const val DEFAULT_RENDER_MODE: String = "Offscreen"

        /** The switch `udeaVerifyRelease` keys on, and so the switch the agent flag keys on. */
        public const val RELEASE_PROPERTY: String = "udea.release"

        /** The debug-only module. */
        public const val AGENT_HOST_PROJECT: String = ":udea-agent-host"
    }
}
