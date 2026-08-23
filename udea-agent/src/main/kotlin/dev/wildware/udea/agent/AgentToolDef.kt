package dev.wildware.udea.agent

import kotlin.reflect.KClass

/**
 * One published argument, in the shape `game-bridge-mcp` documents for `tools[].args[]`:
 * `{name, type, description, required, default}`.
 *
 * [type] is a JSON Schema type name, so one converter serves both the game's commands and the
 * bridge's own tools. [default] is `null` for "no default", and text otherwise - a manifest
 * serialised from a typed language renders defaults as strings, and the bridge folds them into
 * the description rather than emitting them into the schema.
 */
public data class AgentToolArg(
    public val name: String,
    public val type: String,
    public val description: String,
    public val required: Boolean,
    public val default: String?,
)

/**
 * One generated tool: what it is called, what it is for, what it accepts, and how to run it.
 *
 * Implemented by `udea-codegen`'s emitted `object <Owner><Fn>Tool`, one per `@AgentTool`
 * function; `docs/contracts/agent-tools.md` is the written form of this shape and the emitter
 * names it by fully-qualified name, so neither module depends on the other at compile time.
 *
 * @param T the declaring class - the toolset - which supplies the receiver. Typed rather than
 *   `Any`, so a dispatcher can never be handed the wrong toolset instance, and contravariant
 *   so an index can hold `AgentToolDef<*>`.
 */
public interface AgentToolDef<in T> {

    /** The MCP name an agent calls, lower_snake_case. */
    public val name: String

    /** What the tool does and when to reach for it: the text the model reasons over. */
    public val description: String

    /** The published arguments, in declaration order. */
    public val args: List<AgentToolArg>

    /** The tool's JSON Schema, served verbatim as `tools[].inputSchema`. */
    public val inputSchema: String

    /**
     * The toolset class this tool is a member of, as a class literal and never a name.
     *
     * [ToolIndex] needs it and there is no other way to get it: `T` is erased, so an index
     * holding `AgentToolDef<*>` cannot tell which of the toolset instances a host registered
     * this tool belongs to. Discovering it reflectively would put reflection on the agent
     * surface, which spec 3.1 rules out and R8 would break; a name would be a string the
     * shrinker cannot follow. A `::class` literal in generated code is a genuine reference,
     * so R8 keeps the class, and the pairing is checked once when the index is built rather
     * than on every call.
     */
    public val owner: KClass<*>

    /**
     * Runs the tool on the simulation thread. Throws [BadArgumentException] naming the argument
     * if one is missing or will not convert; anything else it throws is the tool's own failure,
     * which `AgentDispatcher` contains as `tool_threw`.
     */
    public fun invoke(receiver: T, command: AgentCommand): Any?
}

/**
 * One Gradle module's contribution to the agent's tool surface, found through `ServiceLoader`.
 *
 * The same mechanism `NetModule` uses and for the same reason: tools live in engine modules, a
 * shared module and the game at once, and no single KSP round sees them all. ServiceLoader
 * discovery is what lets `moba` declare a toolset without `udea-agent` knowing the game exists,
 * with no magic package and no classpath scan.
 */
public interface ToolModule {

    /** The Gradle module this index was generated for, in `UpperCamelCase`. */
    public val moduleName: String

    /** Every tool generated for this module, in ascending name order. */
    public val tools: List<AgentToolDef<*>>
}
