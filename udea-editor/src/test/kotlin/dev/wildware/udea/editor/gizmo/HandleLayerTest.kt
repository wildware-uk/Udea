package dev.wildware.udea.editor.gizmo

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.render.view.EditorCamera
import dev.wildware.udea.render.view.GizmoCanvas
import dev.wildware.udea.render.view.GizmoLayer
import dev.wildware.udea.render.view.ViewPoint
import dev.wildware.udea.render.view.WorldViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The handle layer goes over the Scene view's layer rather than in place of it (issues #236 and
 * #243): a press no handle takes still reaches the layer underneath - the bone overlay's, put there
 * by the Animation panel, or a launcher's.
 *
 * Drawing is not reached from here: a `GizmoCanvas` is made only by `udea-render`, inside a GL pass.
 */
class HandleLayerTest {

    private val camera = EditorCamera().also { it.fit(WIDTH, HEIGHT) }
    private val scene = WorldViewport.detached(camera, WIDTH, HEIGHT)

    /** A layer that counts the presses that reach it, and takes every one. */
    private class Under : GizmoLayer {
        var presses = 0

        override fun draw(canvas: GizmoCanvas) = Unit

        override fun press(canvas: GizmoCanvas, viewX: Float, viewY: Float): Boolean {
            presses++
            return true
        }
    }

    /** One point handle at the world's origin. */
    private val frame = GizmoFrame.of(
        listOf(
            Handle(
                WorldPoint(0f, 0f),
                HandleShape.Point,
                DragConstraint.Across(Plane.XY),
                AxisFrame.WORLD,
                NetId.of(index = 1, generation = 0),
                PhysicsBody,
            ) {},
        ),
    )

    @Test
    fun `a press that misses every handle goes on to the layer underneath`() {
        val under = Under()
        val layer = HandleLayer(under) { frame }
        scene.gizmos = layer

        val miss = ViewPoint(10f, 10f)
        assertTrue(scene.pressGizmo(miss.x, miss.y), "a miss did not reach the layer underneath")
        assertEquals(1, under.presses)
        assertNull(layer.pressed, "a miss took a handle")
    }

    @Test
    fun `a press on a handle is the handle's, and the layer underneath never hears it`() {
        val under = Under()
        val layer = HandleLayer(under) { frame }
        scene.gizmos = layer
        val origin = ViewPoint().also { camera.project(0f, 0f, 0f, it) }

        assertTrue(scene.pressGizmo(origin.x, origin.y))
        assertSame(frame.handles.single(), layer.pressed)
        assertEquals(0, under.presses, "the handle's press went on to the layer underneath")
    }

    private companion object {
        const val WIDTH = 200
        const val HEIGHT = 100
    }
}
