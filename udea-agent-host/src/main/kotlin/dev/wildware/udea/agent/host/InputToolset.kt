package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentToolArg
import dev.wildware.udea.agent.AgentToolDef
import dev.wildware.udea.agent.ToolModule
import dev.wildware.udea.render.input.ActionId
import dev.wildware.udea.render.input.AxisId
import dev.wildware.udea.render.input.InjectedIntent
import kotlin.reflect.KClass

/** Why an `input.*` call could not do what it was asked. */
public object AgentInputErrors {

    /**
     * This process has no injected input source wired, so nothing an agent writes reaches a tick.
     *
     * Distinct from a bad action name, and the remedy is different: this is a host wiring fault
     * (the composition root never gave the toolset an `InjectedIntent`), and no argument value
     * fixes it. An agent told `no_such_action` for a game with no input at all would spend its
     * next three calls guessing names.
     */
    public val NO_INPUT_SOURCE: AgentErrorKind = AgentErrorKind("no_input_source")

    /** The game declares no action or axis by that name. The message lists what it does declare. */
    public val NO_SUCH_BINDING: AgentErrorKind = AgentErrorKind("no_such_binding")
}

/**
 * `input.press`, `input.release`, `input.tap`, `input.set_axis`, `input.release_all`, `input.state`.
 *
 * ## What this replaces, and why the old injection point was not good enough
 *
 * Phase 1's injection point was `Gdx.input.inputProcessor`: the agent posted synthetic key events
 * into LibGDX and the game read them as if a human had typed. Three things were wrong with it and
 * only the first is obvious.
 *
 * - It **cannot exist in `RenderMode.Headless`**, because there is no `Gdx.input`. So the mode an
 *   agent most often drives was the one mode with no input at all.
 * - Events arrive at *frame* boundaries. An agent that held a key "for ten ticks" actually held it
 *   for however many ticks ten frames happened to contain, which varies with the machine.
 * - It only worked at all while the game polled the device, which is the arrangement issue #124
 *   deletes.
 *
 * This writes an [InjectedIntent] instead - the same [dev.wildware.udea.render.input.IntentSource]
 * seam a keyboard writes through - so the simulation cannot tell an agent's input from a human's,
 * and neither can a replay. That is not a nicety: it is what makes "the agent drives the same
 * character the player does" a fact about the code rather than a claim about two similar paths.
 *
 * ## Why it names `udea-render` types directly, unlike `RenderToolset`
 *
 * [RenderToolset] goes through a [RenderControl] port because a game may hand the toolset a
 * renderer that is not `udea-render`. Input is not like that: the [InjectedIntent] *is* the
 * engine's input model, it holds no GL object, and a port here would be an interface with one
 * implementation whose only effect would be to make the wiring harder to read.
 *
 * ## What an agent still cannot reach through it
 *
 * The overlay hotkey. `GdxOverlayKey` polls `Gdx.input.isKeyPressed` directly - it is upstream of
 * every [dev.wildware.udea.render.input.IntentSource], and nothing here can reach it (issue #161).
 * An agent cannot turn off the panel that narrates what it is doing, and that is structural: the
 * arrow runs from a device to an intent and there is no arrow back.
 *
 * ## Threading
 *
 * Every tool here runs inside a `SimBarrier` drain, on the simulation thread. [InjectedIntent] is
 * atomic throughout, so a press recorded by any of them is seen by the next tick and by exactly
 * one tick - which is why `input.tap` is a reliable single edge rather than "probably one".
 */
