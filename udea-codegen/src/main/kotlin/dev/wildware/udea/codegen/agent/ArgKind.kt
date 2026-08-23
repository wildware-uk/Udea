package dev.wildware.udea.codegen.agent

import com.squareup.kotlinpoet.CodeBlock
import dev.wildware.udea.codegen.CoreNames

/**
 * The closed world of `@AgentTool` parameter types, and the one place each one's JSON Schema
 * type and its coercion from a query-string value are decided.
 *
 * **Closed on purpose.** The generator this replaces answered a type it did not recognise
 * with a blind serialisation fallback, so an unsupported parameter compiled and then failed
 * at run time on a live server. Here an unrecognised type is `UDEA0010` at the parameter,
 * naming the type and the supported set.
 *
 * A tool call arrives as query parameters (`GET /command?cmd=drop&x=-1.5`), so every value
 * starts life as a `String`. The conversion is not written here: `AgentCommand` already has
 * `int`, `long`, `float`, `bool` and `str`, each of which throws `BadArgumentException` naming
 * the tool, the argument, what arrived and what was expected. Generated code calls those, so
 * there is one coercion in the engine rather than one in the engine and one in every generated
 * file that could drift from it.
 */
internal enum class ArgKind(
    /** The JSON Schema type name the bridge's `args[].type` and `inputSchema` carry. */
    val jsonType: String,
    /** The `AgentCommand` accessor that converts and reports, or `null` where none fits. */
    val accessor: String?,
    /** What a wrong value is described as in a `BadArgumentException`. */
    val expectation: String,
) {
    INT("integer", "int", "a whole number"),
    LONG("integer", "long", "a whole number"),
    FLOAT("number", "float", "a number"),

    /** `AgentCommand` has no `double` accessor, so this one parses the raw text itself. */
    DOUBLE("number", null, "a number"),
    BOOLEAN("boolean", "bool", "true, false, 1 or 0"),
    STRING("string", "str", "text"),

    /** Published as a `string` with an `enum` of the constant names; never the ordinal. */
    ENUM("string", null, "one of the listed constants"),

    /**
     * `NetId`, the only identity a tool may name (spec 5, "Entity identity"). Carried as its
     * packed word, which is what `describe_entity` and `set_component_field` are given.
     */
    NET_ID("integer", null, "a NetId packed word"),
    ;

    companion object {
        /**
         * The scalar kinds, by fully-qualified Kotlin type name.
         *
         * `Double` is here and deliberately absent from the replication side: a replicated
         * field costs 64 bits on every packet, but a tool argument costs nothing per tick, so
         * the two closed worlds are not the same closed world and are not shared.
         */
        private val BY_TYPE: Map<String, ArgKind> = mapOf(
            "kotlin.Int" to INT,
            "kotlin.Long" to LONG,
            "kotlin.Float" to FLOAT,
            "kotlin.Double" to DOUBLE,
            "kotlin.Boolean" to BOOLEAN,
            "kotlin.String" to STRING,
            CoreNames.NET_ID_FQN to NET_ID,
        )

        /** The scalar kind for a fully-qualified type name, or `null` if it is outside the set. */
        fun scalarOf(qualifiedName: String): ArgKind? = BY_TYPE[qualifiedName]

        /** For a diagnostic: the set an author is allowed to choose from. */
        val supported: String =
            "Int, Long, Float, Double, Boolean, String, an enum, NetId, or a List of any of those"
    }
}

/**
 * Whether a `@Arg(default = "...")` text is a value its parameter's type can actually hold.
 *
 * Checked at build time on purpose. The default is passed to `AgentCommand`'s accessor as its
 * `fallback`, so an unparseable one would not fail here — it would be handed to the tool as-is
 * the first time an agent omitted the argument, which is a wrong value applied silently rather
 * than a build that stopped.
 */
internal object ArgDefaults {

    /** The Kotlin literal for [text], or `null` when the text is not a [kind] at all. */
    fun literal(kind: ArgKind, enumType: com.squareup.kotlinpoet.ClassName?, constants: List<String>, text: String): CodeBlock? =
        when (kind) {
            ArgKind.INT -> text.toIntOrNull()?.let { CodeBlock.of("%L", it) }
            ArgKind.LONG -> text.toLongOrNull()?.let { CodeBlock.of("%LL", it) }
            ArgKind.FLOAT -> text.toFloatOrNull()?.let { CodeBlock.of("%Lf", it) }
            ArgKind.DOUBLE -> text.toDoubleOrNull()?.let { CodeBlock.of("%L", it) }
            // `AgentCommand.bool` accepts 1 and 0 as well as true and false, so a default may
            // be written either way and still mean what the manifest says it means.
            ArgKind.BOOLEAN -> BOOLEANS[text]?.let { CodeBlock.of("%L", it) }
            ArgKind.STRING -> CodeBlock.of("%S", text)
            ArgKind.ENUM -> if (text in constants) {
                CodeBlock.of("%T.%N", requireNotNull(enumType), text)
            } else {
                null
            }
            ArgKind.NET_ID -> text.toIntOrNull()?.let { CodeBlock.of("%T.ofRaw(%L)", CoreNames.NET_ID, it) }
        }

    private val BOOLEANS = mapOf("true" to true, "1" to true, "false" to false, "0" to false)
}
