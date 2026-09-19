package dev.wildware.udea.render.input

import dev.wildware.udea.assets.InputKey

/**
 * The keyboard, as the sampler needs it: what is down, and what was tapped since the last tick.
 *
 * An interface rather than direct device calls for two reasons and only one of them is
 * testability. The other is the reason this whole issue exists: `Gdx.input.isKeyJustPressed` is
 * reset **per frame**, so a tick that runs twice in one frame reads the same "just pressed"
 * twice, and a key tapped and released between two frames is never reported at all. This shape -
 * a level read plus a *counted* edge that the sampler consumes - is what makes one press produce
 * exactly one edge whatever the frame pattern was.
 *
 * ## Threading
 *
 * Presses are recorded by whatever pumps the window's event queue (the render thread) and
 * consumed by the tick. On every host this engine ships those are the same thread - `KoolThread`
 * ticks from inside Kool's frame callback, and configures Kool with `asyncSceneUpdate = false` so
 * that polling input and drawing are that same thread too - and `GlKoolInputTest` asserts it. So
 * the implementation is deliberately not synchronised. A host that ticks on a thread of its own
 * must supply its own implementation and say so.
 */
public interface KeyboardState {

    /**
     * Whether [key] is down right now.
     *
     * By name, never by a backend's number (issue #228): `KoolKeyboard` translates what the backend
     * reports through `KoolKeyTable`, so a caller - `DeviceIntent`, a test, an agent - cannot hold
     * a number that means a different key on another backend.
     */
    public fun isKeyDown(key: InputKey): Boolean

    /** How many times [key] went down since [endSample] was last called. */
    public fun pressesSince(key: InputKey): Int

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
            override fun isKeyDown(key: InputKey): Boolean = false
            override fun pressesSince(key: InputKey): Int = 0
            override fun endSample(): Unit = Unit
            override fun toString(): String = "KeyboardState.NONE"
        }
    }
}

/**
 * The mouse and every finger, as the sampler needs them: which pointer buttons are down, and how
 * many times each went down since the last tick.
 *
 * The same shape as [KeyboardState] - a level plus a counted edge that [endSample] spends - for the
 * same reason: a click pressed and released between two ticks is still exactly one press.
 *
 * A button is the backend's own index: on Kool `0` is the left button, `1` the right, `2` the
 * middle, `3` back and `4` forward (`PointerInput.LEFT_BUTTON` and its neighbours), and a finger on
 * a touch screen presses `0`. Every pointer is folded together: a button is down when any pointer
 * holds it, and two fingers landing are two presses.
 *
 * ## Threading
 *
 * [KeyboardState]'s rule exactly: recorded on the render thread, read by the tick on that same
 * thread, and so deliberately not synchronised.
 */
public interface PointerState {

    /** Whether any pointer holds [button] down, as far as the game is concerned. */
    public fun isButtonDown(button: Int): Boolean

    /** How many times [button] went down since [endSample] was last called. */
    public fun pressesSince(button: Int): Int

    /** Spends the counted presses. See [KeyboardState.endSample]. */
    public fun endSample()

    public companion object {
        /** No mouse and no touch screen. */
        public val NONE: PointerState = object : PointerState {
            override fun isButtonDown(button: Int): Boolean = false
            override fun pressesSince(button: Int): Int = 0
            override fun endSample(): Unit = Unit
            override fun toString(): String = "PointerState.NONE"
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
