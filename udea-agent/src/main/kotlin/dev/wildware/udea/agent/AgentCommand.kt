package dev.wildware.udea.agent

import dev.wildware.udea.agent.activity.AgentSessionId
import java.util.concurrent.atomic.AtomicLong

/**
 * One tool call, as it crosses from the HTTP thread to the simulation thread.
 *
 * Arguments are strings because that is what arrives: the bridge contract keys the tool name
 * on `cmd` and passes every other query parameter through as text (`game-bridge-mcp`
 * `GET /command`). Coercion happens once, here, through the typed accessors, so a tool never
 * writes `args["x"]?.toFloatOrNull() ?: 0f` and never silently reads a zero where the agent
 * sent `"4o"` - [float], [int] and [bool] report a bad value rather than swallowing it.
 *
 * Immutable on purpose: it is published to another thread by [AgentBridge.submit], and a
 * mutable argument map would be a data race with no owner.
 */
public class AgentCommand(
    /** The tool to run. Matched against `ToolRegistry`. */
    public val name: String,
    /** Every other query parameter, verbatim. */
    public val args: Map<String, String> = emptyMap(),
    /** Monotonic within one process. What [AgentBridge.completedCommandId] reports. */
    public val id: Long = nextId(),
    /**
     * Who issued it, for the human-facing activity overlay (spec 3.7).
     *
     * Carried on the command rather than looked up later because there is no later: the HTTP
     * thread is the only thread that knows which client sent this, and by the time the
     * simulation thread runs the tool the request is gone. `AgentHost` derives it from a
     * reserved `session` query key, falling back to the remote address.
     *
     * Defaulted, so every existing construction site - `SimHarness`, the barrier tests, the
     * codegen fixtures - keeps compiling and lands under [AgentSessionId.LOCAL]. It is
     * deliberately **not** an entry in [args]: a tool must never be able to read it, or an
     * agent could branch on which session it is, and the overlay's whole premise is that the
     * agent cannot see the overlay's inputs.
     */
    public val session: AgentSessionId = AgentSessionId.LOCAL,
) {

    /**
     * The argument named [key] as a `Float`.
     *
     * @throws BadArgumentException when the argument is present but is not a number. A tool
     *   that defaulted here would apply a mutation the agent did not ask for and report
     *   success.
     */
    public fun float(key: String, fallback: Float? = null): Float =
        coerce(key, fallback) { it.toFloatOrNull() }

    /** The argument named [key] as an `Int`. See [float] for the failure contract. */
    public fun int(key: String, fallback: Int? = null): Int =
        coerce(key, fallback) { it.toIntOrNull() }

    /** The argument named [key] as a `Long`. See [float] for the failure contract. */
    public fun long(key: String, fallback: Long? = null): Long =
        coerce(key, fallback) { it.toLongOrNull() }

    /**
     * The argument named [key], verbatim.
     *
     * @throws BadArgumentException when it is absent and no [fallback] was given.
     */
    public fun str(key: String, fallback: String? = null): String =
        args[key] ?: fallback ?: throw BadArgumentException(name, key, null, "a value")

    /** The argument named [key] as a `Boolean`, accepting `true`/`false`/`1`/`0`. */
    public fun bool(key: String, fallback: Boolean? = null): Boolean =
        coerce(key, fallback) {
            when (it) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }
        }

    /** True when [key] was supplied at all. */
    public operator fun contains(key: String): Boolean = args.containsKey(key)

    override fun toString(): String = "AgentCommand#$id($name, ${args.size} arg(s))"

    private inline fun <T : Any> coerce(key: String, fallback: T?, parse: (String) -> T?): T {
        val raw = args[key] ?: return fallback ?: throw BadArgumentException(name, key, null, "a value")
        return parse(raw) ?: throw BadArgumentException(name, key, raw, "a well-formed value")
    }

    public companion object {
        private val counter = AtomicLong(0)

        /**
         * The next command id.
         *
         * Process-wide and monotonic, because [AgentBridge.completedCommandId] is a single
         * high-water mark: two independent id sequences would let a low id from one of them
         * confirm a command from the other that had not run.
         */
        public fun nextId(): Long = counter.incrementAndGet()
    }
}

/**
 * An argument that was missing or unparseable.
 *
 * Typed rather than a `NumberFormatException` because [AgentDispatcher] turns it into an
 * [AgentError] the agent can act on: the tool, the argument and what was expected are the
 * three things needed to fix the call, and a stack trace through a coercion helper is none of
 * them.
 */
public class BadArgumentException(
    /** The tool whose argument was wrong. */
    public val toolName: String,
    /** The argument name. */
    public val argument: String,
    /** What arrived, or `null` when nothing did. */
    public val supplied: String?,
    /** What the tool needed. */
    public val expected: String,
) : IllegalArgumentException(
    if (supplied == null) {
        "$toolName requires the argument $argument ($expected)"
    } else {
        "$toolName got $argument=$supplied, which is not $expected"
    },
)
