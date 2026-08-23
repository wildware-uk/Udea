package dev.wildware.udea.agent.host

/**
 * What part this process plays in a multiplayer test, as the wire spells it.
 *
 * Three values and no fourth: an instance is the authority, it is one of the ends talking to an
 * authority, or it is on its own. There is deliberately no `unknown` - see [SessionIdentity] for
 * why an instance that was told nothing still answers `standalone` with an id of its own rather
 * than a hole in the document.
 */
public enum class InstanceRole(
    /** The lower-case token published by `/health` and by the registry entry. */
    public val id: String,
) {

    /** Holds the authoritative simulation. What an agent asserts state against. */
    Server("server"),

    /** Predicts and sends input. What an agent sends input to. */
    Client("client"),

    /** Not part of a group: a single instance nobody launched peers for. */
    Standalone("standalone");

    override fun toString(): String = id

    public companion object {

        /**
         * Parses [token], or throws naming what was accepted.
         *
         * Loud rather than defaulting: the value comes from a `-D` a developer or a launcher
         * typed, and an instance that silently became `standalone` because somebody wrote
         * `-Dudea.agent.role=Server ` with a trailing space would be *missing* from the group an
         * agent is trying to drive, with nothing anywhere saying why. Standards section 1 puts
         * log-and-continue past a real error at the top of what this rewrite exists to kill.
         */
        public fun parse(token: String): InstanceRole =
            entries.firstOrNull { it.id == token.trim().lowercase() }
                ?: throw IllegalArgumentException(
                    "'$token' is not an instance role; expected one of " +
                        entries.joinToString { it.id },
                )
    }
}

/**
 * The short opaque label that groups a server and its clients into one agent session.
 *
 * A value class over `String` because charter section 1 says a domain concept is never a bare
 * `String` - the smell being killed is `Assets["character/orc"]`, and a session id passed around
 * as text is the same shape. The wrapper cannot detect a typo; what it can do is refuse a value
 * no session id could have, and stop one being handed to a parameter that wanted a port.
 *
 * ## Short, and opaque
 *
 * It is printed in `list_instances` output an agent reads dozens of times, so it is four hex
 * characters behind an `s-` and not a UUID. It carries no meaning: nothing may parse it, nothing
 * may derive behaviour from it, and two instances sharing one is the *only* thing it says.
 */
