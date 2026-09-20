package dev.wildware.udea.render.kool

import de.fabmax.kool.input.InputStack
import de.fabmax.kool.util.Time
import dev.wildware.udea.render.input.InputFrame
import dev.wildware.udea.render.input.PointerId
import dev.wildware.udea.render.input.PointerMotion
import dev.wildware.udea.render.input.PointerPosition
import dev.wildware.udea.render.input.PointerReportListener
import dev.wildware.udea.render.input.PointerState
import dev.wildware.udea.render.input.PointerWheel
import dev.wildware.udea.render.input.UiPointers
import dev.wildware.udea.render.ui.UiLayer

/**
 * Kool's mouse and touches, as the intent sampler needs them - **after the interface has had first
 * refusal** (issue #227).
 *
 * The pointer half of what [KoolKeyboard] is for keys. Build one, hand it to a `DeviceIntent`, and
 * every `ActionBinding.pointerButtons` works:
 *
 * ```kotlin
 * val pointer = KoolPointer(ui)                         // `ui` is the UiLayer, or null for none
 * val source = DeviceIntent(game.bindings, keyboard, pointer = pointer)
 * ```
 *
 * It is also the mouse's motion ([PointerMotion], issue #248), which a camera turns by: summed every
 * frame, and spent by that camera rather than by the tick.
 *
 * ## Held back until the interface has judged it
 *
 * A key is offered to the interface and answered before it is recorded. A pointer cannot be: the
 * interface handles pointers when it draws, which on Kool's frame is after the game has ticked, so its
 * verdict on frame N's pointers ([UiPointers]) exists only once N is over. So each frame's *changes* -
 * a button going down, a button coming up, a pointer going away - are recorded as they are read and
 * then **held back**, and the verdict for that pointer in that frame decides what reaches the game:
 *
 * - **Not used:** the change is applied exactly as it would be with no interface at all.
 * - **Used:** a press is dropped. It never counts and never holds, so a click on a button never becomes
 *   an intent - nothing is retracted, because nothing was let through.
 * - **Either way, a release is applied.** Taking one away could only ever leave a button held down for
 *   the game after the player has let go of it: pressed on the scene and released over a button, say.
 *
 * The cost is one frame: a click on the scene reaches the tick after the one it would have with no
 * interface (settled in issue #224, and an ordering consequence rather than a threading one). With no
 * interface up nothing waits, because nothing is coming.
 *
 * ## Matched on the frame the pointer was read in
 *
 * A verdict carries the Kool frame the interface's own listener read the pointer in, and this matches
 * it against the frame *its* listener read the pointer in - both read `Time.frameCount` inside the same
 * pass over Kool's `InputStack`. Never against when the verdict arrives, which is after that frame's
 * tick, and after the next frame's pointers have been read whenever the two overlap.
 * A frame with no verdict of its own is settled as not used when a later verdict for the same pointer
 * arrives: the interface began listening after it, so nothing can have taken it.
 *
 * That relies on the interface reading every pointer this reads, and it does: this handler is pushed
 * to the *bottom* of Kool's `InputStack` and the interface's is pushed to the top, so anything that
 * blocks pointers from the interface blocks them from this too, and both keep only the pointers Kool
 * lists as valid.
 *
 * ## The verdict's granularity is the toolkit's
 *
 * `used` covers everything one pointer did in one frame. A press on the scene in the same frame as a
 * move a control handled is dropped with it. That is conservative - it can cost a click, never leak
 * one - and it is ComposeGL's report as specified, not a choice made here.
 *
 * ## Threads
 *
 * Kool calls the listener while it polls input, the verdict arrives while the interface's scene
 * renders, and `DeviceIntent.sample` reads the counters from the frame callback. All three are the
 * render thread, because `KoolThread` configures Kool with `asyncSceneUpdate = false`, and
 * `GlKoolInputTest` asserts it: Kool's input callback, the composition and the frame callback on one
 * thread. This listener runs inside the same `InputStack` pass as `KoolKeyboard`'s, and the verdict
 * inside the same scene render as that composition, so that test is this class's guard as much as the
 * keyboard's. Nothing here is synchronised.
 */
