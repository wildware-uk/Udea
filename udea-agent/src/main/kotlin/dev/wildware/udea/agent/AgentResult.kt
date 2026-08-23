package dev.wildware.udea.agent

/**
 * What one tool call produced, as a value.
 *
 * ## The defect this replaces
 *
 * The reference implementation had no result channel. `DebugInspector` wrote answers into the
 * shared event ring as formatted strings - `"hit:<chain>"`, `"screenshot:<path>"`,
 * `"error:no_such_modifier:<id>"` - so every caller had to string-match a *racy* ring for its
 * own answer, could not tell its answer from a concurrent caller's, and could not tell an
 * error from a game event that happened to start with the same word. Here a result belongs to
 * a command id, an error carries a machine-readable [AgentError.kind], and the event ring goes
 * back to being what it is for: things that happened in the game.
 *
 * A sealed hierarchy so a renderer or a tool wrapper handles both cases or fails to compile.
 */
public sealed interface AgentResult {

    /**
     * The tool succeeded.
     *
     * [json] is a rendered JSON **value** - an object, an array or a scalar - which
     * [AgentBridge] splices into `{"id":18,"ok":true,"result":<json>}`. Rendering rather than
     * a structured tree because the alternative is a general-purpose object model, and this
     * module exists partly to avoid shipping one; tools render with [Json.render], which is
     * the only supported way to produce this string.
     */
    public class Ok(public val json: String) : AgentResult {
        init {
            require(json.isNotEmpty()) {
                "an Ok result renders a JSON value; use Json.render { } or AgentResult.EMPTY"
            }
        }

        override fun toString(): String = "Ok($json)"
    }

    /** The tool refused, or threw, or was never found. */
    public class Failed(public val error: AgentError) : AgentResult {
        override fun toString(): String = "Failed(${error.kind}: ${error.message})"
    }

    public companion object {
        /** A success with nothing to report. */
        public val EMPTY: Ok = Ok("{}")

        /** Renders [build] as this result's value. */
        public inline fun ok(build: Json.() -> Unit): Ok = Ok(Json.render(build))

        /** A failure of [kind]. */
        public fun failed(kind: AgentErrorKind, message: String): Failed =
            Failed(AgentError(kind, message))
    }
}

/**
 * Why a tool call failed, in a form an agent can branch on.
 *
 * [kind] is the machine-readable half and [message] the human-readable one, and both are
 * required: an agent that can only read prose has to guess whether to retry, and an agent that
 * can only read a kind cannot tell the user what went wrong.
 */
public class AgentError(
    /** The stable classification. */
    public val kind: AgentErrorKind,
    /** One line, naming the offending value where there is one. */
    public val message: String,
) {
    init {
        require(message.isNotBlank()) { "an AgentError of kind ${kind.id} must say what went wrong" }
    }

    override fun toString(): String = "${kind.id}: $message"
}

/**
 * The classification of an [AgentError].
 *
 * A value class over a `String` rather than an enum, because the kinds are open by design:
 * the engine toolsets, the game's own tools and a later module all declare their own, and an
 * enum here would mean every one of them edits this file. The spelling is constrained instead
 * - lowercase, digits and underscores - so the vocabulary stays greppable and an agent can
 * match on it.
 */
@JvmInline
public value class AgentErrorKind(public val id: String) {
    init {
        require(id.isNotEmpty() && id.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }) {
            "an error kind is lower_snake_case, was $id"
        }
    }

    override fun toString(): String = id

    public companion object {
        /** The command queue was full; the command was never queued. See [AgentBridge.submit]. */
        public val QUEUE_FULL: AgentErrorKind = AgentErrorKind("queue_full")

        /** No tool of that name is registered. */
        public val NO_SUCH_TOOL: AgentErrorKind = AgentErrorKind("no_such_tool")

        /** An argument was missing or unparseable. See [BadArgumentException]. */
        public val BAD_ARGUMENT: AgentErrorKind = AgentErrorKind("bad_argument")

        /**
         * The tool threw. The simulation is unaffected and kept ticking - that is the
         * guarantee this kind exists to make visible.
         */
        public val TOOL_THREW: AgentErrorKind = AgentErrorKind("tool_threw")

        /** A `NetId` in the call resolves to no live entity: it is stale, freed, or never was. */
        public val NO_SUCH_ENTITY: AgentErrorKind = AgentErrorKind("no_such_entity")

        /** A component, field or filter the call named does not exist on this world. */
        public val NO_SUCH_FIELD: AgentErrorKind = AgentErrorKind("no_such_field")

        /** The filter or projection could not be parsed. */
        public val BAD_QUERY: AgentErrorKind = AgentErrorKind("bad_query")
    }
}
