package dev.wildware.udea.render.input

/**
 * Where the cursor is, in window pixels (issue #262).
 *
 * ## Why this is not on [PointerState]
 *
 * [PointerState] is the *sampler's* view of the mouse: buttons held, and a counted edge that the
 * tick spends once per tick. Position is neither. It is a level nobody spends, and it is read on the
 * **frame** clock rather than the tick clock, because the only thing that can turn it into something
 * the simulation may see is presentation - `WorldPointer` un-projects it through the camera the
 * frame was drawn with. Putting it on [PointerState] would have made a per-frame reading available
 * to a per-tick sampler, which is precisely the mix-up [PointerMotion] was split out to avoid.
 *
 * So this sits beside [PointerState], [PointerMotion] and [PointerWheel], each read on its own
 * clock, and `KoolPointer` implements all four over one device.
 *
 * ## The pixels
 *
 * The window's, measured the way every backend reports a pointer: `+x` is right of the left edge and
 * `+y` is **down** from the top edge. Not the view pixels `CameraPick` and `EditorCamera` use, which
 * run up from the bottom - the one place the two meet is `WorldPointer.aim`, and it turns them round
 * there so that nothing else has to know.
 *
 * ## Threads
 *
 * [KeyboardState]'s rule: recorded on the render thread and read on it, so not synchronised.
 */
public interface PointerPosition {

    /**
     * Whether a pointer is over the window at all.
     *
     * False with the mouse outside the window, and false before it has ever been inside one. A game
     * that read the coordinates without asking this would aim at whatever the cursor last passed
     * over, for ever, which is a unit selected by a mouse that is not in the window.
     */
    public val isPointerOver: Boolean

    /** Pixels right of the window's left edge. Meaningless unless [isPointerOver]. */
    public val pointerX: Float

    /** Pixels down from the window's top edge. Meaningless unless [isPointerOver]. */
    public val pointerY: Float

    public companion object {

        /** No mouse and no touch screen. */
        public val NONE: PointerPosition = object : PointerPosition {
            override val isPointerOver: Boolean get() = false
            override val pointerX: Float get() = 0f
            override val pointerY: Float get() = 0f
            override fun toString(): String = "PointerPosition.NONE"
        }
    }
}

/**
 * How far the wheel has been turned since whoever reads it last spent it (issue #262).
 *
 * A per-frame delta, like [PointerMotion], and spent separately from it **on purpose**: motion turns
 * a camera and the wheel zooms one, and in an isometric game those are two different readers. One
 * shared `spend` would let whichever ran first take the other's input, which is a zoom that works
 * only while the mouse is still.
 *
 * ## Threads
 *
 * [PointerMotion]'s rule exactly.
 */
public interface PointerWheel {

    /**
     * Notches turned since [spendScroll] was last called.
     *
     * Positive is the wheel pushed away from the player. That is the backend's report and not a
     * policy: what a notch *means* is the game's, and `IsoPanControl.zoomPerNotch` is where the
     * shipped isometric control says which way it takes one.
     */
    public val scrollY: Float

    /** Zeroes [scrollY]: the notches so far have been used. */
    public fun spendScroll()

    public companion object {

        /** A wheel nobody turns. */
        public val NONE: PointerWheel = object : PointerWheel {
            override val scrollY: Float get() = 0f
            override fun spendScroll(): Unit = Unit
            override fun toString(): String = "PointerWheel.NONE"
        }
    }
}
