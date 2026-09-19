package dev.wildware.udea.editor

import dev.wildware.udea.core.identity.NetId
import kotlin.math.abs

/**
 * What the primary button does in the Scene tab once no gizmo handle took the press (issue #235):
 * select what it clicks, and box-select what a drag on empty space touches.
 *
 * - **Click** replaces the selection with the front-most entity under the pointer. A click on
 *   nothing clears it.
 * - **Click again on the same spot** selects the next entity behind the last one picked there,
 *   round to the front again after the back-most: how to reach a unit hidden under another.
 * - **Shift-click** adds the front-most entity under the pointer to the selection, or takes it out
 *   if it is already in. A Shift-click on nothing changes nothing.
 * - **A drag that starts on empty space** draws a box; on release it selects every entity whose
 *   drawn rectangle it touches, instead of the selection or, with Shift held, as well as it. A drag
 *   that starts on an entity selects nothing: moving what is under the pointer is the gizmos' (G5).
 *
 * Every change is an `editor.select` call through [selection]. Positions are view pixels from the
 * bottom left. Render thread only.
 */
internal class ScenePicking(
    private val picker: ScenePicker,
    private val selection: EditorSelection,
) {

    /** The selection box being dragged, in view pixels, or `null` when there is none. */
    var box: Box? = null
        private set

    private var pressed = false
    private var pressX = 0f
    private var pressY = 0f
    private var shift = false

    /** Whether the press was on empty space, so a drag from it is a box. */
    private var onEmpty = false

    /** Where the last plain click picked, what was under it front first, and which it took. */
    private var cycleX = Float.NaN
    private var cycleY = Float.NaN
    private var cycleStack: List<NetId> = emptyList()
    private var cycleIndex = 0

    /** True from any change to [box] until [consumeChanged] reads it: the Scene tab must draw again. */
    private var changed = false

    /** The primary button went down at ([x], [y]), with Shift held or not. */
    fun press(x: Float, y: Float, shift: Boolean) {
        pressed = true
        pressX = x
        pressY = y
        this.shift = shift
        onEmpty = picker.under(x, y).isEmpty()
        setBox(null)
    }

    /** The pointer moved to ([x], [y]) with the button down. */
    fun drag(x: Float, y: Float) {
        if (!pressed || !onEmpty) return
        if (box == null && !beyondSlop(x, y)) return
        setBox(Box(pressX, pressY, x, y))
    }

    /** The button came up at ([x], [y]): a click, or the end of a box. */
    fun release(x: Float, y: Float) {
        if (!pressed) return
        pressed = false
        val dragged = box
        setBox(null)
        when {
            dragged != null -> boxed(picker.touching(dragged.x0, dragged.y0, dragged.x1, dragged.y1))
            !beyondSlop(x, y) -> clicked(picker.under(pressX, pressY))
        }
    }

    /** The gesture was taken away - the window lost the pointer. Nothing is selected. */
    fun cancel() {
        pressed = false
        setBox(null)
    }

    /** Whether [box] has changed since the last call. */
    fun consumeChanged(): Boolean = changed.also { changed = false }

    private fun clicked(stack: List<NetId>) {
        if (shift) {
            forgetCycle()
            val front = stack.firstOrNull() ?: return
            if (front in selection.ids) selection.remove(listOf(front)) else selection.add(listOf(front))
            return
        }
        if (stack.isEmpty()) {
            forgetCycle()
            selection.replace(emptyList())
            return
        }
        val again = abs(pressX - cycleX) <= SLOP && abs(pressY - cycleY) <= SLOP && stack == cycleStack
        cycleIndex = if (again) (cycleIndex + 1) % stack.size else 0
        cycleX = pressX
        cycleY = pressY
        cycleStack = stack
        selection.replace(listOf(stack[cycleIndex]))
    }

    private fun boxed(touched: List<NetId>) {
        forgetCycle()
        if (shift) selection.add(touched) else selection.replace(touched)
    }

    private fun forgetCycle() {
        cycleX = Float.NaN
        cycleY = Float.NaN
        cycleStack = emptyList()
        cycleIndex = 0
    }

    private fun beyondSlop(x: Float, y: Float): Boolean = abs(x - pressX) > SLOP || abs(y - pressY) > SLOP

    private fun setBox(next: Box?) {
        if (next == box) return
        box = next
        changed = true
    }

    override fun toString(): String = "ScenePicking(box=$box, cycle=$cycleIndex of ${cycleStack.size})"

    /** A selection box from where the drag began, ([x0], [y0]), to where the pointer is, ([x1], [y1]). */
    internal data class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    internal companion object {

        /**
         * How far the pointer may move between press and release, in view pixels, and still be a
         * click; and how near a click must be to the last one to reach the entity behind. A hand
         * clicking twice does not land on the same pixel.
         */
        const val SLOP: Float = 4f
    }
}
