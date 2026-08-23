package dev.wildware.udea.agent.host.overlay

/**
 * How much of the agent activity overlay is on screen.
 *
 * Ordered least to most, because [OverlayVerbosityControl] cycles by ordinal and a human pressing
 * the key expects "more, more, more, off" rather than an order they have to learn.
 */
public enum class OverlayVerbosity(
    /** What the key press announces, so the first press tells a human what the key does. */
    public val label: String,
) {
    /** Nothing drawn at all. The overlay costs one branch per frame. */
    OFF("off"),

    /** Session identity and the caption. What a human wants while watching the game itself. */
    CAPTION("caption"),

    /** The caption plus the recent-call panel and world markers. The default. */
    NORMAL("normal"),

    /** Everything, with per-call timings, outcomes and command ids. For debugging the agent. */
    VERBOSE("verbose");

    /** The next level in the cycle, wrapping. */
    public fun next(): OverlayVerbosity = LEVELS[(ordinal + 1) % LEVELS.size]

    /** Whether world-space markers are drawn at this level. */
    public val showsMarkers: Boolean get() = ordinal >= NORMAL.ordinal

    /** Whether the recent-call panel is drawn at this level. */
    public val showsCalls: Boolean get() = ordinal >= NORMAL.ordinal

    /** Whether per-call timings and command ids are drawn at this level. */
    public val showsTimings: Boolean get() = ordinal >= VERBOSE.ordinal

    public companion object {
        /** Hoisted: `entries` materialises a list on each access, and [next] is on a frame path. */
        private val LEVELS: Array<OverlayVerbosity> = entries.toTypedArray()

        /** What an instance starts at. */
        public val DEFAULT: OverlayVerbosity = NORMAL
    }
}

/**
 * Whether the human's overlay key is physically down **right now**, read from the hardware.
 *
 * ## Why this is its own port, and the whole of issue #161
 *
 * The agent can synthesise input. That is a deliberate feature - an agent has to be able to
 * drive the game - and it is implemented by injecting into whatever the game reads intents from.
 * If the overlay key were read from *that*, an agent replaying a recorded input stream, or
 * fuzzing, or simply pressing the wrong key, could **switch the human's overlay off, or on, in
 * the middle of a capture**. On means the human sees nothing they expected; on mid-capture is
 * worse, because the agent has then changed a thing it is not supposed to be able to observe and
 * a human's understanding of the session silently diverges from it.
 *
 * So this reads the physical device, upstream of any injected intent source, and there is
 * deliberately no way to write to it: the interface has one method and it returns a state rather
 * than consuming an event queue. An implementation is `Gdx.input.isKeyPressed` in `udea-render`
 * - the *real* keyboard, not the game's input mapping - and that is exactly one hop with nothing
 * agent-writable on it.
 *
 * `OverlayHotkeyIsHardwareTest` asserts the separation by driving the injected side and the
 * hardware side independently and checking only the hardware one moves the level.
 */
public fun interface HardwareKeyState {

    /** Whether the overlay key is down at this instant. */
    public fun isOverlayKeyDown(): Boolean

    public companion object {
        /** Never pressed. What a headless or offscreen instance is wired with. */
        public val NEVER: HardwareKeyState = HardwareKeyState { false }
    }
}

/**
 * Turns the hardware key into a verbosity level, one step per press.
 *
 * ## Edge-triggered, and why that is not a detail
 *
 * [HardwareKeyState] reports a *level* - the key is down - and a frame runs sixty times a
 * second, so reacting to the level would cycle the overlay sixty times during one human key
 * press. The rising edge is what a press is. The previous state is held here rather than in the
 * key source, so a source can stay a one-method `fun interface` with no memory to get wrong.
 *
 * ## No wall clock, no simulation clock
 *
 * Neither is needed: a rising edge is a comparison of two booleans. That matters because this
 * runs inside the presentation frame, where reading `SimClock` is forbidden outright (an overlay
 * is handed `dtSeconds` and never a `Tick`, and the type says so), and where a wall clock would
 * be a debounce constant somebody would have to tune per keyboard.
 */
public class OverlayVerbosityControl(
    private val keys: HardwareKeyState,
    /** Where it starts. */
    initial: OverlayVerbosity = OverlayVerbosity.DEFAULT,
) {

    /** The level right now. */
    public var verbosity: OverlayVerbosity = initial
        private set

    private var wasDown: Boolean = false

    /** How many presses have been handled. Asserted by the hotkey test. */
    public var presses: Long = 0L
        private set

    /**
     * Samples the key and advances the level on a rising edge.
     *
     * Called once per presentation frame, before anything is drawn.
     *
     * @return the level to draw at, which is [verbosity].
     */
    public fun poll(): OverlayVerbosity {
        val down = keys.isOverlayKeyDown()
        if (down && !wasDown) {
            verbosity = verbosity.next()
            presses++
        }
        wasDown = down
        return verbosity
    }

    /** Sets the level directly, for a launch flag or a test. Does not disturb the edge state. */
    public fun set(level: OverlayVerbosity) {
        verbosity = level
    }

    override fun toString(): String = "OverlayVerbosityControl($verbosity, $presses press(es))"
}
