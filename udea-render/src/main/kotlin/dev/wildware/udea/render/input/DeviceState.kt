package dev.wildware.udea.render.input

/**
 * The keyboard, as the sampler needs it: what is down, and what was tapped since the last tick.
 *
 * An interface rather than direct `Gdx.input` calls for two reasons and only one of them is
 * testability. The other is the reason this whole issue exists: `Gdx.input.isKeyJustPressed` is
 * reset **per frame**, so a tick that runs twice in one frame reads the same "just pressed"
 * twice, and a key tapped and released between two frames is never reported at all. This shape -
 * a level read plus a *counted* edge that the sampler consumes - is what makes one press produce
 * exactly one edge whatever the frame pattern was.
 *
 * ## Threading
 *
 * Presses are recorded by whatever pumps the window's event queue (the render thread) and
 * consumed by the tick. On every host this engine ships those are the same thread - `GameLoop`
 * ticks from inside the render callback - so the implementation is deliberately not synchronised.
 * A host that ticks on a thread of its own must supply its own implementation and say so.
 */
public interface KeyboardState {

    /** Whether [keycode] (a `com.badlogic.gdx.Input.Keys` value) is down right now. */
    public fun isKeyDown(keycode: Int): Boolean

    /** How many times [keycode] went down since [endSample] was last called. */
    public fun pressesSince(keycode: Int): Int

    /**
     * Marks the end of one tick's sample: every press counted so far is now spent.
     *
     * Called once per tick by [DeviceIntent], **after** every binding has read its counts, so
     * two actions bound to the same key both see the press rather than the first eating it.
     */
    public fun endSample()

    public companion object {
        /** A keyboard nobody is at. */
        public val NONE: KeyboardState = object : KeyboardState {
            override fun isKeyDown(keycode: Int): Boolean = false
            override fun pressesSince(keycode: Int): Int = 0
            override fun endSample(): Unit = Unit
            override fun toString(): String = "KeyboardState.NONE"
        }
    }
}

/**
 * One gamepad's sticks and buttons.
 *
 * ## Stated plainly: nothing implements this against real hardware yet
 *
 * LibGDX's gamepad support lives in `gdx-controllers`, which is a separate artifact and is not
 * on this repository's dependency graph. So [NONE] is the only implementation that ships, a
 * stick is unreadable on a real machine today, and the axis half of a binding is exercised by
 * tests and by an agent rather than by a thumb. Everything *above* this interface is finished -
 * the deadzone, the radial rescale, the combination with the keyboard vector - so wiring a real
 * pad is one class and one dependency line, not a design change. It is written down here rather
 * than left for somebody to discover from a controller that does nothing.
 */
public interface GamepadState {

    /** Whether a pad is present. `false` makes every read below zero by definition. */
    public val isConnected: Boolean

    /** Axis [axis] in `-1..1`, raw - no deadzone applied. */
    public fun axis(axis: Int): Float

    /** Whether button [button] is down. */
    public fun isButtonDown(button: Int): Boolean

    /** How many times [button] went down since [endSample]. See [KeyboardState.pressesSince]. */
    public fun pressesSince(button: Int): Int

    /** Spends the counted presses. See [KeyboardState.endSample]. */
    public fun endSample()

    public companion object {
        /** No pad plugged in. */
        public val NONE: GamepadState = object : GamepadState {
            override val isConnected: Boolean get() = false
            override fun axis(axis: Int): Float = 0f
            override fun isButtonDown(button: Int): Boolean = false
            override fun pressesSince(button: Int): Int = 0
            override fun endSample(): Unit = Unit
            override fun toString(): String = "GamepadState.NONE"
        }
    }
}
