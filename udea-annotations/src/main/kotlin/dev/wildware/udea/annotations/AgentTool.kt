package dev.wildware.udea.annotations

/**
 * Marks a function as one tool on the agent's MCP surface.
 *
 * Consumed by the **`udea-codegen` KSP2 processor**, which emits the tool manifest and
 * its JSON Schema from the function signature, plus the `ToolModule` ServiceLoader entry
 * that `udea-agent` discovers without a magic package (spec 3.2, spec 5 "Id assignment").
 * The generated dispatcher calls the function directly, so the tool surface survives R8.
 *
 * @param name the tool's MCP name. Empty means "derive it from the function name".
 * @param description one line describing the tool for the model. Empty means "take the
 *   function's KDoc summary", which the **`udea-compiler-plugin`** propagates, since KSP
 *   cannot read KDoc (spec 3.2).
 *
 * Retention is [AnnotationRetention.BINARY]: the manifest, the schema and the dispatch
 * table are all generated files, so nothing looks this annotation up at runtime.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class AgentTool(
    val name: String = "",
    val description: String = "",
)

/**
 * Describes one parameter of an [AgentTool] function.
 *
 * Consumed by the **`udea-codegen` KSP2 processor**, which turns each annotated parameter
 * into a named, described property of the tool's JSON Schema. An unannotated parameter
 * still appears in the schema; `@Arg` only supplies the model-facing text and the
 * required flag.
 *
 * @param description one line describing the argument for the model.
 * @param required whether the schema marks the argument required. Defaults to `true`; a
 *   Kotlin parameter with a default value is emitted as optional regardless.
 *
 * Retention is [AnnotationRetention.BINARY]: it feeds schema generation at build time only.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Arg(
    val description: String = "",
    val required: Boolean = true,
)

/**
 * Groups the [AgentTool] functions of one class into a single named MCP toolset.
 *
 * Consumed by the **`udea-codegen` KSP2 processor**, which emits one `ToolModule`
 * ServiceLoader entry per annotated class and prefixes the contained tools' names with
 * [name], so two subsystems can each expose a `describe` tool without colliding.
 *
 * @param name the toolset's name. Empty means "derive it from the class name".
 *
 * Retention is [AnnotationRetention.BINARY]: grouping is resolved into the generated
 * registry at build time.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class AgentToolset(
    val name: String = "",
)

/**
 * Marks a property as agent-visible context that is neither replicated nor snapshotted.
 *
 * Consumed by the **`udea-codegen` KSP2 processor**, which adds the property to the
 * generated agent field table - so `describe_entity` can report it - while assigning it
 * no network bit index and no snapshot slot. It is the read-only counterpart to
 * [Net] with `agentWritable = true`: use it for derived or diagnostic state the agent
 * should observe but that must never enter the simulation's rewind or the wire.
 *
 * Retention is [AnnotationRetention.BINARY]: the field table is generated code, so the
 * agent reads values through `Replicator.getField` rather than reflecting on this marker.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class AgentState
