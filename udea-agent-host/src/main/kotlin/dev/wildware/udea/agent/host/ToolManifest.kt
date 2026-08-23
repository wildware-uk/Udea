package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.AgentToolDef
import dev.wildware.udea.agent.Json

/**
 * The rendered body of `GET /tools`: what this game can be told to do, in its own words.
 *
 * ## Rendered once, at start-up
 *
 * The manifest is immutable for the life of a process — the tools come from `ServiceLoader` at
 * boot and nothing adds one later — so it is built once and the handler writes a `String`. That
 * keeps the HTTP thread's work to a socket write, which matters because `/tools` is fetched on
 * every bridge reconnect and after every restart detection.
 *
 * ## Toolsets are derived from the tool name, not from the module
 *
 * The contract asks for toolsets "named for **what a caller is trying to do**, not for how your
 * code is arranged" (`game-bridge-mcp` README §1). A Gradle module is exactly how the code is
 * arranged, so grouping by `ToolModule.moduleName` would publish `UdeaRender` and `Moba` as
 * toolset names and tell an agent nothing. The tool's own name carries the group instead —
 * `render.screenshot`, `world.spawn_blueprint` — and the prefix before the first `.` is the
 * toolset. A tool with no prefix lands in [DEFAULT_TOOLSET].
 */
public class ToolManifest private constructor(
    /** The rendered document, served verbatim. */
    public val json: String,
    /** Toolset names in the document, ascending. For tests and for a startup log line. */
    public val toolsetNames: List<String>,
) {

    override fun toString(): String = "ToolManifest(${toolsetNames.joinToString()})"

    public companion object {

        /** Where a tool whose name carries no `<toolset>.` prefix is published. */
        public const val DEFAULT_TOOLSET: String = "game"

        /**
         * Renders [tools] as the manifest for [identity].
         *
         * Descriptions and schemas are the generated ones, verbatim: `inputSchema` is spliced in
         * raw because the contract says "if you already have JSON Schema, send it instead of
         * `args` and it is used verbatim". Both are sent — `args` for a tolerant reader, the
         * schema for a strict one — and the bridge prefers the schema.
         */
        public fun of(identity: GameIdentity, tools: List<AgentToolDef<*>>): ToolManifest {
            val grouped = tools.sortedBy { it.name }.groupBy { toolsetOf(it.name) }
            val names = grouped.keys.sorted()
            val json = Json.render {
                obj("game") {
                    put("name", identity.name)
                    put("version", identity.version)
                    put("protocol", identity.protocol)
                }
                arr("toolsets") {
                    names.forEach { toolset ->
                        element {
                            put("name", toolset)
                            put("description", describe(toolset))
                            arr("tools") {
                                grouped.getValue(toolset).forEach { tool -> element { renderTool(tool) } }
                            }
                        }
                    }
                }
            }
            return ToolManifest(json, names)
        }

        /** The toolset [toolName] belongs to. */
        public fun toolsetOf(toolName: String): String {
            val dot = toolName.indexOf('.')
            return if (dot <= 0) DEFAULT_TOOLSET else toolName.substring(0, dot)
        }

        private fun Json.renderTool(tool: AgentToolDef<*>) {
            put("name", tool.name)
            put("description", tool.description)
            // `command` is what the bridge puts in `?cmd=`. It equals the name here, and is
            // emitted anyway: the field defaults to the name, but a reader that has been handed
            // it never has to reconstruct one, and the day a tool is renamed for the model
            // without renaming the command this is the field that has to change.
            put("command", tool.name)
            arr("args") {
                tool.args.forEach { arg ->
                    element {
                        put("name", arg.name)
                        put("type", arg.type)
                        put("description", arg.description)
                        put("required", arg.required)
                        put("default", arg.default)
                    }
                }
            }
            key("inputSchema")
            raw(tool.inputSchema)
        }

        /**
         * One line about a toolset, for the model.
         *
         * Derived rather than declared because `AgentToolDef` carries no toolset description and
         * inventing an annotation for one is out of this module's scope. It is honest about what
         * it knows: the group name and that the tools in it are listed. A toolset that wants
         * better prose should get it from the codegen side, where the declaration lives.
         */
        private fun describe(toolset: String): String = when (toolset) {
            DEFAULT_TOOLSET -> "Tools this game publishes without a toolset prefix."
            else -> "The $toolset toolset. Each tool below says what it does and when to use it."
        }
    }
}
