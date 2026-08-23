package dev.wildware.udea.agent.host

/**
 * Whether an agent host may bind, and on what port.
 *
 * A pure function rather than three `if`s inside `startIfRequested`, because the decision is
 * the security boundary of the whole module and a decision made inside a method that also
 * creates sockets cannot be unit tested — the test would have to bind a port to find out what
 * the branch did, which is precisely the branch that says "bind nothing".
 */
public object AgentHostGate {

    /** What [decide] concluded. */
    public sealed interface Decision {

        /** Bind on loopback, on [port]. */
        public class Bind(public val port: Int) : Decision {
            override fun toString(): String = "Bind($port)"
        }

        /** Do not bind. [reason] is one line, suitable for a startup log. */
        public class Refuse(public val reason: String) : Decision {
            override fun toString(): String = "Refuse($reason)"
        }
    }

    /**
     * Decides from the two conditions and nothing else.
     *
     * @param agentAllowed [BuildFlags.AGENT_ALLOWED] in production; a parameter so a test can
     *   drive the release variant's answer without a second build.
     * @param portProperty the raw value of `-Dudea.agent.port`, or `null` when it is absent.
     *   Absent is the overwhelmingly common case — a developer running the game normally — and
     *   it is refused silently rather than logged, because a line of startup noise on every run
     *   is how people learn to stop reading startup logs.
     */
    public fun decide(agentAllowed: Boolean, portProperty: String?): Decision {
        if (!agentAllowed) {
            return Decision.Refuse(
                "the agent surface is not permitted in this build (BuildFlags.AGENT_ALLOWED=false); " +
                    "-D${BuildFlags.PORT_PROPERTY} is ignored",
            )
        }
        val raw = portProperty
            ?: return Decision.Refuse("-D${BuildFlags.PORT_PROPERTY} was not set")
        val port = raw.trim().toIntOrNull()
            ?: return Decision.Refuse(
                "-D${BuildFlags.PORT_PROPERTY}=$raw is not a port number",
            )
        // 0 is legitimate and useful: it asks the OS for an ephemeral port, which is how every
        // test in this module binds without colliding with a developer's running game.
        if (port < 0 || port > MAX_PORT) {
            return Decision.Refuse(
                "-D${BuildFlags.PORT_PROPERTY}=$port is outside 0..$MAX_PORT",
            )
        }
        return Decision.Bind(port)
    }

    /** The highest TCP port number. */
    public const val MAX_PORT: Int = 65535
}
