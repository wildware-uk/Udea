package dev.wildware.udea.editor

import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.view.GizmoCanvas
import dev.wildware.udea.render.view.GizmoLayer

/**
 * What the Scene tab draws over the world (issue #235): an outline round each selected entity, the
 * selection box while one is dragged, and then the gizmo [handles] on top.
 *
 * It is the Scene view's `GizmoLayer`, so everything it draws is excluded from `render.screenshot`
 * by the same structure as a gizmo (issue #234) and shows in `editor.screenshot(view=scene)`.
 *
 * A press is offered to [handles] alone: an outline and a box are pictures, not controls.
 */
internal class SceneOverlay(
    private val picker: ScenePicker,
    private val selection: EditorSelection,
    private val picking: ScenePicking,
    /** The gizmo handles, drawn over everything else and hit-tested before any entity. */
    var handles: GizmoLayer?,
) : GizmoLayer {

    override fun draw(canvas: GizmoCanvas) {
        for (bounds in picker.bounds(selection.ids)) {
            outline(canvas, bounds.left, bounds.bottom, bounds.right, bounds.top, SELECTED)
        }
        picking.box?.let { box ->
            val left = minOf(box.x0, box.x1)
            val bottom = minOf(box.y0, box.y1)
            val right = maxOf(box.x0, box.x1)
            val top = maxOf(box.y0, box.y1)
            canvas.fill(left, bottom, right - left, top - bottom, BOX_FILL)
            outline(canvas, left, bottom, right, top, BOX_EDGE)
        }
        handles?.draw(canvas)
    }

    override fun press(canvas: GizmoCanvas, viewX: Float, viewY: Float): Boolean =
        handles?.press(canvas, viewX, viewY) ?: false

    private fun outline(canvas: GizmoCanvas, left: Float, bottom: Float, right: Float, top: Float, colour: Rgba) {
        val width = right - left
        val height = top - bottom
        canvas.fill(left - EDGE, bottom - EDGE, width + 2f * EDGE, EDGE, colour)
        canvas.fill(left - EDGE, top, width + 2f * EDGE, EDGE, colour)
        canvas.fill(left - EDGE, bottom, EDGE, height, colour)
        canvas.fill(right, bottom, EDGE, height, colour)
    }

    override fun toString(): String = "SceneOverlay(handles=$handles)"

    private companion object {

        /** An outline's thickness, in view pixels: it keeps its size however far the camera zooms. */
        const val EDGE: Float = 2f

        /** A selected entity's outline: the editor's accent, bright enough to read over any sprite. */
        val SELECTED: Rgba = Rgba.of(1f, 0.62f, 0.1f)

        /** The selection box's edge and its faint fill. */
        val BOX_EDGE: Rgba = Rgba.of(0.55f, 0.8f, 1f)
        val BOX_FILL: Rgba = Rgba.of(0.55f, 0.8f, 1f, 0.15f)
    }
}
