package dev.wildware.udea.editor.gizmo

import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.render.view.EditorCamera
import dev.wildware.udea.render.view.GizmoCanvas
import dev.wildware.udea.render.view.GizmoLayer
import dev.wildware.udea.render.view.WorldViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The mark layer goes over a Scene view's own gizmo layer rather than in place of it (issue #243): a
 * launcher's handles, put there before the Animation panel, still get every press. A mark has
 * nothing to grab, so on its own the layer takes none.
 */
class GizmoMarkLayerTest {

    private val world = configureWorld { }
    private val netIds = NetIdIndex()
    private val scene = WorldViewport.detached(EditorCamera(), WIDTH, HEIGHT)

    /** A handle layer that takes every press and counts them. */
    private class Handles : GizmoLayer {
        var presses = 0

        override fun draw(canvas: GizmoCanvas) = Unit

        override fun press(canvas: GizmoCanvas, viewX: Float, viewY: Float): Boolean {
            presses++
            return true
        }
    }

    @Test
    fun `a press goes on to the layer the marks were put over`() {
        val handles = Handles()
        scene.gizmos = GizmoMarkLayer(world, netIds, selection = { emptyList() }, gizmos = emptyList(), under = handles)
        assertTrue(scene.pressGizmo(WIDTH / 2f, HEIGHT / 2f), "the handles under the marks did not take the press")
        assertEquals(1, handles.presses)
    }

    @Test
    fun `on its own the layer takes no press`() {
        scene.gizmos = GizmoMarkLayer(world, netIds, selection = { emptyList() }, gizmos = emptyList())
        assertFalse(scene.pressGizmo(WIDTH / 2f, HEIGHT / 2f), "a mark took a press, and there is nothing in it to grab")
    }

    private companion object {
        const val WIDTH = 200
        const val HEIGHT = 100
    }
}
