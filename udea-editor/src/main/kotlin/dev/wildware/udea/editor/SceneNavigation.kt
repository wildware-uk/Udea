package dev.wildware.udea.editor

import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.udea.render.view.ViewDimension
import dev.wildware.udea.render.view.ViewPoint
import dev.wildware.udea.render.view.WorldViewport
import kotlin.math.pow

/**
 * What a pointer does in the Scene tab (issues #234 and #235): gizmo handles first, then entities,
 * then the editor camera.
 *
 * - **A primary press on a gizmo handle** is the gizmo's, and the drag that follows is its
 *   [GizmoDrag]: one edit session from press to release (issue #236).
 * - **Otherwise the primary button selects** ([ScenePicking]): a click picks the entity under the
 *   pointer, Shift-click toggles it, and a drag from empty space box-selects.
 * - **The other buttons move the camera.** In 2D the secondary and middle buttons pan, the world
 *   following the pointer. In 3D the secondary button orbits round the centre - across turns about Z,
 *   up and down raises and lowers the eye - and the middle button pans the centre across the view.
 * - **The wheel zooms** about the point under the pointer in 2D, and moves the eye in or out in 3D:
 *   turned away from the user it zooms in. ComposeGL reports that turn as a negative `delta.y` (its
 *   positive is "scroll the content up", the wheel pulled back).
 *
 * Every event over the picture is taken, so none reaches the game: the Scene tab's pointer is the
 * editor's. The Game tab has no handler at all, which is what hands its pointer to the game.
 *
 * Positions arrive as a `SceneView` reports them - the picture's own pixels, from its top left - and
 * are mapped through the view's letterbox into view pixels from the bottom left before anything reads
 * them.
 *
 * @param shift whether Shift is held, read at each press: ComposeGL's pointer events carry no
 *   modifiers, so the window keeps it from its key events ([EditorKeys]).
 * @param gizmos what a drag on a handle does, or `null` in an editor with no gizmos.
 */
internal class SceneNavigation(
    private val view: WorldViewport,
    private val picking: ScenePicking,
    private val shift: () -> Boolean,
    private val gizmos: GizmoDrag? = null,
) {

    private val camera = checkNotNull(view.camera) { "$view is the Game tab, which the editor does not navigate" }

    private val at = ViewPoint()

    private var drag: Drag = Drag.None

    private var lastX = 0f

    private var lastY = 0f

    /** True from any change to the camera until [consumeMoved] reads it: the Scene tab must draw again. */
    private var moved = false

    /**
     * Handles [event] over a picture of [pictureWidth] x [pictureHeight] pixels.
     *
     * @return true when the event was the editor's - always, over the picture.
     */
    fun onPointer(event: PointerEvent, pictureWidth: Int, pictureHeight: Int): Boolean {
        val onView = view.toView(event.position.x, event.position.y, pictureWidth, pictureHeight, at)
        when (event) {
            is PointerEvent.Press -> {
                drag = when {
                    !onView -> Drag.None
                    event.button == PointerButton.Primary && view.pressGizmo(at.x, at.y) -> Drag.Gizmo.also {
                        gizmos?.press(at.x, at.y)
                        // The pressed handle is drawn lit.
                        moved = true
                    }
                    event.button == PointerButton.Primary -> Drag.Select.also { picking.press(at.x, at.y, shift()) }
                    camera.dimension == ViewDimension.ThreeD && event.button == PointerButton.Secondary -> Drag.Orbit
                    else -> Drag.Pan
                }
                lastX = at.x
                lastY = at.y
            }

            // Mapped even off the view: a drag that leaves the picture keeps arriving.
            is PointerEvent.Move -> when (drag) {
                Drag.Pan, Drag.Orbit -> {
                    val dx = at.x - lastX
                    val dy = at.y - lastY
                    lastX = at.x
                    lastY = at.y
                    if (drag == Drag.Pan) camera.pan(dx, dy) else camera.orbit(-dx * ORBIT_DEGREES_PER_PIXEL, -dy * ORBIT_DEGREES_PER_PIXEL)
                    moved = true
                }
                Drag.Select -> picking.drag(at.x, at.y)
                Drag.Gizmo -> gizmos?.move(at.x, at.y)
                Drag.None -> Unit
            }

            is PointerEvent.Release -> {
                when (drag) {
                    Drag.Select -> picking.release(at.x, at.y)
                    Drag.Gizmo -> gizmos?.release().also { moved = true }
                    Drag.None, Drag.Pan, Drag.Orbit -> Unit
                }
                drag = Drag.None
            }

            is PointerEvent.Cancel -> {
                when (drag) {
                    Drag.Select -> picking.cancel()
                    Drag.Gizmo -> gizmos?.cancel().also { moved = true }
                    Drag.None, Drag.Pan, Drag.Orbit -> Unit
                }
                drag = Drag.None
            }

            is PointerEvent.Scroll -> if (onView && event.delta.y != 0f) {
                camera.zoomAt(ZOOM_STEP.pow(event.delta.y), at.x, at.y)
                moved = true
            }

            // Leaving the window ends hover, not a drag; the release still arrives.
            is PointerEvent.Exit -> Unit
        }
        return true
    }

    /** Whether the camera has moved since the last call. */
    fun consumeMoved(): Boolean = moved.also { moved = false }

    /** Marks the Scene tab out of date for a change that did not come through a pointer. */
    fun markMoved() {
        moved = true
    }

    override fun toString(): String = "SceneNavigation($view, $drag)"

    private enum class Drag { None, Gizmo, Select, Pan, Orbit }

    private companion object {

        /** Degrees the orbit turns per view pixel dragged: a drag across a 640-pixel view is most of a turn. */
        const val ORBIT_DEGREES_PER_PIXEL = 0.4f

        /** How much one notch of the wheel zooms: out by this factor, or in by its inverse. */
        const val ZOOM_STEP = 1.15f
    }
}
