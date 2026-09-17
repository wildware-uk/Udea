package dev.wildware.udea.agent

/**
 * A failure a tool - or the engine underneath one - can describe precisely.
 *
 * The dispatcher turns any escaping exception into an `ok:false`, but a plain exception can
 * only ever become the generic `tool_threw`: the agent is told something broke and nothing
 * about what to do next. Throwing this instead carries the [AgentErrorKind] through, so
 * "that entity is gone" and "that component has no such field" arrive as different answers.
 *
 * Thrown rather than returned because it is raised deep inside a query evaluation or a field
 * resolution, where every frame in between would otherwise have to thread a result type it has
 * no opinion about.
 */
public class AgentToolException(
    /** What went wrong, in the form the agent receives it. */
    public val error: AgentError,
) : RuntimeException(error.message) {

    /** Convenience for the common construction site. */
    public constructor(kind: AgentErrorKind, message: String) : this(AgentError(kind, message))
}
