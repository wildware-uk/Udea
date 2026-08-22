package dev.wildware.udea.core

/**
 * What this simulation is, authority-wise.
 *
 * Replaces `gameScreen.isServer`, a boolean read off a file-level global. The boolean could
 * not express a listen server, and reading it from a global made "two worlds in one JVM"
 * structurally impossible — which is exactly what the networking and MCP stories need.
 */
public enum class NetRole {
    /** Dedicated server: authoritative, no local player. */
    Server,

    /** Remote client: predicts locally, defers to the server. */
    Client,

    /** Authoritative server that also hosts a local player. */
    ListenServer,

    /** Single-process play with no transport at all. Authoritative by definition. */
    Standalone,
    ;

    /** True when this simulation owns the authoritative state. */
    public val isAuthoritative: Boolean
        get() = this != Client

    /** True when this simulation drives a local player's view and prediction. */
    public val hasLocalPlayer: Boolean
        get() = this != Server
}
