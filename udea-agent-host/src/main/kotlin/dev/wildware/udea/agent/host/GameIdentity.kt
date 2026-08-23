package dev.wildware.udea.agent.host

/**
 * How this instance names itself: to `GET /tools`, and to its registry entry.
 *
 * One value for both, because the contract's reader rules (`game-bridge-mcp` README §2) say the
 * bridge prefers the live `/tools` answer over the registry file — which is only a safe
 * preference if the two cannot disagree. Two independently assembled name strings is exactly
 * how a bridge ends up reporting an instance under a name no `list_instances` row matches.
 */
public class GameIdentity(
    /** Shown by `list_instances`. How a human tells five running instances apart. */
    public val name: String,
    /** The game's version, not the protocol's. */
    public val version: String,
    /**
     * Versions the **manifest document**, not the command set.
     *
     * Adding a tool changes nothing here; restructuring `/tools` does. Current version: 1.
     */
    public val protocol: Int = PROTOCOL,
) {
    init {
        require(name.isNotBlank()) { "a game identity needs a name; list_instances shows it" }
        require(version.isNotBlank()) { "a game identity needs a version" }
        require(protocol > 0) { "protocol must be positive, was $protocol" }
    }

    override fun toString(): String = "$name $version (protocol $protocol)"

    public companion object {
        /** The manifest document version this module emits. */
        public const val PROTOCOL: Int = 1

        /** What a host that was handed no identity calls itself. */
        public val UNKNOWN: GameIdentity = GameIdentity("udea", "0.0.0")
    }
}
