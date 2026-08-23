package dev.wildware.udea.agent.host

/**
 * Whether this build is permitted to stand the agent surface up at all.
 *
 * ## Two independent conditions, on purpose
 *
 * Spec §4 words the requirement as "debug-only, **verified absent** from release" rather than
 * "disabled in release", and the two halves of that guard different failures:
 *
 * - the **generated constant** removes the code path, so a release binary that somehow carries
 *   the classes still refuses `-Dudea.agent.port=7820`;
 * - the **missing classes** make the constant irrelevant, so a build that forgot to regenerate
 *   the constant still cannot serve anything, because `AgentHost` is not on the classpath.
 *
 * Either alone has failed in prior art: `FruitGameKTX`'s `DebugHttpServer` gates on a system
 * property alone, which means the whole remote-control surface ships in the player's binary and
 * any user can turn it on with one JVM argument.
 *
 * ## Today's honest state
 *
 * This file is **not** regenerated and never was. The generated per-variant flag is
 * `UdeaAgentBuildFlags`, written into the *game* by `udeaGenerateAgentBuildFlags`
 * (`AgentBuildFlagsSource` says why it cannot live in a library jar), and it is the one that is
 * `false` in a release build.
 *
 * [AGENT_ALLOWED] here is a hand-written `true` and nothing more. It used to be the **default**
 * of `AgentHost.startIfRequested(agentAllowed = ...)`, which made the generated flag opt-in: a
 * game that forgot to pass it was gated on a constant no build recomputes, so `-Pudea.release=true`
 * had no effect on the code path at all and `udeaVerifyRelease` was checking only the artifact
 * half. That default is gone. The parameter is required, every caller states its answer, and a
 * game states the generated one - `MobaAgent` passes `UdeaAgentBuildFlags.AGENT_ALLOWED`.
 *
 * What remains true is the artifact half, and it is what makes a release safe regardless:
 * `udeaVerifyRelease` fails a `-Pudea.release=true` build if any `dev/wildware/udea/agent/`
 * entry is inside the packaged jar, and `ReleaseRules.CLASSPATH_RULE` fails it if `:udea-agent`
 * or `:udea-agent-host` resolves onto `runtimeClasspath`.
 *
 * So: read [AGENT_ALLOWED] as "this library was compiled with the surface available", which is
 * the only thing a library can honestly say. It is what a *development-only* entry point with no
 * generated flag of its own passes - the demos in this module's test source set - and it is not
 * a release gate.
 */
public object BuildFlags {

    /**
     * `true`, always, in this jar. **Not** a release gate; see the class KDoc.
     *
     * Deliberately a `const` so that a `UdeaAgentBuildFlags.AGENT_ALLOWED` of `false` folds the
     * `if (!agentAllowed) return null` branch in [AgentHostGate] away entirely at the game's own
     * call site, leaving no reachable path to `HttpServer.create` for a shrinker to keep.
     */
    public const val AGENT_ALLOWED: Boolean = true

    /** The system property that carries the port. There is no environment-variable fallback. */
    public const val PORT_PROPERTY: String = "udea.agent.port"
}
