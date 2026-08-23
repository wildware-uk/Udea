package dev.wildware.udea.gradle

/**
 * The `gamebridge.json` document: how a project says it is started, once, so that no caller ever
 * types a build command or picks a port.
 *
 * ## Why the port has to reach the game from here
 *
 * `launch_instance` picks a free port, substitutes it into [command], starts the process and polls
 * `/health` until it answers. Every part of that rests on one thing: `{port}` in the command line
 * must arrive as `-Dudea.agent.port` inside the game's JVM. A declaration whose `{port}` reaches
 * only Gradle - and not the forked game - launches a game the bridge then reports as dead, which
 * looks exactly like a crash and is not one.
 *
 * ## Rendered here rather than by a serialiser
 *
 * `udea-gradle` has no JSON library and does not want one: a build plugin's dependencies are the
 * build's dependencies. The document is nine fields, so it is written by hand and the escaping is
 * tested rather than assumed - `launch.cwd` on Windows is full of backslashes, and an unescaped
 * one produces a file the bridge's `JSON.parse` rejects with an error naming a column number.
 */
public class LaunchDeclaration(
    /** How the project names itself. Shown by `list_instances`. */
    public val name: String,
    /** The shell command line. Must contain `{port}`. */
    public val command: String,
    /** Working directory, resolved **relative to `gamebridge.json` itself**. */
    public val cwd: String = ".",
    /** Ports the launcher may claim. */
    public val portRange: String = DEFAULT_PORT_RANGE,
    /** How long to wait for `/health`. */
    public val readyTimeoutMs: Long = DEFAULT_READY_TIMEOUT_MS,
    /** Extra environment for the launched process. */
    public val env: Map<String, String> = emptyMap(),
) {

    init {
        require(name.isNotBlank()) { "a launch declaration needs a name" }
        require(command.contains(PORT_PLACEHOLDER)) {
            "launch.command must contain $PORT_PLACEHOLDER - substituting the port into the " +
                "command line is the entire mechanism by which the bridge chooses one. Got: $command"
        }
        require(readyTimeoutMs > 0) { "readyTimeoutMs must be positive, was $readyTimeoutMs" }
        require(PORT_RANGE_SHAPE.matches(portRange)) {
            "portRange is <low>-<high>, for example $DEFAULT_PORT_RANGE; was $portRange"
        }
    }

    /**
     * The document, deterministic to the byte.
     *
     * Keys in a fixed order and the environment sorted, because the task is cacheable and two runs
     * that produced different bytes for the same inputs would make it permanently out of date.
     */
    public fun render(): String = buildString {
        appendLine("{")
        appendLine("""  "name": ${quote(name)},""")
        appendLine("""  "launch": {""")
        appendLine("""    "command": ${quote(command)},""")
        appendLine("""    "cwd": ${quote(cwd)},""")
        appendLine("""    "portRange": ${quote(portRange)},""")
        appendLine("""    "readyTimeoutMs": $readyTimeoutMs,""")
        append("""    "env": {""")
        if (env.isEmpty()) {
            appendLine("}")
        } else {
            appendLine()
            val sorted = env.toSortedMap()
            sorted.entries.forEachIndexed { index, (key, value) ->
                append("""      ${quote(key)}: ${quote(value)}""")
                appendLine(if (index == sorted.size - 1) "" else ",")
            }
            appendLine("    }")
        }
        appendLine("  }")
        appendLine("}")
    }

    override fun toString(): String = "LaunchDeclaration($name, $command)"

    public companion object {

        /** What the bridge substitutes the chosen port into. */
        public const val PORT_PLACEHOLDER: String = "{port}"

        /**
         * `7820-7839`, deliberately clear of 7777 and 7800-7810.
         *
         * Those are the ports people hand out by hand, so a launcher that claimed them would
         * collide with the instance a developer started themselves - the one case where the
         * collision is silent, because both are the same game.
         */
        public const val DEFAULT_PORT_RANGE: String = "7820-7839"

        /**
         * 180 seconds.
         *
         * A cold Gradle build plus a JVM genuinely takes tens of seconds. Lowering this to make a
         * test finish sooner turns a slow first build into a launch failure, which is reported as
         * a boot error with the child's own output and sends the reader looking for a crash that
         * did not happen.
         */
        public const val DEFAULT_READY_TIMEOUT_MS: Long = 180_000

        /** The file name the bridge walks up the tree to find. */
        public const val FILE_NAME: String = "gamebridge.json"

        private val PORT_RANGE_SHAPE = Regex("[0-9]{1,5}-[0-9]{1,5}")

        /** JSON string escaping. Backslashes matter: `launch.cwd` is a Windows path half the time. */
        public fun quote(value: String): String = buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character < ' ') {
                        append("\\u").append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
            append('"')
        }
    }
}
