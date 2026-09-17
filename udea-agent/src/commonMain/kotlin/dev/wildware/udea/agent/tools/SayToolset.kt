package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.activity.AgentNarration
import dev.wildware.udea.agent.dispatch.AgentContext
import dev.wildware.udea.annotations.AgentTool
import dev.wildware.udea.annotations.Arg

/**
 * `agent.say`: the one-line caption the human watching the window reads, and the agent cannot
 * (spec 3.7, issue #158).
 *
 * ## A write-only tool, which is unusual enough to justify
 *
 * There is no `agent.read_narration`, the caption is absent from `/state`, and no tool result
 * echoes it back beyond the confirmation of the write itself. That is the whole point. An agent
 * doing visual verification - capture, act, capture, diff - that could see its own caption would
 * read it changing between two frames as *the game* changing, and would then spend a context
 * window debugging a difference it caused itself. The same reasoning makes the overlay draw on a
 * target no capture reads.
 *
 * The confirmation *is* returned, with the deadline, because a tool that succeeded silently is
 * indistinguishable from one that was never registered.
 *
 * ## Why `agent.say` and not `agent_say`
 *
 * Spec 3.7 names the capability `agent_say`. The tree addresses every tool as
 * `<toolset>.<function>` and `ToolManifest.toolsetOf` groups the manifest on that dot, so a name
 * with no dot lands in the manifest's catch-all bucket beside nothing else. `agent.say` is the
 * same capability under the tree's own naming rule, and it is the name in the manifest, the
 * schema and the `?cmd=` parameter alike.
 */
public class SayToolset(
    private val bridge: AgentBridge,
) {

    /**
     * Sets the caption.
     *
     * The session comes off [AgentCommand.session] and not off an argument, deliberately: an
     * agent that could *name* a session could impersonate another one in a human's overlay, and
     * an agent that could read the session back could branch on which of several agents it is.
     * The HTTP layer decides who the caller is; the tool never sees the decision.
     *
     * That is also the only reason this tool takes an [AgentContext]: the session is on the
     * command, the command is on the context, and there is nowhere else to read it. It does
     * *not* use [AgentContext.answerLater] - the caption is set inside the drain like any other
     * mutation - so the generated object being a [ContextualToolDef] is about reaching the
     * caller's identity, not about running late.
     */
    @AgentTool(
        name = "agent.say",
        description = "Put a one-line description of what you are currently doing on " +
            "the human's screen, for a few seconds. It is for a person watching a " +
            "windowed instance; you cannot read it back, it never appears in /state and " +
            "it never appears in a screenshot, so it cannot affect anything you " +
            "observe. Say what you are trying to establish, not what tool you are about " +
            "to call - the tool calls are already on screen.",
    )
    public fun say(
        context: AgentContext,
        @Arg(
            description = "The line to show, at most ${AgentNarration.MAX_LENGTH} characters. " +
                "A longer line is refused rather than truncated.",
        )
        text: String,
        @Arg(
            description = "Wall-clock seconds to show it for, up to " +
                "${AgentNarration.MAX_TTL_SECONDS}.",
            required = false,
            default = DEFAULT_TTL_SECONDS_TEXT,
        )
        ttlSeconds: Float,
    ): AgentResult {
        val refusal = bridge.narration.say(text, ttlSeconds, context.command.session)
        if (refusal != null) {
            return AgentResult.failed(AgentErrorKind.BAD_ARGUMENT, refusal.message)
        }
        return AgentResult.ok {
            // Deliberately not the text. Echoing it back would make this readable through the
            // command-result ring, which `/state` renders - and the caption would be inside the
            // document the agent polls after all.
            put("shown", true)
            put("ttlSeconds", ttlSeconds)
            put("chars", text.length)
        }
    }

    /** Clears the caption, without waiting for it to expire. */
    @AgentTool(
        name = "agent.clear_say",
        description = "Remove the on-screen caption now, instead of waiting for it to " +
            "expire. Reach for it when you have finished a piece of work and the next " +
            "thing you do is unrelated, so a human glancing at the window is not told " +
            "you are still doing the previous thing.",
    )
    public fun clear(): AgentResult {
        bridge.narration.clear()
        return AgentResult.ok { put("cleared", true) }
    }

    override fun toString(): String = "SayToolset"

    public companion object {

        /**
         * Thirty seconds.
         *
         * About as long as one step of an agent's work takes, so a caption set and then
         * forgotten clears itself rather than mislabelling the window for the next thing the
         * agent does.
         */
        public const val DEFAULT_TTL_SECONDS: Float = 30f

        /**
         * [DEFAULT_TTL_SECONDS] as the text `@Arg.default` takes.
         *
         * Written out rather than `DEFAULT_TTL_SECONDS.toString()` because an annotation
         * argument has to be a compile-time constant, and a second literal is exactly the pair
         * that drifts - so `SayToolsetTest` asserts the two agree.
         */
        public const val DEFAULT_TTL_SECONDS_TEXT: String = "30"
    }
}
