package dev.wildware.udea.codegen.agent

import com.squareup.kotlinpoet.ClassName

/**
 * The `udea-agent` types generated agent code refers to.
 *
 * Held as [ClassName]s and not as a compile dependency, for the same reason `CoreNames` is:
 * the processor only ever *names* these types, and a build-time-only module must not drag a
 * runtime module onto every consumer's annotation-processor classpath.
 *
 * ## The shape this pins
 *
 * Generated code can only implement an interface that already exists on the module's own
 * compile classpath, so emission of each index is gated on the build telling the processor
 * that the service is there — `udea.toolModuleService` and `udea.stateModuleService`, exactly
 * as `udea.netModuleService` gates the `NetModule` index. The remaining names below are not
 * gated, because they are members of the same one contract that ships with the service: a
 * module that has `ToolModule` has `AgentToolDef` and `AgentToolArg` too.
 *
 * Every declaration named here now exists in `udea-agent`'s `src/main`, and
 * `docs/contracts/agent-tools.md` is the written form of the shape. This module still does not
 * depend on it in either direction: `udea-codegen` runs inside the Kotlin compiler and anything
 * it declared would land on every consumer's annotation-processor classpath, so the names stay
 * `ClassName`s. `udea-agent` is on this module's *test* classpath, which is what lets the
 * generated code here be compiled, `ServiceLoader`-loaded and dispatched through the real
 * `ToolIndex` rather than through a stand-in.
 */
internal object AgentNames {
    const val PACKAGE: String = "dev.wildware.udea.agent"

    /** One generated tool: name, description, schema and a direct-call dispatcher. */
    val AGENT_TOOL_DEF: ClassName = ClassName(PACKAGE, "AgentToolDef")

    /**
     * An [AGENT_TOOL_DEF] that also takes the [AGENT_CONTEXT] of the command being served.
     *
     * Emitted only for a tool that declared an `AgentContext` parameter. `ToolIndex` checks for
     * this type and passes the context only to a tool that asked for one, so the ordinary
     * generated surface is untouched.
     */
    val CONTEXTUAL_TOOL_DEF: ClassName = ClassName("$PACKAGE.tools", "ContextualToolDef")

    /**
     * What a tool is handed when it runs: the world, the engine services, and `answerLater`.
     *
     * Named here because a `@AgentTool` function may declare it as a parameter to say it must
     * run outside the barrier drain it was called in. See `ToolModel.contextParameter`.
     */
    val AGENT_CONTEXT: ClassName = ClassName("$PACKAGE.dispatch", "AgentContext")

    /** One entry of the bridge's `tools[].args[]`: `{name, type, description, required, default}`. */
    val AGENT_TOOL_ARG: ClassName = ClassName(PACKAGE, "AgentToolArg")

    /**
     * One tool call as it crosses to the simulation thread: the name, the query parameters
     * verbatim, and typed accessors over them.
     *
     * The dispatcher takes `udea-agent`'s own command type rather than an argument bag of this
     * generator's invention. Coercion then happens once, in the engine, through accessors that
     * already throw [BAD_ARGUMENT] — so the generated code inherits "never silently read a zero
     * where the agent sent `4o`" instead of restating it, and `AgentDispatcher` needs no case
     * for a second failure type.
     */
    val AGENT_COMMAND: ClassName = ClassName(PACKAGE, "AgentCommand")

    /**
     * The typed coercion failure, carrying the tool, the argument, what arrived and what was
     * expected.
     *
     * `AgentDispatcher` already turns this into an `ok:false` with kind `bad_argument`, which
     * is exactly the Phase 1 exit criterion "a throwing tool lands as `ok:false` without
     * stalling the loop" — so generated code throws this and nothing else.
     */
    val BAD_ARGUMENT: ClassName = ClassName(PACKAGE, "BadArgumentException")

    /** One declaring class's `@AgentState` properties, written straight into the digest. */
    val AGENT_STATE_SOURCE: ClassName = ClassName(PACKAGE, "AgentStateSource")

    /**
     * The digest's `game` block sink, which accepts scalars and nothing else.
     *
     * `udea-agent` declares it and `StateDigest` implements it over the digest's own `Json`
     * buffer, so a generated writer appends straight into the document being built.
     */
    val GAME_STATE_SINK: ClassName = ClassName("$PACKAGE.state", "GameStateSink")
}
