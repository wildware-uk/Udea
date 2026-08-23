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
 * @param description one line describing the argument for the model. A blank description is
 *   a build error: a JSON Schema property with no `description` tells the model nothing about
 *   what to put in it.
 * @param required whether the schema marks the argument required. Defaults to `true`; a
 *   Kotlin parameter with a default value is emitted as optional regardless.
 * @param default the value used when an optional argument is absent, written as the text an
 *   agent would have sent. Empty means "no default".
 *
 *   It has to be written here, rather than read off the Kotlin parameter's own default,
 *   because KSP can see *that* a parameter has a default but never the expression that
 *   produces it. The generated dispatcher therefore always passes an explicit value, and this
 *   is the one place both it and the published manifest can read that value from — so an
 *   optional, non-nullable argument without a `default` is a build error rather than a
 *   manifest that advertises a default the tool does not actually have.
 *
 *   A **nullable** parameter is the other half of that rule and is published as optional with
 *   no default: absent means `null`, which the dispatcher passes and the tool decides about. A
 *   `default` on one is therefore a build error too — it would be advertised in the manifest
 *   and never once used — and so is a Kotlin `= ...` on one, for the same reason a Kotlin
 *   default is unreadable anywhere else here.
 *
 * Retention is [AnnotationRetention.BINARY]: it feeds schema generation at build time only.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Arg(
    val description: String = "",
    val required: Boolean = true,
    val default: String = "",
)
