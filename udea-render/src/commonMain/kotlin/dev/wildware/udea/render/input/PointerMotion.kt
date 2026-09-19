package dev.wildware.udea.render.input

/**
 * How far the mouse has moved, in window pixels, since whoever reads it last spent it.
 *
 * What turns a camera (issue #248). It sits beside [PointerState] rather than inside it because the
 * two are read on different clocks: [PointerState]'s presses are spent once per **tick** by
 * `DeviceIntent`, while motion is spent once per **frame** by the presentation code that turns a view
 * with it. Motion never reaches an `Intent`, so nothing in the simulation depends on it; a game that
 * wants the mouse to steer a unit turns a camera with it, and makes the unit's intent relative to that
 * camera on the input side.
 *
 * The pixels are the window's, measured the way the backend reports a pointer: `+x` is right and
 * `+y` is **down** the screen. Every pointer's motion is summed, so a finger dragging on a touch
 * screen moves it as the mouse does.
 *
 * ## Raw, not judged by the interface
 *
 * Unlike a press, motion is not held back for the interface's verdict (see `KoolPointer`): a pointer
 * moving across a button is not a click on it. A reader that should not turn while the pointer is on
 * the interface turns only while a button is held down, and a press the interface took never holds
 * one - which is what `ThirdPersonRig.turnButton` does.
 *
 * ## Threading
 *
 * [KeyboardState]'s rule: recorded on the render thread and read on it, so not synchronised.
 */
public interface PointerMotion {

    /** Pixels moved right since [spendMotion] was last called; negative is left. */
    public val motionX: Float

    /** Pixels moved down the screen since [spendMotion] was last called; negative is up. */
    public val motionY: Float

    /** Zeroes [motionX] and [motionY]: the motion so far has been used. */
    public fun spendMotion()

    public companion object {
        /** A mouse that never moves. */
        public val NONE: PointerMotion = object : PointerMotion {
            override val motionX: Float get() = 0f
            override val motionY: Float get() = 0f
            override fun spendMotion(): Unit = Unit
            override fun toString(): String = "PointerMotion.NONE"
        }
    }
}
