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
 * `udea-gradle` regenerates this file per variant (`false` for release) — that task is issue
 * #82's remaining half and is **not written yet**. What is enforced today is the artifact half:
 * `udeaVerifyRelease` fails a `-Pudea.release=true` build if any `dev/wildware/udea/agent/`
 * entry is inside the packaged jar, and `ReleaseRules.CLASSPATH_RULE` fails it if `:udea-agent`
 * or `:udea-agent-host` resolves onto `runtimeClasspath`. So the constant is `true` here and the
 * absence of the module is what makes a release safe. Treat the value as generated: do not read
 * it as a promise that a release build has it set to `false` yet.
 */
public object BuildFlags {

    /**
     * `true` in a development build. Regenerated as `false` for the release variant.
     *
     * Deliberately a `const` so the `if (!BuildFlags.AGENT_ALLOWED) return null` branch in
     * [AgentHostGate] folds away entirely when it is false, leaving no reachable path to
     * `HttpServer.create` for a shrinker to keep.
     */
    public const val AGENT_ALLOWED: Boolean = true

    /** The system property that carries the port. There is no environment-variable fallback. */
    public const val PORT_PROPERTY: String = "udea.agent.port"
}
