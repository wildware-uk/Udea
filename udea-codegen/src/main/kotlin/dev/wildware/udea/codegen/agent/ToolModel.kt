package dev.wildware.udea.codegen.agent

import com.squareup.kotlinpoet.ClassName

/**
 * One `@AgentTool` function, resolved down to everything three emitters need: the Kotlin
 * dispatcher, the JSON Schema and the manifest fragment the bridge reads.
 *
 * A model rather than three passes over the `KSFunctionDeclaration` because the three outputs
 * must agree by construction. A schema that advertises an argument the dispatcher does not
 * coerce is the exact drift the manifest exists to prevent, and it is invisible in every test
 * that reads only one of the two.
 */
internal data class ToolModel(
    /** The MCP name, snake_case, from `@AgentTool(name)` or derived from the function name. */
    val name: String,
    /** The model-facing text. Never blank; the description gate is `UDEA0008`. */
    val description: String,
    /** The declaring class or object: the toolset, and the receiver the dispatcher calls on. */
    val owner: ClassName,
    /** The toolset name the manifest groups this tool under: the owner's name, snake_cased. */
    val toolset: String,
    /** The Kotlin function to call. */
    val functionName: String,
    /** The generated object's simple name, e.g. `PlaygroundSpawnBlueprintTool`. */
    val objectName: String,
    /** In declaration order, which is the order the generated call passes them. */
    val args: List<ToolArgModel>,
)

/**
 * One parameter of a [ToolModel].
 *
 * [required] and [defaultText] are the two halves of the same question and are kept separate
 * because the bridge publishes both: `required: false` with no `default` means "may be
 * omitted, and then it is absent", which for a nullable parameter is exactly right.
 */
internal data class ToolArgModel(
    /** The Kotlin parameter name, which is also the JSON Schema property name. */
    val name: String,
    val kind: ArgKind,
    /** True when the parameter is `List<kind>`; the schema type becomes `array`. */
    val list: Boolean,
    /** The enum type, when [kind] is [ArgKind.ENUM]. */
    val enumType: ClassName?,
    /** The enum constant names, in declaration order, when [kind] is [ArgKind.ENUM]. */
    val enumConstants: List<String>,
    val description: String,
    val required: Boolean,
    /**
     * The `@Arg(default = "...")` text, or `null` when the parameter is required or nullable.
     *
     * A string and not a parsed value because it is what the manifest publishes; the emitter
     * folds it into a typed literal separately, and a text that will not fold is a build error.
     */
    val defaultText: String?,
    /** True when the Kotlin parameter type is nullable, so an absent argument means `null`. */
    val nullable: Boolean,
) {
    /** The JSON Schema type name: `array` for a list, otherwise the element kind's own. */
    val jsonType: String get() = if (list) "array" else kind.jsonType
}

/**
 * One declaring class's `@AgentState` properties.
 *
 * Deliberately a **separate** model from anything in `dev.wildware.udea.codegen.replicator`,
 * and nothing here is reachable from `ReplicatedComponent`. `@AgentState` publishes a scalar
 * into the digest's `game` block; it takes no `fieldNames` slot, no `FieldMask` bit and no
 * `FieldStore` index, because the frozen `Replicator` contract makes those three one index and
 * a property that owns one of them without the other two cannot exist in that space.
 */
internal data class AgentStateModel(
    /** The class declaring the properties; the receiver the generated writer reads from. */
    val owner: ClassName,
    /** The generated object's simple name, e.g. `MatchClockAgentState`. */
    val objectName: String,
    /** Sorted by effective name, so the digest's key order is a function of the sources alone. */
    val entries: List<AgentStateEntry>,
)

/** One published scalar: what it is called in the digest and how it is read. */
internal data class AgentStateEntry(
    /** The digest key: `@AgentState(name)` or the property's own name. */
    val name: String,
    /** The Kotlin property to read. */
    val propertyName: String,
    val kind: AgentStateKind,
    /** The enum type, when [kind] is [AgentStateKind.ENUM]. */
    val enumType: ClassName?,
)

/**
 * The scalar types the digest's `game` block accepts.
 *
 * Closed by the bridge contract, not by convenience: "scalar fields are included in the
 * digest. Nested objects and arrays are not." A non-scalar would be dropped by the bridge
 * with nothing reporting it, so it is `UDEA0011` at the property instead.
 */
internal enum class AgentStateKind {
    INT,
    LONG,
    FLOAT,
    BOOLEAN,
    STRING,

    /** Published by constant name, which is what the bridge's `game.state` example shows. */
    ENUM,
    ;

    companion object {
        /**
         * `Double` is **not** here, and that is a deliberate narrowing of what the brief asked
         * for.
         *
         * `GameStateSink` publishes `Int`, `Long`, `Float`, `Boolean` and `String`, and rounds
         * every float to four decimal places on the way into the digest. Accepting a `Double`
         * would mean generating a `.toFloat()` nobody wrote - a silent narrowing in generated
         * code, which is the class of defect this generator exists to remove. Declaring the
         * property `Float` says the same thing out loud, so the restriction is an error at the
         * property with that instruction rather than a conversion in the emitter.
         */
        private val BY_TYPE: Map<String, AgentStateKind> = mapOf(
            "kotlin.Int" to INT,
            "kotlin.Long" to LONG,
            "kotlin.Float" to FLOAT,
            "kotlin.Boolean" to BOOLEAN,
            "kotlin.String" to STRING,
        )

        fun of(qualifiedName: String): AgentStateKind? = BY_TYPE[qualifiedName]

        /** For a diagnostic: the set an author is allowed to choose from. */
        val supported: String = "Int, Long, Float, Boolean, String or an enum"
    }
}
