package dev.wildware.udea.codegen.fixtures

import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.annotations.AgentTool
import dev.wildware.udea.annotations.Arg
import dev.wildware.udea.agent.dispatch.AgentContext

/**
 * The two shapes that kept the engine's own toolsets hand-written for a whole wave.
 *
 * [Playground] next door covers every *argument* shape. This covers the two things that are
 * not about arguments at all, and both are load-bearing for `world.*`, `time.*`, `events.*`
 * and `diag.*` being generated rather than written out by hand a second time:
 *
 * - **a name that carries its own toolset.** `@AgentTool(name = "sim.describe")` groups the
 *   tool under `sim` in the manifest instead of under `timeline`, the declaring class's name
 *   snake_cased. Without it the engine's four toolsets could not be generated at all: they are
 *   addressed as `world.query` and `time.step` in the frozen `docs/contracts/agent-tools.md`,
 *   and the only way to derive that from a class name is to call a class `World` in a module
 *   that imports Fleks' `World` on almost every line.
 * - **an `AgentContext` parameter.** A tool that has to run *outside* the `SimBarrier` drain it
 *   was called in - anything that steps the simulation - needs `AgentContext.answerLater`, and
 *   there is nowhere else to get one. The emitted object implements `ContextualToolDef`, its
 *   two-argument `invoke` throws, and the context is **not** published as an argument: there is
 *   nothing an agent could put in it.
 */
public class Timeline {

    /** Every request [advance] was handed, so a test can assert the dispatcher called through. */
    public val requested: MutableList<Int> = mutableListOf()

    @AgentTool(
        name = "sim.describe",
        description = "Report what this timeline is holding without changing it. Reach for it " +
            "before advancing, to find out how far the history already reaches.",
    )
    public fun describe(
        @Arg(description = "Include the per-request breakdown as well as the count.", required = false, default = "false")
        verbose: Boolean = false,
    ): String = if (verbose) requested.joinToString(",") else requested.size.toString()

    /**
     * The context is declared first on purpose: the emitter passes every argument by name, so a
     * context in any position has to work, and putting it where a positional emitter would have
     * got it wrong is what makes that claim testable.
     */
    @AgentTool(
        name = "sim.advance",
        description = "Advance this timeline by a number of ticks after the current tick has " +
            "finished, and answer with what actually ran. Use it when an assertion has to be " +
            "about a named tick rather than a moment.",
    )
    public fun advance(
        context: AgentContext,
        @Arg(description = "Ticks to advance. One tick is one 1/60s simulation step.", required = false, default = "1")
        ticks: Int = 1,
    ): AgentResult? {
        requested += ticks
        context.answerLater { AgentResult.ok { put("ticksRequested", ticks) } }
        return null
    }
}
