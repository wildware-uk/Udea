package dev.wildware.udea.render.input

/**
 * One of the backend's pointers: the mouse, or one finger.
 *
 * The backend's own number: on Kool it is `Pointer.id`, and the mouse is `PointerInput.MOUSE_POINTER_ID`. A finger's number is reused once it
 * lifts, so an id names a pointer only for as long as the backend keeps listing it.
 */
@JvmInline
internal value class PointerId(val value: Int) {
    override fun toString(): String = "PointerId($value)"
}

/**
 * The backend's frame counter, and **only** for pairing a verdict with the pointers it is about.
 *
 * Kool's `Time.frameCount` on this renderer. It is not time and it is not a tick: a frame can contain
 * any number of ticks, and nothing about it may reach the simulation - an [Intent] carries no stamp at
 * all, and a stamp that did would be a `Tick`. It exists so a verdict that arrives a frame late is
 * matched to the frame the pointer was read in rather than to the frame it happened to arrive in.
 */
@JvmInline
internal value class InputFrame(val value: Int) {
    override fun toString(): String = "InputFrame($value)"
}

/**
 * Hears the interface's verdict on one pointer in one frame. See [UiPointers].
 */
internal fun interface PointerReportListener {

    /**
     * Whether the interface [used] [pointer] in the frame it was read in, [frame].
     *
     * Every valid pointer is reported once per frame, used or not, and a pointer that lifted or left
     * is reported once more in the frame it went. So this is a stream to reconcile rather than an
     * event to react to.
     */
    fun onReport(pointer: PointerId, frame: InputFrame, used: Boolean)
}

/**
 * The interface's **first refusal** on a pointer: the seam beside [UiInput], not a widening of it.
 *
 * A click on a button must not also fire whatever the mouse button is bound to underneath. That much
 * is [UiInput]'s rule, but the answer comes back in a different shape, for a reason outside this
 * engine, and each of the three differences is why this is a second interface rather than a second
 * method on the first:
 *
 * - **Per pointer per frame, not per event.** The toolkit judges everything a pointer did in one of
 *   the backend's frames at once, and says whether it used any of it.
 * - **A frame late, not synchronous.** The backend reads pointers, the game ticks, and only then does
 *   the interface draw - which is where it handles pointers and where its verdict first exists. So a
 *   key is asked and answered before it is recorded, while a pointer is recorded and then *held back*
 *   until its verdict arrives (`KoolPointer`). A click the interface took never becomes an intent at
 *   all: nothing is retracted, because nothing was ever let through.
 * - **Pushed, not returned.** The interface calls [PointerReportListener.onReport], so [reportTo] is
 *   how a listener is put in place.
 *
 * ## Internal, and why
 *
 * [UiInput] is public because a game can put any interface in front of the keyboard. This one has a
 * single implementation, `UiLayer`'s, and a single reader, `KoolPointer`, whose public constructor
 * takes the `UiLayer` itself - so nothing outside this module needs to name it.
 *
 * ## The render thread
 *
 * Both members and every report are on the render thread, the one the interface draws on, the one
 * input is polled on and the one the simulation ticks on. `KoolPointer`'s KDoc names the test that
 * holds those three to being one thread.
 */
internal interface UiPointers {

    /**
     * Whether a verdict is coming for the pointers read this frame.
     *
     * `false` when no interface is up, in which case nothing waits: a pointer is the game's as soon as
     * it is read, because there is nothing that could take it.
     */
    val isReporting: Boolean

    /** Where verdicts go from now on, or nowhere when `null`. There is at most one listener. */
    fun reportTo(listener: PointerReportListener?)

    companion object {

        /**
         * No interface: every pointer is the game's, at once.
         *
         * Not a null check at the call site, for the reason [UiInput.NONE] is not one either.
         */
        val NONE: UiPointers = object : UiPointers {
            override val isReporting: Boolean get() = false
            override fun reportTo(listener: PointerReportListener?): Unit = Unit
            override fun toString(): String = "UiPointers.NONE"
        }
    }
}
