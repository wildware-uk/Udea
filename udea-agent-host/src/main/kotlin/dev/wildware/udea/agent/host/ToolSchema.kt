package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.AgentToolArg

/**
 * Builds a tool's `inputSchema` in **exactly** the shape `udea-codegen` emits, from the same
 * `args` the tool publishes.
 *
 * ## Why this exists
 *
 * Every tool in this module is hand-written - `CompareArtifactsTool` explains why, and the render
 * declarations need an `AgentContext` and constructor-injected receivers that no KSP round here
 * could supply. Hand-written meant hand-written *schemas*, and they had drifted into a second
 * dialect of their own:
 *
 * | | generated | hand-written, before this |
 * |---|---|---|
 * | `$schema` | declared | absent |
 * | `additionalProperties` | `false` | absent, so anything was allowed |
 * | empty `required` | omitted | emitted as `"required":[]` |
 * | a default | folded into the property description as `(default 1)` | prose, or nothing |
 * | an optional with no default | `(optional; omit for none)` | nothing |
 *
 * None of that is cosmetic. `additionalProperties:false` is how a model learns it passed an
 * argument the tool does not take, instead of watching half its request be ignored; the folded
 * default is the only place a schema-reading client sees one, since `ToolManifest.schemaOf`
 * deliberately does not emit a `default` keyword. And the drift was invisible: nothing compared
 * the two, and `game-bridge-mcp`'s parser is tolerant, so a malformed schema does not fail - it
 * quietly makes a capability harder to call correctly.
 *
 * Deriving the schema from `args` also closes the gap that made the drift possible: the manifest
 * published an argument list and a schema that were two independent literals, so a tool could
 * advertise `afterTick` in one and not the other. Here there is one source.
 *
 * ## Why it is a copy of the generator's rules and not a call into it
 *
 * `udea-codegen` is a KSP processor. It is a build-time dependency of the modules that run a
 * processing round and is not on any runtime classpath, so this module cannot call
 * `ToolManifest.schemaOf` even though that is the function this reproduces. `ToolSchemaTest`
 * closes the loop the only way that is left: it rebuilds the schema of a **generated** tool from
 * that tool's own `args` and asserts the result is byte-identical to the string the generator
 * emitted. If the emitter's shape ever moves, that test fails here, in this module, rather than
 * this module publishing last year's dialect forever.
 */
public object ToolSchema {

    /** The JSON Schema dialect the generated documents declare. Must match `ToolManifest`. */
    public const val DIALECT: String = "https://json-schema.org/draft/2020-12/schema"

    /**
     * The schema for a tool that takes [args].
     *
     * @param args the tool's published arguments, in declaration order, which is the order the
     *   generator writes its properties in.
     */
    public fun of(args: List<AgentToolArg>): String = buildString {
        append("""{"${'$'}schema":"""").append(DIALECT).append('"')
        append(""","type":"object","properties":{""")
        args.forEachIndexed { index, arg ->
            if (index > 0) append(',')
            append('"').append(escape(arg.name)).append("""":{"type":"""")
            append(escape(arg.type)).append("""","description":"""")
            append(escape(describe(arg))).append(""""}""")
        }
        append('}')
        val required = args.filter(AgentToolArg::required)
        if (required.isNotEmpty()) {
            append(""","required":[""")
            required.forEachIndexed { index, arg ->
                if (index > 0) append(',')
                append('"').append(escape(arg.name)).append('"')
            }
            append(']')
        }
        append(""","additionalProperties":false}""")
    }

    /**
     * The description a schema property carries: the argument's own text, plus its default.
     *
     * The generator folds the default into the prose rather than emitting a `default` keyword -
     * the text is what a model reads either way, and a `default` on a strictly typed property is
     * something a strict client may reject. An optional argument with no default says so, because
     * "omit for none" and "omit for the default" are different instructions.
     *
     * A `default` of `""` means *empty string*, not *no default*, and reproduces as `(default )`.
     * That is the generator's behaviour and it is the reason the hand-written declarations in this
     * module now pass `null` for an optional with no default: `""` was being written where `null`
     * was meant, which is exactly the kind of thing a shared builder makes visible.
     */
    private fun describe(arg: AgentToolArg): String = buildString {
        append(arg.description)
        when {
            arg.default != null -> append(" (default ").append(arg.default).append(')')
            !arg.required -> append(" (optional; omit for none)")
        }
    }

    /** JSON string escaping, for the same characters `JsonText` escapes in the generator. */
    private fun escape(text: String): String {
        if (text.none { it == '"' || it == '\\' || it.code < 0x20 }) return text
        return buildString(text.length + ESCAPE_HEADROOM) {
            for (character in text) {
                when {
                    character == '"' -> append("\\\"")
                    character == '\\' -> append("\\\\")
                    character == '\n' -> append("\\n")
                    character == '\r' -> append("\\r")
                    character == '\t' -> append("\\t")
                    character.code < 0x20 ->
                        append("\\u").append(character.code.toString(HEX).padStart(4, '0'))
                    else -> append(character)
                }
            }
        }
    }

    private const val ESCAPE_HEADROOM: Int = 16

    private const val HEX: Int = 16
}
