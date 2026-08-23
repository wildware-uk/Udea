package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentToolArg
import dev.wildware.udea.agent.AgentToolDef
import dev.wildware.udea.agent.Json
import dev.wildware.udea.agent.dispatch.AgentContext
import dev.wildware.udea.agent.dispatch.ToolIndex
import kotlin.reflect.KClass

/**
 * One hand-written engine tool, in the shape `udea-codegen` emits for an `@AgentTool`.
 *
 * ## Why the engine's own toolsets are written rather than generated
 *
 * `@AgentTool` derives the tool's name from the function name and the *toolset* from the
 * declaring class's name, snake-cased (`AgentNaming`). The engine's toolsets are `world`,
 * `time`, `events` and `diag`, and the host groups them by the `<toolset>.` prefix on the tool
 * name (`ToolManifest.toolsetOf`) - a prefix a generated name cannot carry, since
 * `AgentNaming.NAME_FORMAT` is `[a-z][a-z0-9_]*`. Generating them would also mean a Kotlin
 * class literally called `World` in a module that imports Fleks' `World` on almost every line.
 *
 * So these are written against exactly the interfaces the generator writes against - the same
 * [AgentToolDef], the same [AgentToolArg], the same `ToolModule`, discovered through the same
 * `ServiceLoader` - and the contract already allows it in as many words: *"a game may also
 * implement it by hand"*. What is lost is the compile-time description gate, which is why
 * [dev.wildware.udea.agent.dispatch.ToolIndex] applies the same rule at index construction:
 * see `ToolIndex.Builder.build`.
 *
 * @param T the toolset class supplying the receiver, exactly as for a generated tool.
 */
public class EngineToolDef<in T : Any>(
    override val name: String,
    override val description: String,
    override val owner: KClass<*>,
    override val args: List<AgentToolArg> = emptyList(),
    private val body: (T, AgentCommand) -> Any?,
) : AgentToolDef<T> {

    init {
        require(name.isNotBlank()) { "a tool needs a name an agent can address it by" }
    }

    /** Rendered once at construction: a tool's schema is fixed for the life of the process. */
    override val inputSchema: String = ToolSchema.render(args)

    override fun invoke(receiver: T, command: AgentCommand): Any? = body(receiver, command)

    override fun toString(): String = "EngineToolDef($name)"
}

/**
 * A tool that needs the [AgentContext] its command is running under.
 *
 * ## The one thing a receiver cannot supply
 *
 * A generated tool is deliberately never handed a context: it reaches the world through the
 * toolset it is a member of, which the host constructed with whatever that tool mutates
 * (`ToolIndex.invoke`). That covers every tool that *changes* something.
 *
 * It does not cover a tool that has to run **outside** the barrier drain it was called in.
 * `SimBarrier.drain` refuses to re-enter, and `Simulation.step` drains the barrier - so
 * `time.step`, `time.fast_forward` and `time.rewind` cannot do their work where they are
 * called, and the only way to run after the tick and still answer for it is
 * [AgentContext.answerLater]. That lives on the context, and there is nowhere else to get one.
 *
 * Kept as a separate interface rather than widening [AgentToolDef], so the generated surface is
 * untouched and the contract in `docs/contracts/agent-tools.md` still describes it exactly.
 * [ToolIndex] checks for this type and passes the context only to a tool that asked for one.
 */
public interface ContextualToolDef<in T> : AgentToolDef<T> {

    /** Runs the tool with the context of the command being served. */
    public fun invoke(receiver: T, command: AgentCommand, context: AgentContext): Any?
}

/**
 * A hand-written engine tool that needs its [AgentContext]. See [ContextualToolDef].
 *
 * [invoke]`(receiver, command)` is unreachable through [ToolIndex] and throws rather than
 * quietly running the tool without a context: a tool in this class needs one, and doing half
 * the work is worse than refusing.
 */
public class EngineContextToolDef<in T : Any>(
    override val name: String,
    override val description: String,
    override val owner: KClass<*>,
    override val args: List<AgentToolArg> = emptyList(),
    private val body: (T, AgentCommand, AgentContext) -> Any?,
) : ContextualToolDef<T> {

    init {
        require(name.isNotBlank()) { "a tool needs a name an agent can address it by" }
    }

    override val inputSchema: String = ToolSchema.render(args)

    override fun invoke(receiver: T, command: AgentCommand): Any? =
        throw UnsupportedOperationException(
            "$name needs the AgentContext of the command it is serving; call the three-argument " +
                "invoke, which is what ToolIndex does for a ContextualToolDef",
        )

    override fun invoke(receiver: T, command: AgentCommand, context: AgentContext): Any? =
        body(receiver, command, context)

    override fun toString(): String = "EngineContextToolDef($name)"
}

/**
 * One published argument. Shorthand for an [AgentToolArg] with the JSON Schema type spelled
 * out, so a toolset declaration reads as a table.
 */
public fun agentArg(
    name: String,
    type: String,
    description: String,
    required: Boolean = true,
    default: String? = null,
): AgentToolArg = AgentToolArg(name, type, description, required, default)

/**
 * The JSON Schema for a hand-written tool, in the shape `udea-codegen` emits.
 *
 * Every rule here mirrors `ToolManifest.schemaOf` deliberately, because the two documents end
 * up side by side in one `GET /tools` and a model that has to notice which half of the manifest
 * a tool came from is a model doing the engine's job:
 *
 * - `additionalProperties: false`, so an argument the tool does not accept is reported rather
 *   than silently ignored;
 * - a default folded into the property's `description` and **never** emitted as `default`, which
 *   is what the bridge does when it builds a schema itself - a `default` on a strictly-typed
 *   property is something a strict client is entitled to reject;
 * - `required` omitted entirely when nothing is required, rather than written as `[]`.
 */
public object ToolSchema {

    /** The JSON Schema dialect the emitted documents declare. Same constant as the generator's. */
    public const val DIALECT: String = "https://json-schema.org/draft/2020-12/schema"

    /** One line of JSON, served verbatim as `tools[].inputSchema`. */
    public fun render(args: List<AgentToolArg>): String = Json.render {
        put("\$schema", DIALECT)
        put("type", "object")
        obj("properties") {
            args.forEach { arg ->
                obj(arg.name) {
                    put("type", arg.type)
                    put("description", describedWithDefault(arg))
                }
            }
        }
        val required = args.filter { it.required }
        if (required.isNotEmpty()) {
            arr("required") { required.forEach { value(it.name) } }
        }
        put("additionalProperties", false)
    }

    private fun describedWithDefault(arg: AgentToolArg): String = when {
        arg.default != null -> "${arg.description} (default ${arg.default})"
        !arg.required -> "${arg.description} (optional; omit for none)"
        else -> arg.description
    }
}