public class KoolPointer internal constructor(
    /** Asked whether a verdict is coming, and told each one. [UiPointers.NONE] with no interface. */
    private val ui: UiPointers,
) : PointerState, PointerMotion, PointerPosition, PointerWheel, AutoCloseable {

    /** Reads pointers after [ui] has had first refusal on them, or straight away when it is `null`. */
    public constructor(ui: UiLayer? = null) : this(ui?.pointers ?: UiPointers.NONE)

    /** Buttons the game holds, by pointer: a bit per button, as Kool's `buttonMask` numbers them. */
    private val held = HashMap<Int, Int>()

    /** How many pointers hold each button for the game, so a read allocates nothing. */
    private val holders = IntArray(BUTTONS)

    /** Presses counted since [endSample], by button. */
    private val presses = IntArray(BUTTONS)

    /** The button mask Kool last listed each pointer with, as read - before any verdict. */
    private val listed = HashMap<Int, Int>()

    /** Pointers listed in the frame being read, so the ones that went can be found at its end. */
    private val listedNow = HashSet<Int>()

    /** Changes read but not yet judged, oldest first. */
    private val waiting = ArrayList<Change>()

    /** The frame being read. */
    private var frame = InputFrame(0)

    private val listener = InputStack.PointerListener { state, _ ->
        beginFrame(InputFrame(Time.frameCount))
        for (pointer in state.pointers) {
            if (!pointer.isValid) continue
            onPointer(PointerId(pointer.id), pointer.buttonMask)
            onMotion(pointer.delta.x, pointer.delta.y)
            onPosition(pointer.pos.x, pointer.pos.y)
            onScroll(pointer.scroll.y)
        }
        endFrame()
    }

    private val handler = InputStack.InputHandler(HANDLER_NAME).also {
        it.pointerListeners += listener
    }

    init {
        // The bottom of the stack, as KoolKeyboard's is, and here it is load-bearing: see the class
        // KDoc on why every pointer read here is a pointer the interface reports on.
        InputStack.pushBottom(handler)
        ui.reportTo(PointerReportListener(::onReport))
    }

    override fun isButtonDown(button: Int): Boolean = button in 0 until BUTTONS && holders[button] > 0

    override fun pressesSince(button: Int): Int = if (button in 0 until BUTTONS) presses[button] else 0

    override fun endSample() {
        presses.fill(0)
    }

    /**
     * Pixels moved right since [spendMotion], every pointer summed. Not held back for the interface's
     * verdict, as a press is: see [PointerMotion] for why, and for what a reader does instead.
     */
    override var motionX: Float = 0f
        private set

    /** Pixels moved down the window since [spendMotion], every pointer summed. */
    override var motionY: Float = 0f
        private set

    override fun spendMotion() {
        motionX = 0f
        motionY = 0f
    }

    /**
     * One pointer's motion this frame, in Kool's window pixels: `+y` is down, as Kool's pointer
     * position runs. `internal`, like [onPointer], so it can be driven with no context.
     */
    internal fun onMotion(dx: Float, dy: Float) {
        motionX += dx
        motionY += dy
    }

    /**
     * Where the last pointer Kool listed this frame is, in window pixels (issue #262).
     *
     * **Not held back for the interface's verdict**, for [PointerMotion]'s reason: a cursor resting
     * on a button is still at a position, and hiding it would make a game's hover flicker as the
     * mouse crossed the interface. What the interface takes is the *press*, and a press it took
     * never reaches the tick - so a click on a button cannot become an order however well the game
     * knows where the cursor is.
     */
    override var isPointerOver: Boolean = false
        private set

    /** Pixels right of the window's left edge. */
    override var pointerX: Float = 0f
        private set

    /** Pixels down from the window's top edge, as Kool reports a pointer's position. */
    override var pointerY: Float = 0f
        private set

    /** Notches turned since [spendScroll], every pointer summed. */
    override var scrollY: Float = 0f
        private set

    override fun spendScroll() {
        scrollY = 0f
    }

    /** One pointer's position this frame, in Kool's window pixels. `internal`, like [onMotion]. */
    internal fun onPosition(x: Float, y: Float) {
        isPointerOver = true
        pointerX = x
        pointerY = y
    }

    /** One pointer's wheel this frame, in notches. `internal`, like [onMotion]. */
    internal fun onScroll(notches: Float) {
        scrollY += notches
    }

    /** Stops listening. After this, no pointer reaches the game through this. */
    override fun close() {
        InputStack.remove(handler)
        ui.reportTo(null)
        held.clear()
        holders.fill(0)
        presses.fill(0)
        listed.clear()
        waiting.clear()
        spendMotion()
        spendScroll()
        isPointerOver = false
    }

    /**
     * The start of one of Kool's frames of pointers.
     *
     * `internal`, with [onPointer] and [endFrame], so the reconciliation can be driven with no context:
     * `KoolPointerOrderTest` calls them with numbers it chose, and the listener with Kool's.
     */
    internal fun beginFrame(frame: InputFrame) {
        // An interface that went away will not judge what it was judging, so it is the game's now.
        if (!ui.isReporting) settleAll()
        this.frame = frame
        listedNow.clear()
        // Cleared here and set by each valid pointer read below, so a mouse that has left the
        // window stops reporting a position instead of leaving the last one it had standing for
        // ever - which would be a game aiming at wherever the cursor happened to go out.
        isPointerOver = false
    }

    /**
     * One pointer Kool lists this frame, with the buttons down now.
     *
     * Edges are read from the level, against the level this pointer was last listed with, and Kool's
     * `buttonEventMask` is deliberately not read. It says nothing the two levels do not: Kool computes
     * it as the old level against the new one, so a press and release between two polls - which Kool
     * folds into a level that never moved - sets no bit in it either. And where it does say more, it is
     * wrong: a mouse that left the window holding a button comes back listed with nothing down and that
     * button marked as changed, left over from before it went. Read as a click shorter than a frame, that
     * was a press nobody made (`GlKoolPointerTest`, step 5, found it).
     */
    internal fun onPointer(pointer: PointerId, buttons: Int) {
        val was = listed[pointer.value] ?: 0
        listed[pointer.value] = buttons
        listedNow += pointer.value

        val press = buttons and was.inv()
        val release = was and buttons.inv()
        if (press or release != 0) take(Change(pointer, frame, press, release))
    }

    /** The end of the frame: a pointer listed last frame and not this one has gone, with everything it held. */
    internal fun endFrame() {
        val entries = listed.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (entry.key in listedNow) continue
            entries.remove()
            take(Change(PointerId(entry.key), frame, press = 0, release = ALL_BUTTONS))
        }
    }

    /** The interface's verdict on [pointer] in [frame]. Render thread, after that frame's tick. */
    private fun onReport(pointer: PointerId, frame: InputFrame, used: Boolean) {
        var index = 0
        while (index < waiting.size) {
            val change = waiting[index]
            // A difference rather than a comparison, so the order survives Kool's counter wrapping.
            val age = change.frame.value - frame.value
            if (change.pointer != pointer || age > 0) {
                index++
                continue
            }
            waiting.removeAt(index)
            apply(change, used = age == 0 && used)
        }
    }

    private fun take(change: Change) {
        if (ui.isReporting) waiting += change else apply(change, used = false)
    }

    private fun settleAll() {
        for (change in waiting) apply(change, used = false)
        waiting.clear()
    }

    private fun apply(change: Change, used: Boolean) {
        val before = held[change.pointer.value] ?: 0
        var after = before
        if (!used) {
            after = after or change.press
            for (button in 0 until BUTTONS) {
                if (change.press and (1 shl button) != 0) presses[button]++
            }
        }
        after = after and change.release.inv()
        if (after == 0) held -= change.pointer.value else held[change.pointer.value] = after
        for (button in 0 until BUTTONS) {
            val bit = 1 shl button
            if (before and bit == 0 && after and bit != 0) holders[button]++
            if (before and bit != 0 && after and bit == 0) holders[button]--
        }
    }

    override fun toString(): String = "KoolPointer(ui=$ui)"

    /**
     * What one pointer's buttons did in one frame: the ones that went down and the ones that came up,
     * as bits the way Kool's `buttonMask` numbers them. Never the same bit in both.
     */
    private class Change(
        val pointer: PointerId,
        val frame: InputFrame,
        val press: Int,
        val release: Int,
    )

    private companion object {

        /** Kool's five pointer buttons: left, right, middle, back, forward. */
        const val BUTTONS = 5

        /** Every one of them, for a pointer that went. */
        const val ALL_BUTTONS = (1 shl BUTTONS) - 1

        /** What this handler is called on Kool's stack, so a person reading a dump can tell. */
        const val HANDLER_NAME = "udea-game-pointer"
    }
}
