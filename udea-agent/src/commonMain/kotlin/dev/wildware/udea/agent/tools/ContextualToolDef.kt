package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentToolDef
import dev.wildware.udea.agent.dispatch.AgentContext
import dev.wildware.udea.agent.dispatch.ToolIndex

/**
 * A tool that needs the [AgentContext] its command is running under.
 *
 * ## The one thing a receiver cannot supply
 *
 * A tool is normally never handed a context: it reaches the world through the toolset it is a
 * member of, which the host constructed with whatever that tool mutates ([ToolIndex.invoke]).
 * That covers every tool that *changes* something.
 *
 * It does not cover a tool that has to run **outside** the barrier drain it was called in.
 * `SimBarrier.drain` refuses to re-enter, and `Simulation.step` drains the barrier - so
 * `time.step`, `time.fast_forward` and `time.rewind` cannot do their work where they are
 * called, and the only way to run after the tick and still answer for it is
 * [AgentContext.answerLater]. That lives on the context, and there is nowhere else to get one.
 *
 * ## How a tool asks for one
 *
 * By declaring an `AgentContext` parameter on the `@AgentTool` function. `udea-codegen` sees
 * the type, leaves the parameter out of the published schema - there is nothing an agent could
 * put in it - and emits an object implementing this interface instead of plain [AgentToolDef],
 * whose two-argument `invoke` throws. [ToolIndex] checks for this type and passes the context
 * only to a tool that asked for one, so the ordinary generated surface is untouched and the
 * contract in `docs/contracts/agent-tools.md` still describes it exactly.
 *
 * Kept as a separate interface rather than widening [AgentToolDef] for that reason: a context
 * threaded into every tool would suggest generated tools can read one, and the first one that
 * tried would find no way to.
 */
public interface ContextualToolDef<in T> : AgentToolDef<T> {

    /** Runs the tool with the context of the command being served. */
    public fun invoke(receiver: T, command: AgentCommand, context: AgentContext): Any?
}
