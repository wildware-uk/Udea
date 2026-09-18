package dev.wildware.udea.agent.host.overlay

import dev.wildware.udea.render.overlay.HardwareKeyState

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
 * Turns the hardware key into a verbosity level, one step per press.
 *
 * [HardwareKeyState] itself now lives in `udea-render` (issue #211): the LibGDX implementation,
 * `GdxOverlayKey`, went with LibGDX, and its Kool replacement is issue #224's. This class is
 * unaffected either way - it only ever depended on the port, never on a real device.
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