public class InputToolset(
    /** Where an agent's input goes, or `null` when this process wired none. */
    private val injected: InjectedIntent? = null,
) {

    /** Holds [action] down until `input.release`, and records one press edge. */
    public fun press(action: String): AgentResult = withAction("press", action) { source, id ->
        source.press(id)
        AgentResult.ok {
            put("action", action)
            put("held", true)
        }
    }

    /** Releases [action]. Releasing something never held is not an error. */
    public fun release(action: String): AgentResult = withAction("release", action) { source, id ->
        source.release(id)
        AgentResult.ok {
            put("action", action)
            put("held", false)
        }
    }

    /**
     * One press edge with no hold: the synthesised equivalent of a key tapped between two frames.
     *
     * This is what an agent almost always wants for a one-shot - an ability, a menu confirm - and
     * getting it right by hand through `press`/`release` is impossible from outside: the two calls
     * could land in the same tick (one edge, never observably held) or straddle a `time.step`.
     */
    public fun tap(action: String): AgentResult = withAction("tap", action) { source, id ->
        source.tap(id)
        AgentResult.ok {
            put("action", action)
            put("tapped", true)
        }
    }

    /**
     * Deflects a 2D axis. `(0, 0)` centres it.
     *
     * Values outside `-1..1` are clamped by [InjectedIntent] rather than refused, because an agent
     * asking for `x = 2` means "as far right as it goes" and a refusal there teaches it nothing.
     * A non-finite value centres the axis, which is the only safe reading of `NaN`.
     */
    public fun setAxis(axis: String, x: Float, y: Float): AgentResult {
        val source = injected ?: return unwired("set_axis")
        val id = axisId(source, axis) ?: return unknown("set_axis", axis, isAxis = true)
        source.setAxis(id, x, y)
        return AgentResult.ok {
            put("axis", axis)
            put("x", source.axisX(id))
            put("y", source.axisY(id))
        }
    }

    /**
     * Releases everything and centres every axis.
     *
     * Worth its own tool rather than left to a loop of `release` calls: an agent that steps away
     * mid-session while holding "move right" leaves the character walking into a wall for as long
     * as the process lives, and the next agent to connect inherits it with no way to see why.
     */
    public fun releaseAll(): AgentResult {
        val source = injected ?: return unwired("release_all")
        source.releaseAll()
        return AgentResult.ok { put("released", source.catalog.actionCount) }
    }

    /**
     * What this game binds, and what the agent is currently holding.
     *
     * The discovery call. Without it an agent has to guess action names, and a wrong guess is
     * indistinguishable from a control that does nothing - so this lists every name the catalog
     * holds alongside the current state, which is the shape that makes the first `input.press` of
     * a session a considered call rather than a probe.
     */
    public fun state(): AgentResult {
        val source = injected ?: return unwired("state")
        val catalog = source.catalog
        return AgentResult.ok {
            arr("actions") {
                for (index in 0 until catalog.actionCount) {
                    val id = ActionId(index)
                    element {
                        put("name", catalog.nameOf(id))
                        put("held", source.isHeld(id))
                    }
                }
            }
            arr("axes") {
                for (index in 0 until catalog.axisCount) {
                    val id = AxisId(index)
                    element {
                        put("name", catalog.nameOf(id))
                        put("x", source.axisX(id))
                        put("y", source.axisY(id))
                    }
                }
            }
        }
    }

    override fun toString(): String = "InputToolset(wired=${injected != null})"

    private inline fun withAction(
        tool: String,
        action: String,
        body: (InjectedIntent, ActionId) -> AgentResult,
    ): AgentResult {
        val source = injected ?: return unwired(tool)
        val id = actionId(source, action) ?: return unknown(tool, action, isAxis = false)
        return body(source, id)
    }

    /**
     * The id, or `null`.
     *
     * `runCatching` rather than a `contains` check because [dev.wildware.udea.render.input.InputCatalog]
     * deliberately throws on an unknown name - a control that silently never fires is the failure
     * it exists to prevent - and a tool must turn that into a typed refusal rather than let the
     * dispatcher report `tool_threw`, which reads to an agent as an engine defect.
     */
    private fun actionId(source: InjectedIntent, name: String): ActionId? =
        runCatching { source.catalog.action(name) }.getOrNull()

    private fun axisId(source: InjectedIntent, name: String): AxisId? =
        runCatching { source.catalog.axis(name) }.getOrNull()

    private fun unwired(tool: String): AgentResult = AgentResult.failed(
        AgentInputErrors.NO_INPUT_SOURCE,
        "input.$tool cannot reach this game: no injected input source is wired into its agent " +
            "host, so nothing written here would ever be sampled by a tick. That is a host " +
            "wiring fault - the composition root builds an InjectedIntent and hands it both to " +
            "this toolset and to the game's IntentState - and no argument value fixes it.",
    )

    private fun unknown(tool: String, name: String, isAxis: Boolean): AgentResult {
        val catalog = injected?.catalog
        val known = if (isAxis) catalog?.axes else catalog?.actions
        return AgentResult.failed(
            AgentInputErrors.NO_SUCH_BINDING,
            "input.$tool: this game declares no ${if (isAxis) "axis" else "action"} called " +
                "'$name'. It declares ${known?.joinToString().orEmpty().ifEmpty { "none" }}. " +
                "Call input.state to list them rather than guessing.",
        )
    }
}

/**
 * The `input.*` tools, as a [ToolModule] of their own.
 *
 * **Deliberately not folded into [AgentHostTools]**, which is where the `render.*` declarations
 * live. A `ToolModule` is a promise that every tool in it has a receiver wired, and `ToolIndex`
 * refuses to build an index whose declaration has none - correctly, because a tool an agent can
 * see in the manifest and cannot call is worse than one that is absent. Putting these in
 * `AgentHostTools` would therefore have forced an `InputToolset` on every host that wanted a
 * screenshot, including the two demo harnesses and the render toolset's own test fixture, none
 * of which has a game with input in it.
 *
 * A host that drives input registers both lines:
 *
 * ```
 * ToolIndex.builder()
 *     .module(AgentInputTools)
 *     .toolset(InputToolset(injected))
 * ```
 */
