package dev.wildware.udea.agent.dispatch

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentToolArg

/**
 * Every tool this simulation can run, and how to run one.
 *
 * ## Why the dispatcher takes an interface it does not implement
 *
 * The real implementation is **generated**: `udea-codegen` emits one `ToolModule` per module
 * from the `@AgentTool` functions it sees, and a runtime index merges them through
 * `ServiceLoader`. The dispatcher must not know that, for the reason spec 5 gives for
 * ServiceLoader discovery in the first place - no magic package, no classpath scan - and
 * because a dispatcher that could only be exercised by running a KSP round would have no
 * unit tests worth the name. A hand-written registry in a test drives every path here.
 *
 * ## What an implementation owes
 *
 * - [invoke] returns a value for every outcome it can describe, including refusals. Throwing
 *   is permitted and contained (the dispatcher answers `tool_threw`), but a refusal a tool
 *   *expected* should be an [AgentResult.Failed] with a kind that says what to do instead.
 * - [invoke] runs on the simulation thread, at a tick boundary, inside a `SimBarrier` drain.
 *   It may mutate the world directly. It must not block, sleep or wait on another thread:
 *   whatever it waits for cannot happen, because the thread that would do it is this one.
 * - Work that must happen *after* the tick goes through [AgentContext.defer].
 */
public interface ToolRegistry {

    /** Whether a tool named [toolName] exists. Checked before [invoke] so an unknown name is one answer. */
    public fun contains(toolName: String): Boolean

    /**
     * How long [toolName] is expected to take, in milliseconds; `0` for no declared budget.
     *
     * Advisory, and deliberately so. Exceeding it emits a `slow_tool` event and nothing else -
     * the dispatcher does not cancel, because a tool half-way through mutating the world is
     * precisely the torn state `SimBarrier` exists to prevent. Timeouts remain the caller's
     * business: only the caller knows how long it is prepared to wait.
     */
    public fun budgetMs(toolName: String): Long

    /** Runs [command] against [context] and returns what it produced. */
    public fun invoke(command: AgentCommand, context: AgentContext): AgentResult

    /**
     * The published arguments of [toolName], or an empty list for a tool this registry has never
     * heard of.
     *
     * ## Why the dispatcher needs the declaration and not just the call
     *
     * The activity overlay (spec 3.7) anchors a world-space marker to what a call was *about* -
     * a ring on the entity it inspected, a pin where a spawn landed. That anchor is derived from
     * the tool's **declared** arguments, by
     * [dev.wildware.udea.agent.activity.AnchorRule], and not from a table of tool names: half
     * the tool surface is generated from `@AgentTool` in a game module the engine has never
     * heard of, so a name table here could not be complete even in principle, and the overlay
     * would silently stop marking every tool added after it was written.
     *
     * Declared on the registry rather than reached for through a cast to
     * [dev.wildware.udea.agent.dispatch.ToolIndex] because the dispatcher takes this interface
     * precisely so it can be driven by a hand-written registry in a test - and a cast would make
     * the anchor path the one part of dispatch those tests could not reach.
     */
    public fun declaredArgs(toolName: String): List<AgentToolArg>

    public companion object {
        /** A registry with no tools. Every call answers `no_such_tool`. */
        public val EMPTY: ToolRegistry = object : ToolRegistry {
            override fun contains(toolName: String): Boolean = false

            override fun budgetMs(toolName: String): Long = 0L

            override fun declaredArgs(toolName: String): List<AgentToolArg> = emptyList()

            override fun invoke(command: AgentCommand, context: AgentContext): AgentResult =
                AgentResult.failed(
                    dev.wildware.udea.agent.AgentErrorKind.NO_SUCH_TOOL,
                    "no tools are registered on this simulation",
                )

            override fun toString(): String = "ToolRegistry.EMPTY"
        }
    }
}
