package dev.wildware.udea.editor.gizmo

import dev.wildware.udea.render.view.GizmoCanvas
import dev.wildware.udea.render.view.GizmoLayer

/**
 * The gizmos in a Scene view (issues #235 and #236): each guide drawn, each handle drawn over them, and
 * a press on a handle taken before the editor picks any entity under it.
 *
 * Every shape is drawn and hit-tested by one [HandlePainter], the built-ins' and a game's alike. Where
 * two handles overlap, the one declared later is drawn on top and is the one a press takes.
 *
 * @param frame this frame's handles and guides, asked for each time the view draws or is pressed.
 */
internal class HandleLayer(private val frame: () -> GizmoFrame) : GizmoLayer {

    private val painter = HandlePainter()

    /** The handle the last press took, or `null` when it took none. */
    var pressed: ShownHandle? = null
        private set

    /** Which of the frame's handles [pressed] was, drawn lit while it is held. */
    private var pressedIndex = NONE

    override fun draw(canvas: GizmoCanvas) {
        val shown = frame()
        for (guide in shown.guides) painter.draw(canvas, guide)
        shown.handles.forEachIndexed { index, handle ->
            painter.draw(canvas, handle.at, handle.shape, handle.axes, lit = index == pressedIndex)
        }
    }

    override fun press(canvas: GizmoCanvas, viewX: Float, viewY: Float): Boolean {
        val handles = frame().handles
        pressedIndex = handles.indices.lastOrNull { index ->
            val handle = handles[index]
            painter.hits(canvas, handle.at, handle.shape, handle.axes, viewX, viewY)
        } ?: NONE
        pressed = handles.getOrNull(pressedIndex)
        return pressed != null
    }

    /** The drag on [pressed] is over: nothing is held, and nothing is drawn lit. */
    fun letGo() {
        pressed = null
        pressedIndex = NONE
    }

    override fun toString(): String = "HandleLayer(pressed=$pressed)"

    private companion object {
        const val NONE: Int = -1
    }
}