public object AgentInputTools : ToolModule {

    override val moduleName: String = "UdeaAgentInput"

    override val tools: List<AgentToolDef<*>> = listOf(
        InputPressTool,
        InputReleaseAllTool,
        InputReleaseTool,
        InputSetAxisTool,
        InputStateTool,
        InputTapTool,
    ).sortedBy { it.name }
}

/** Base for this module's hand-written input declarations: everything but `invoke`. */
public abstract class InputToolDef(
    override val name: String,
    override val description: String,
    override val args: List<AgentToolArg>,
) : AgentToolDef<InputToolset> {

    /** Derived from [args]. See [RenderToolDef.inputSchema] for why it is not written by hand. */
    override val inputSchema: String = ToolSchema.of(args)

    override val owner: KClass<*> = InputToolset::class
}

private val ACTION_ARG = AgentToolArg(
    "action",
    "string",
    "Name of the action, as input.state lists it (they are namespaced, e.g. 'moba/attack').",
    required = true,
    default = null,
)

/** `input.press`. */
public object InputPressTool : InputToolDef(
    name = "input.press",
    description = "Hold a game action down, exactly as a player holding the key would. It stays " +
        "held across every following tick until input.release, so use it for movement and for " +
        "anything charged - and remember it is still held after you stop looking. For a " +
        "one-shot, use input.tap instead: it is a single press edge and cannot be left on. The " +
        "press is sampled by the next simulation tick, so a time.step in the same batch is what " +
        "makes it take effect.",
    args = listOf(ACTION_ARG),
) {
    override fun invoke(receiver: InputToolset, command: AgentCommand): Any? =
        receiver.press(command.str("action"))
}

/** `input.release`. */
public object InputReleaseTool : InputToolDef(
    name = "input.release",
    description = "Let go of an action held by input.press. Releasing one that was not held is " +
        "not an error. Reach for input.release_all instead when you are finishing a session or " +
        "have lost track of what is held - a forgotten held action walks the character into a " +
        "wall for as long as the process lives.",
    args = listOf(ACTION_ARG),
) {
    override fun invoke(receiver: InputToolset, command: AgentCommand): Any? =
        receiver.release(command.str("action"))
}

/** `input.tap`. */
public object InputTapTool : InputToolDef(
    name = "input.tap",
    description = "Fire an action once: one press edge, never held. This is what a human tapping " +
        "a key produces, and it is what you want for an ability, a confirm or a single swing. " +
        "It is deliberately not press-then-release - those two calls can land in the same tick " +
        "or straddle a time.step, so the duration would be whatever the schedule happened to be.",
    args = listOf(ACTION_ARG),
) {
    override fun invoke(receiver: InputToolset, command: AgentCommand): Any? =
        receiver.tap(command.str("action"))
}

/** `input.set_axis`. */
public object InputSetAxisTool : InputToolDef(
    name = "input.set_axis",
    description = "Deflect a 2D axis - this is how you walk. (1,0) is full right, (0,1) is full " +
        "up, and (0,0) centres it and stops. A partial deflection walks slowly, exactly as a " +
        "half-pushed stick does. The value persists until you change it, so set it back to (0,0) " +
        "when you have arrived. Values outside -1..1 are clamped rather than refused.",
    args = listOf(
        AgentToolArg(
            "axis",
            "string",
            "Name of the axis, as input.state lists it (e.g. 'moba/move').",
            required = true,
            default = null,
        ),
        AgentToolArg("x", "number", "Horizontal deflection, -1..1.", required = false, default = "0"),
        AgentToolArg("y", "number", "Vertical deflection, -1..1. Positive is up.", required = false, default = "0"),
    ),
) {
    override fun invoke(receiver: InputToolset, command: AgentCommand): Any? =
        receiver.setAxis(command.str("axis"), command.float("x", 0f), command.float("y", 0f))
}

/** `input.release_all`. */
public object InputReleaseAllTool : InputToolDef(
    name = "input.release_all",
    description = "Let go of every action and centre every axis. Call it when you finish driving " +
        "the game, or whenever the character is behaving as though it is still being told to do " +
        "something - a held action survives a time.step, a screenshot and your own forgetting.",
    args = emptyList(),
) {
    override fun invoke(receiver: InputToolset, command: AgentCommand): Any? = receiver.releaseAll()
}

/** `input.state`. */
public object InputStateTool : InputToolDef(
    name = "input.state",
    description = "List every action and axis this game binds, with what you are currently " +
        "holding. Call it before your first input.press: a misspelt action name is refused, but " +
        "a *plausible* wrong one you never had is indistinguishable from a control that does " +
        "nothing, and this is what tells the two apart.",
    args = emptyList(),
) {
    override fun invoke(receiver: InputToolset, command: AgentCommand): Any? = receiver.state()
}