@JvmInline
public value class SessionId(public val value: String) {

    init {
        require(value.isNotBlank()) { "a session id is never blank" }
        require(value.length <= MAX_LENGTH) {
            "a session id is printed in every list_instances row, so it is at most $MAX_LENGTH " +
                "characters; '$value' is ${value.length}"
        }
        require(value.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) {
            "a session id must be safe in a filename, a query string and a JSON string, so it is " +
                "limited to letters, digits, '-', '_' and '.'; got '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {

        /** Long enough for a readable label, short enough for a table an agent reads. */
        public const val MAX_LENGTH: Int = 32

        /** What a generated id starts with, so a human can tell one from a hand-picked label. */
        public const val GENERATED_PREFIX: String = "s-"

        /**
         * An id for an instance that was told nothing, derived from [pid].
         *
         * Derived rather than random, and that is the more useful property here: two processes
         * running at the same time on one machine have different pids by definition, so two
         * concurrent standalone instances cannot collide - which is the only collision that
         * matters, because the id exists to group *concurrently running* instances. A random id
         * would have a small chance of colliding exactly there.
         *
         * The fold to sixteen bits is what makes it four characters; two pids that differ only
         * above sixteen bits fold together. That is a real, if unlikely, collision, and it is
         * survivable precisely because the contract forbids any endpoint from behaving
         * differently on the strength of a session id: the worst outcome is two unrelated
         * standalone instances printed in one `list_instances` group.
         */
        public fun generate(pid: Long): SessionId {
            val folded = (pid xor (pid ushr 16) xor (pid ushr 32) xor (pid ushr 48)) and 0xFFFFL
            return SessionId(GENERATED_PREFIX + folded.toString(HEX_RADIX).padStart(HEX_DIGITS, '0'))
        }

        private const val HEX_RADIX: Int = 16

        private const val HEX_DIGITS: Int = 4
    }
}

/**
 * This instance's place in a session: its [role] and its [sessionId].
 *
 * ## Both fields are additive, and that is the invariant
 *
 * An instance publishing neither is still fully drivable. Nothing in `/health`, `/state`,
 * `/command`, `/tools` or `/artifact` reads either field or behaves differently because of it -
 * grouping is a **reader-side convenience**. The moment an endpoint branches on a session id, a
 * bridge that ignores the field silently gets different semantics from one that does not, and
 * the difference shows up as a test that passes locally and fails through the real bridge.
 * `SessionAdditiveTest` drives a contract-era parser that knows none of these keys and asserts it
 * reads an instance publishing all of them.
 *
 * ## Resolved before the port binds
 *
 * [AgentHost.start] takes this in its config, and the config is built before
 * `HttpServer.create`. So by the time [AgentRegistry.advertise] runs - which is after the bind,
 * by contract - the values are already correct, and there is no window in which an entry exists
 * naming a role that has not been decided. `SessionGroupingTest` asserts the entry and the live
 * `/health` agree, which is the observable form of that ordering.
 *
 * ## Why an instance always has one
 *
 * A nullable field would put "no session" into every reader: the bridge's grouping, the
 * `agent_session` tool's answer, and the registry payload would each need a case for it. One real
 * value, generated from the pid when nobody supplied one, keeps every path the same shape - the
 * same reasoning `AgentSessionId.LOCAL` uses for the unrelated per-caller id.
 */
public class SessionIdentity(
    /** What this instance is. */
    public val role: InstanceRole,
    /** The group it belongs to. Never absent; generated when nothing supplied one. */
    public val sessionId: SessionId,
) {

    override fun toString(): String = "$role in $sessionId"

    public companion object {

        /** `-Dudea.agent.session=s-7f3a`. Absent means "generate one". */
        public const val SESSION_PROPERTY: String = "udea.agent.session"

        /** `-Dudea.agent.role=server|client|standalone`. Absent means [InstanceRole.Standalone]. */
        public const val ROLE_PROPERTY: String = "udea.agent.role"

        /**
         * Reads [SESSION_PROPERTY] and [ROLE_PROPERTY], filling in what was not supplied.
         *
         * @param properties system-property lookup, injected so a test can drive precedence
         *   without mutating the JVM - the same reason [AgentRegistry] takes one.
         * @param pid this process's id, used only to generate an id when none was given.
         * @throws IllegalArgumentException if [ROLE_PROPERTY] names no role, or if
         *   [SESSION_PROPERTY] holds something no session id could be. Both are values a launcher
         *   passed on purpose, and both are silent wrongness if defaulted.
         */
        public fun resolve(
            properties: (String) -> String? = System::getProperty,
            pid: Long = ProcessHandle.current().pid(),
        ): SessionIdentity {
            val role = properties(ROLE_PROPERTY)
                ?.takeIf { it.isNotBlank() }
                ?.let(InstanceRole::parse)
                ?: InstanceRole.Standalone
            val session = properties(SESSION_PROPERTY)
                ?.takeIf { it.isNotBlank() }
                ?.let(::SessionId)
                ?: SessionId.generate(pid)
            return SessionIdentity(role, session)
        }

        /**
         * The JVM arguments a launcher passes to a peer so it joins [session] as [role].
         *
         * **This is the seam with `:udea-net`.** `net.start_host` and `net.start_client` spawn a
         * process; whatever they spawn it with, they add these two arguments, and the peer then
         * resolves the identical identity through [resolve] before its port binds. Expressed as a
         * function here rather than as two string literals over there, so the property names have
         * exactly one definition and a peer cannot end up in a session named by a typo.
         */
        public fun jvmArguments(session: SessionId, role: InstanceRole): List<String> = listOf(
            "-D$SESSION_PROPERTY=${session.value}",
            "-D$ROLE_PROPERTY=${role.id}",
        )
    }
}
