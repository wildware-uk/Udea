package dev.wildware.udea.agent.dispatch

import com.github.quillraven.fleks.World
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.Tick

/**
 * What a tool is handed when it runs.
 *
 * Constructed by [AgentDispatcher] for one command and thrown away, so a tool cannot keep it
 * and reach the world from an HTTP thread later - which is the one thing this whole design is
 * arranged to make impossible.
 *
 * ## Why the world is here and not on `GameContext`
 *
 * `GameContext` is the sole Fleks injectable and holds engine services; the `World` is not one
 * of them, and adding it would make the context a handle to everything. A tool genuinely needs
 * the world - that is what a tool is - so it is passed at the call, where the fact that this
 * code mutates a world is visible in the signature.
 */
public class AgentContext internal constructor(
    /** The world this command runs against. Safe to mutate: this is a tick boundary. */
    public val world: World,
    /** Engine services - the clock, the RNG, the scene manager, the barrier. */
    public val game: GameContext,
    /** The command being served. A tool reads its arguments from here. */
    public val command: AgentCommand,
    private val deferred: DeferredQueue,
    private val bridge: AgentBridge,
) {

    /** Whether [answerLater] was called. Read by [AgentDispatcher] to decide who completes. */
    internal var answersLater: Boolean = false
        private set

    /** The tick about to be simulated. Every duration an agent gives or gets is in these. */
    public val tick: Tick get() = game.clock.tick

    /**
     * Registers [work] to run after every system of this tick, before the state document is
     * rebuilt.
     *
     * For the mutations that must not happen mid-tick even at a barrier: a scene swap, a
     * shutdown, anything that invalidates the world the systems are about to iterate. The
     * reference implementation deferred exactly these and for exactly this reason
     * (`DebugInspector.applyCommands`).
     *
     * The ordering guarantee is worth stating because the agent depends on it: deferred work
     * runs **before** the digest is published, so an agent that reads the state after its
     * command confirms sees the deferred effect. Publishing first would show it a world one
     * step stale from its own mutation, which reads exactly like a command that did not work.
     */
    public fun defer(work: DeferredWork) {
        deferred.add(command.name, work)
    }

    /**
     * Runs [work] after the tick and completes this command with whatever it returns.
     *
     * ## The tool this exists for, and why it cannot be written any other way
     *
     * A tool call runs **inside** a `SimBarrier` drain - that is the whole point of
     * [AgentRuntime], and it is what makes a mutation land at a tick boundary. But
     * `Simulation.step` drains the barrier, and `SimBarrier.drain` refuses to re-enter: a
     * nested drain swaps the batch the outer one is walking and destroys every action queued
     * since it started. So a tool that **runs the simulation** - `time.step`, `time.rewind`,
     * `time.fast_forward` - cannot do its work where it is called. `TimeControl.rewind` says the
     * same thing from the other side: it forces a drain of its own, and the only thing that
     * makes that safe is the loop being stopped between frames.
     *
     * [defer] already runs work at the right moment - after every system of this tick, before
     * the state document is published, outside any drain. What it could not do is *answer*: the
     * dispatcher completes the command the instant the tool returns, so a deferred step would
     * confirm before it had happened, which is the one failure an agent cannot recover from.
     *
     * This is [defer] with the answer attached. The command completes when [work] returns, so
     * `completedCommandId` still means "it happened", and the digest that carries the
     * confirmation is the one published after the work ran.
     *
     * **A throw is still an answer.** [work] failing completes the command as `tool_threw`
     * rather than leaving it outstanding - a command that never completes is a healthy game
     * reported as frozen, which is exactly what `AgentDispatcher` exists to prevent.
     *
     * @throws IllegalStateException if called twice for one command. Two answers to one id is
     *   two entries in the result ring for the same command, and a caller reading its own
     *   answer would get whichever was written last.
     */
    public fun answerLater(work: () -> AgentResult) {
        check(!answersLater) {
            "${command.name} already deferred its answer; one command has one answer"
        }
        answersLater = true
        val id = command.id
        val name = command.name
        deferred.add(name) {
            val answer = try {
                work()
            } catch (failure: Exception) {
                AgentResult.failed(
                    AgentErrorKind.TOOL_THREW,
                    "$name threw ${failure.javaClass.simpleName} after the tick: " +
                        (failure.message ?: "no message"),
                )
            }
            bridge.complete(id, answer)
        }
    }
}

/** A piece of work registered with [AgentContext.defer]. */
public fun interface DeferredWork {
    /** Runs after the tick, on the simulation thread. */
    public fun run()
}

/**
 * The queue [AgentContext.defer] appends to.
 *
 * Internal, and drained only by [AgentRuntime]: deferred work exists to be run at one
 * specified point in the loop, so a second caller draining it would move that point.
 *
 * A failure is contained per item, for the same reason a `SimBarrier` action failure is: the
 * remaining work belongs to other commands, and stranding it because one tool threw would turn
 * one bad argument into a wedged instance.
 */
internal class DeferredQueue {

    private val labels = ArrayList<String>()
    private val work = ArrayList<DeferredWork>()

    val size: Int get() = work.size

    fun add(label: String, item: DeferredWork) {
        labels.add(label)
        work.add(item)
    }

    /**
     * Runs everything queued, in submission order, and clears the queue.
     *
     * Work registered *while* this runs is queued for the next drain rather than run inside
     * this one, so a tool that defers a tool that defers cannot spin the loop.
     *
     * @return how many items ran, whether they threw or not.
     */
    fun runAll(onFailure: (String, Exception) -> Unit): Int {
        val count = work.size
        if (count == 0) return 0
        var index = 0
        while (index < count) {
            try {
                work[index].run()
            } catch (failure: Exception) {
                onFailure(labels[index], failure)
            }
            index++
        }
        // Only the items that were present at entry: `add` may have appended more.
        labels.subList(0, count).clear()
        work.subList(0, count).clear()
        return count
    }
}
