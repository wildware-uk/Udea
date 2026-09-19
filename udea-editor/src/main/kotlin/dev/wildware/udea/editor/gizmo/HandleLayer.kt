package dev.wildware.udea.editor.gizmo

import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.view.GizmoCanvas
import dev.wildware.udea.render.view.GizmoLayer
import dev.wildware.udea.render.view.ViewPoint
import kotlin.math.abs

/**
 * Gizmo [Handle]s in a Scene view: each drawn where it sits, and a press on one taken before the
 * editor picks any entity under it (issue #235).
 *
 * The hit-test is the one thing this adds, and it is on the public API's own values: a handle is hit
 * when the press lands within [GRAB] view pixels of the point its world position projects to. Every
 * shape keeps a constant size on screen (issue #233), so a handle is as easy to grab zoomed out as
 * zoomed in. How each shape is drawn, and what a drag on one does, are the built-in gizmos' (G5, G6);
 * here every handle is a square.
 *
 * @param handles this frame's handles, asked for each time the view draws or is pressed.
 */
internal class HandleLayer(private val handles: () -> List<Handle<*>>) : GizmoLayer {

    private val at = ViewPoint()

    /** The handle the last press took, or `null` when it took none. */
    var pressed: Handle<*>? = null
        private set

    override fun draw(canvas: GizmoCanvas) {
        for (handle in handles()) {
            if (!canvas.project(handle.at.x, handle.at.y, handle.at.z, at)) continue
            canvas.fill(at.x - DRAWN / 2f, at.y - DRAWN / 2f, DRAWN, DRAWN, COLOUR)
        }
    }

    override fun press(canvas: GizmoCanvas, viewX: Float, viewY: Float): Boolean {
        pressed = handles().firstOrNull { handle ->
            canvas.project(handle.at.x, handle.at.y, handle.at.z, at) && abs(viewX - at.x) <= GRAB && abs(viewY - at.y) <= GRAB
        }
        return pressed != null
    }

    override fun toString(): String = "HandleLayer(pressed=$pressed)"

    internal companion object {

        /** How far from a handle's centre a press still grabs it, in view pixels. */
        const val GRAB: Float = 8f

        /** A handle's drawn size, in view pixels: a little smaller than what grabs it. */
        const val DRAWN: Float = 10f

        val COLOUR: Rgba = Rgba.of(1f, 0.9f, 0.2f)
    }
}
