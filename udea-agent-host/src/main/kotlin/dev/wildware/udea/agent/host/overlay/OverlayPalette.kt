package dev.wildware.udea.agent.host.overlay

import dev.wildware.udea.agent.activity.AgentOutcome
import dev.wildware.udea.agent.activity.AgentSessionId

/**
 * Whether a tool call *changed* the world, for the two-colour marker rule in spec 3.7.
 *
 * ## This is a heuristic, and it is stated as one
 *
 * An `AgentToolDef` publishes a name, a description, arguments and a schema. **None of them says
 * whether the tool mutates anything**, and the contract in `docs/contracts/agent-tools.md` is
 * frozen, so a `mutates` flag cannot be added to it here. The honest options were a lie by
 * omission - draw every marker the same, and lose the distinction the spec asks for - or a rule
 * over the one piece of information there is, which is the name.
 *
 * So: a tool whose function part begins with one of [WRITE_VERBS] is a write, and everything
 * else is a read. It is a verb table rather than a table of tool names, so a game's generated
 * `combat.apply_damage` is classified the day it is written with no edit here, and it fails in
 * the direction that costs least: a misclassified call is drawn in the wrong colour on a surface
 * the agent cannot see, and never changes what a tool returns or what a capture contains.
 *
 * If the frozen contract is ever reopened, the right fix is a declared flag and this object
 * should go.
 */
public object AgentCallKind {

    /**
     * Verbs that mean "this changed something".
     *
     * Derived from the tool surface as it stands - `world.set_component_field`,
     * `world.spawn_blueprint`, `world.destroy_entity`, `events.clear_events`, `time.pause`,
     * `time.resume`, `time.step`, `time.rewind`, `time.fast_forward`, `time.set_time_scale`,
     * `time.snapshot`, `render.set_camera`, `render.follow_entity`,
     * `render.toggle_debug_draw`, `agent.say`, `agent.clear_say` - plus the obvious near
     * neighbours a game will write. Everything absent from this list reads.
     */
    public val WRITE_VERBS: Set<String> = setOf(
        "set", "spawn", "destroy", "clear", "remove", "add", "apply", "grant", "revoke",
        "pause", "resume", "step", "rewind", "fast", "snapshot", "restore", "toggle",
        "follow", "move", "kill", "damage", "heal", "say", "write", "reset", "load", "save",
    )

    /**
     * Whether [toolName] is a write.
     *
     * The verb is the part after the toolset dot and before the first underscore, so
     * `world.set_component_field` yields `set` and `world.query_entities` yields `query`. A name
     * with no dot is treated as its own function part, which is what
     * `ToolManifest.toolsetOf`'s catch-all bucket does with it too.
     */
    public fun isWrite(toolName: String): Boolean {
        val dot = toolName.indexOf('.')
        val function = if (dot < 0) toolName else toolName.substring(dot + 1)
        val underscore = function.indexOf('_')
        val verb = if (underscore < 0) function else function.substring(0, underscore)
        return verb in WRITE_VERBS
    }
}

/**
 * The overlay's colours, as packed `0xRRGGBBAA` ints.
 *
 * ## Three dimensions, three visual channels, none of them shared
 *
 * A human has to read three things off one marker: *who* did it, *whether it changed anything*,
 * and *what it was about*. Overloading one channel with two of them makes an overlay you have to
 * hold a key for, so each gets its own:
 *
 * - **who** is the hue, from [SESSIONS], indexed by [AgentSessionId.raw];
 * - **read or write** is the brightness and the alpha - a write is opaque and bright, a read is
 *   dimmer, so a screen full of reads stays readable and the one mutation stands out;
 * - **entity or point** is the *shape*, a ring versus a cross, which [OverlayCanvas] draws.
 *
 * The palette is deliberately small and deliberately not a rainbow: with more than a handful of
 * concurrent sessions the hue stops being distinguishable anyway, and past [SESSIONS]`.size` two
 * sessions share a colour rather than the palette inventing an unreadable one.
 */
public object OverlayPalette {

    /**
     * One hue per session, indexed by `sessionId.raw % size`.
     *
     * Dense session ids from zero are what make that modulo meaningful: two concurrent sessions
     * are always two adjacent entries and therefore two different colours, rather than two
     * hashes that happened to collide. See `AgentSessions`.
     */
    public val SESSIONS: IntArray = intArrayOf(
        0x4FC3F7FF.toInt(), // blue
        0xFFB74DFF.toInt(), // amber
        0x81C784FF.toInt(), // green
        0xE57373FF.toInt(), // red
        0xBA68C8FF.toInt(), // purple
        0x4DD0E1FF.toInt(), // cyan
    )

    /** The panel background: near-black at 70% so the game stays visible behind it. */
    public const val PANEL: Int = 0x101014B3.toInt()

    /** Ordinary panel text. */
    public const val TEXT: Int = 0xE0E0E0FF.toInt()

    /** Secondary panel text: timings, tick numbers, the things you read second. */
    public const val TEXT_DIM: Int = 0x9E9E9EFF.toInt()

    /** A failed call. */
    public const val FAILED: Int = 0xEF5350FF.toInt()

    /** A call still in flight. */
    public const val RUNNING: Int = 0xFFF176FF.toInt()

    /** The colour for [session]. */
    public fun forSession(session: AgentSessionId): Int =
        SESSIONS[session.raw % SESSIONS.size]

    /** The colour a call's status line is drawn in. */
    public fun forOutcome(outcome: AgentOutcome, session: AgentSessionId): Int = when (outcome) {
        AgentOutcome.RUNNING -> RUNNING
        AgentOutcome.OK -> forSession(session)
        AgentOutcome.FAILED, AgentOutcome.UNKNOWN -> FAILED
    }

    /**
     * [rgba] with its alpha scaled by [factor], clamped to `0..255`.
     *
     * The read/write channel and the caption fade both go through here, so there is one place
     * that knows alpha is the low byte.
     */
    public fun withAlpha(rgba: Int, factor: Float): Int {
        val alpha = ((rgba and 0xFF) * factor).toInt().coerceIn(0, 0xFF)
        return (rgba.toLong() and 0xFFFFFF00L).toInt() or alpha
    }

    /** How much of a read marker's alpha survives, against a write's full strength. */
    public const val READ_ALPHA: Float = 0.45f
}
