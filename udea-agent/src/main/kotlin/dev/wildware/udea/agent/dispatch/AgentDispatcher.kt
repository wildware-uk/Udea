package dev.wildware.udea.agent.dispatch

import com.github.quillraven.fleks.World
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentClock
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentToolException
import dev.wildware.udea.agent.BadArgumentException
import dev.wildware.udea.agent.activity.AgentOutcome
import dev.wildware.udea.agent.activity.AnchorRule
import dev.wildware.udea.core.GameContext

/**
 * Runs one command and records its answer. The only place a tool failure is turned into a value.
 *
 * ## Nothing a tool can do stalls the loop
 *
 * This is the single most important property of the agent surface, and it is the reason this
 * class exists separately from [AgentRuntime]. A tool is game code written by whoever is
 * debugging the game; it will throw. If a throw escaped:
 *
 * - the `SimBarrier` drain would log it and move on, so the command would **never complete**;
 * - the caller would poll `completedCommandId` until it timed out;
 * - and the bridge would report a perfectly healthy game as frozen.
 *
 * So every outcome becomes an [AgentResult], `completedCommandId` advances for a failure
 * exactly as for a success, and the simulation keeps ticking. Spec 6 makes it a Phase 1 exit
 * criterion in those words: *a throwing tool lands as `ok:false` without stalling the loop*.
 *
 * An `Error` is deliberately **not** contained, matching `SimBarrier`: `OutOfMemoryError` and
 * `LinkageError` leave the world undefined, and an `AssertionError` swallowed here would be a
 * test assertion silenced by production code.
 */
internal class AgentDispatcher(
    private val bridge: AgentBridge,
    private val tools: ToolRegistry,
    private val deferred: DeferredQueue,
    private val clock: AgentClock,
) {

    fun run(command: AgentCommand, world: World, ctx: GameContext) {
        // Recorded before the name is even checked, and before the tool runs: the activity ring
        // is what a human watching the window reads, and a call that wedged, threw or named a
        // tool that does not exist is exactly the call they need to see. A ring written only on
        // success shows nothing during the one call worth watching.
        val slot = bridge.activity.begin(
            command,
            ctx.clock.tick.value,
            command.session,
            anchorRuleFor(command.name),
        )
        if (!tools.contains(command.name)) {
            bridge.activity.complete(slot, command.id, AgentOutcome.UNKNOWN, 0L)
            bridge.complete(
                command.id,
                AgentResult.failed(
                    AgentErrorKind.NO_SUCH_TOOL,
                    "no tool named ${command.name} is registered; call the tools listing to see " +
                        "what is",
                ),
            )
            return
        }
        val context = AgentContext(world, ctx, command, deferred, bridge)
        val result = invoke(command, context)
        // A tool that must run outside this drain - anything that steps the simulation, since
        // `SimBarrier.drain` refuses to re-enter - has already registered its own completion
        // through `AgentContext.answerLater`. Completing here as well would put two answers in
        // the ring under one id, and the caller would read whichever was written last.
        //
        // The activity entry is left RUNNING for exactly the same reason, and that is honest
        // rather than a gap: `time.rewind` really has not finished, and the overlay showing it
        // in flight is the only signal a human gets that the instance is mid-rewind.
        if (context.answersLater) return
        bridge.activity.complete(slot, command.id, outcomeOf(result), lastElapsedNanos)
        bridge.complete(command.id, result)
    }

    /**
     * The anchor rule for [toolName], derived once from its declared arguments and cached.
     *
     * Cached because the derivation walks the argument list, and the answer depends only on the
     * declaration - which cannot change while the process runs. A `HashMap` and not a
     * `ConcurrentHashMap`: dispatch is single-threaded by construction, running inside a
     * `SimBarrier` drain on the simulation thread, and a concurrent map here would be advertising
     * a second writer that does not exist.
     */
    private fun anchorRuleFor(toolName: String): AnchorRule =
        anchorRules.getOrPut(toolName) { AnchorRule.of(tools.declaredArgs(toolName)) }

    private val anchorRules = HashMap<String, AnchorRule>()

    /** Wall nanoseconds the most recent [invoke] took. Written there, read once by [run]. */
    private var lastElapsedNanos: Long = 0L

    private fun outcomeOf(result: AgentResult): AgentOutcome = when (result) {
        is AgentResult.Ok -> AgentOutcome.OK
        is AgentResult.Failed -> AgentOutcome.FAILED
    }

    private fun invoke(command: AgentCommand, context: AgentContext): AgentResult {
        val startedAt = clock.nowNanos()
        val result = try {
            tools.invoke(command, context)
        } catch (typed: AgentToolException) {
            // The tool said exactly what went wrong; passing its kind through is the whole
            // reason the exception exists.
            AgentResult.Failed(typed.error)
        } catch (badArgument: BadArgumentException) {
            // Separated from the general case because it is the failure an agent can act on
            // without reading prose: the argument is wrong, not the game.
            AgentResult.failed(AgentErrorKind.BAD_ARGUMENT, badArgument.message ?: command.name)
        } catch (failure: Exception) {
            AgentResult.failed(
                AgentErrorKind.TOOL_THREW,
                "${command.name} threw ${failure.javaClass.simpleName}: ${failure.message ?: "no message"}",
            )
        }
        lastElapsedNanos = clock.nowNanos() - startedAt
        reportIfSlow(command, lastElapsedNanos)
        return result
    }

    /**
     * Emits exactly one `slow_tool` event when a call overran its declared budget.
     *
     * An event and not a failure: the tool did what it was asked, and turning a slow answer
     * into an error would lose the answer. The event is what makes the cost visible to the
     * agent that caused it, in the same ring as everything else that happened this tick.
     */
    private fun reportIfSlow(command: AgentCommand, elapsedNanos: Long) {
        val budgetMs = tools.budgetMs(command.name)
        if (budgetMs <= 0L) return
        val elapsedMs = elapsedNanos / NANOS_PER_MILLI
        if (elapsedMs <= budgetMs) return
        bridge.event("slow_tool:${command.name}:${elapsedMs}ms:budget=${budgetMs}ms")
    }

    private companion object {
        const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}
