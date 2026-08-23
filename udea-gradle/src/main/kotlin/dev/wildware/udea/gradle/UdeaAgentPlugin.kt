package dev.wildware.udea.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.CommandLineArgumentProvider

/**
 * How a project overrides the generated launch declaration.
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

    /** The command line, containing `{port}`. Defaults to `./gradlew <path>:run -PdebugPort={port}`. */
    public abstract val command: Property<String>

    /** Extra environment for the launched process. */
    public abstract val env: MapProperty<String, String>

    /** The Gradle property a launcher passes the port in. Defaults to `debugPort`. */
    public abstract val portProperty: Property<String>
}

/** Writes `gamebridge.json`. Cacheable, and deterministic to the byte. */
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
 * The launch half of `game-bridge-mcp` conformance: `gamebridge.json`, and the wiring that makes
 * its `{port}` reach the game.
 *
 * ## The two halves are one mechanism
 *
 * A declaration without the run wiring produces a command line the bridge will happily execute and
 * a game that binds nothing, which is reported as a boot failure. The wiring without a declaration
 * leaves `launch_instance` with nothing to launch. So they are applied together, by one plugin,
 * and the property name that carries the port between them is [UdeaAgentExtension.portProperty] in
 * both places rather than a string written twice.
 *
 * ## Nothing here reaches the game's runtime classpath
 *
 * `gradleApi()` is `compileOnly` on this module (the `udea.gradle-plugin` convention) and no game
 * module depends on this project, which is what stops the defect the old `gradle-plugin` had: it
 * declared `gradleApi()` as `implementation` and games depended on it, so the whole Gradle API
 * shipped on the game's runtime classpath.
 *
 * ## The debug configuration
 *
 * `udea-agent-host` is added to a configuration of its own, `udeaAgentRuntime`, which the run task
 * puts *in front of* the main runtime classpath. It is deliberately not `runtimeOnly`:
 * `ReleaseRules.CLASSPATH_RULE` fails a release build that resolves `:udea-agent-host` on
 * `runtimeClasspath`, and that rule is the thing standing between a shipped game and a live
 * remote-control API. A separate configuration gives a developer's `run` the agent surface while
 * leaving the classpath that gets packaged untouched.
 */
public class UdeaAgentPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("udeaAgent", UdeaAgentExtension::class.java)
        extension.name.convention(project.name)
        extension.portRange.convention(LaunchDeclaration.DEFAULT_PORT_RANGE)
        extension.readyTimeoutMs.convention(LaunchDeclaration.DEFAULT_READY_TIMEOUT_MS)
        extension.portProperty.convention(DEFAULT_PORT_PROPERTY)
        extension.command.convention(
            extension.portProperty.map { property ->
                "$GRADLE_WRAPPER ${project.path}:run -P$property=${LaunchDeclaration.PORT_PLACEHOLDER} " +
                    "--console=plain"
            },
        )

        val generate = project.tasks.register(
            GENERATE_TASK,
            UdeaGenerateLaunchDeclarationTask::class.java,
        ) { task ->
            task.group = "udea"
            task.description = "Writes gamebridge.json so game-bridge-mcp can launch this project."
            task.gameName.set(extension.name)
            task.command.set(extension.command)
            task.portRange.set(extension.portRange)
            task.readyTimeoutMs.set(extension.readyTimeoutMs)
            task.env.set(extension.env)
            // `.` always, and relative to the declaration rather than to wherever the MCP client
            // started the bridge - which is almost never the project directory.
            task.cwd.set(".")
            task.declaration.set(project.rootProject.layout.projectDirectory.file(LaunchDeclaration.FILE_NAME))
        }

        // A declaration that describes a run task nobody has regenerated since it changed is worse
        // than none, so assembling or running regenerates it.
        project.tasks.matching { it.name == "assemble" || it.name == "run" }
            .configureEach { it.dependsOn(generate) }

        val agentRuntime = project.configurations.create(AGENT_CONFIGURATION) { configuration ->
            configuration.isCanBeConsumed = false
            configuration.isCanBeResolved = true
            configuration.description =
                "The debug-only agent host, put on the run task's classpath and on no other."
        }
        project.dependencies.add(AGENT_CONFIGURATION, project.dependencies.project(mapOf("path" to AGENT_HOST_PROJECT)))

        project.tasks.withType(org.gradle.api.tasks.JavaExec::class.java).configureEach { task ->
            if (task.name != "run") return@configureEach
            task.classpath(agentRuntime)
            task.jvmArgumentProviders.add(AgentJvmArguments(project, extension))
        }
    }

    /**
     * `-Dudea.agent.port=<port>` and `-Dudea.render.mode=Offscreen`, or nothing at all.
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

        override fun asArguments(): Iterable<String> {
            val chosen = port.orNull ?: return emptyList()
            return listOf("-D$AGENT_PORT_PROPERTY=$chosen", "-D$RENDER_MODE_PROPERTY=Offscreen")
        }
    }

    public companion object {

        /** The task that writes `gamebridge.json`. */
        public const val GENERATE_TASK: String = "udeaGenerateLaunchDeclaration"

        /** The configuration carrying the debug-only agent host. */
        public const val AGENT_CONFIGURATION: String = "udeaAgentRuntime"

        /** The Gradle property a launcher passes the port in, by default. */
        public const val DEFAULT_PORT_PROPERTY: String = "debugPort"

        /** Accepted alongside `debugPort`, because both spellings are in circulation. */
        public const val ALTERNATE_PORT_PROPERTY: String = "agentPort"

        /** What the game reads. Must match `BuildFlags.PORT_PROPERTY`. */
        public const val AGENT_PORT_PROPERTY: String = "udea.agent.port"

        /** What the host reads to choose a `RenderMode`. */
        public const val RENDER_MODE_PROPERTY: String = "udea.render.mode"

        /** The debug-only module. */
        public const val AGENT_HOST_PROJECT: String = ":udea-agent-host"

        private const val GRADLE_WRAPPER: String = "./gradlew"
    }
}
