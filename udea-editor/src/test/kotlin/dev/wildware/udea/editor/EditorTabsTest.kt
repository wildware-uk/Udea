package dev.wildware.udea.editor

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.activity.AgentSessions
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.view.GizmoCanvas
import dev.wildware.udea.render.view.GizmoLayer
import dev.wildware.udea.render.view.ViewDimension
import dev.wildware.udea.render.view.ViewPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Scene and Game tabs (issue #234) with no GL: which tab takes the pointer, and what the Scene
 * tab's pointer does to the editor camera and to a gizmo.
 *
 * The views are detached - no pass draws them - so these read the camera and the gizmo, never pixels.
 * `UiTest.click` answering false is the window leaving an event alone, which in a running editor is
 * what hands it to the game; `GlEditorTabsTest` watches it arrive there.
 */
class EditorTabsTest {

    private val views = EditorViews.detached(VIEW_WIDTH, VIEW_HEIGHT)
    private val camera = views.camera
    private val gizmo = Square()

    private val session = EditorSession(
        tools = EditorTools(AgentBridge(), AgentSessions().intern("editor")),
        tick = { Tick(0) },
        paused = { true },
        spawn = EditorSpawn("Spawn", BlueprintId("skeleton"), 0f, 0f),
        views = views,
    )

    init {
        views.scene.gizmos = gizmo
        views.game.gizmos = gizmo
    }

    private fun open(): UiTest = uiTest { session.window.content() }

    @Test
    fun `the window opens on the Scene tab, and the Game heading shows the Game tab instead`() {
        open().use { ui ->
            ui.assertExists(EditorTags.SCENE_VIEW)
            ui.assertDoesNotExist(EditorTags.GAME_VIEW)

            assertTrue(ui.click(EditorTags.GAME_TAB), "the Game heading took no click:\n${ui.dump()}")
            ui.settle()
            ui.assertExists(EditorTags.GAME_VIEW)
            ui.assertDoesNotExist(EditorTags.SCENE_VIEW)

            ui.click(EditorTags.SCENE_TAB)
            ui.settle()
            ui.assertExists(EditorTags.SCENE_VIEW)
        }
    }

    @Test
    fun `the Scene tab takes its pointer, and the Game tab leaves its pointer to the game`() {
        open().use { ui ->
            assertTrue(ui.click(EditorTags.SCENE_VIEW), "the Scene tab left a click to the game")

            ui.click(EditorTags.GAME_TAB)
            ui.settle()
            assertFalse(ui.click(EditorTags.GAME_VIEW), "the Game tab took a click the game should have had")
        }
    }

    @Test
    fun `a secondary drag in the Scene tab pans the world with the pointer, and the wheel zooms`() {
        open().use { ui ->
            val before = origin()
            val from = ui.offCentre(EditorTags.SCENE_VIEW)
            // The secondary button: the primary one selects (issue #235), `ScenePickingTest`'s.
            assertTrue(ui.press(from, PointerButton.Secondary), "the Scene tab took no press")
            ui.dragTo(Offset(from.x + DRAG, from.y + DRAG))
            ui.release(PointerButton.Secondary)

            val after = origin()
            val moved = DRAG * viewPerScreen(ui)
            assertNear(before.x + moved, after.x, "a drag right did not carry the world right with it")
            // Screen y grows downwards and view y upwards: a drag down carries the world down.
            assertNear(before.y - moved, after.y, "a drag down did not carry the world down with it")

            val zoom = camera.camera2D.zoom
            // Turned away from the user: ComposeGL's negative, the opposite of "scroll the content up".
            ui.scroll(EditorTags.SCENE_VIEW, Offset(0f, -1f))
            assertTrue(camera.camera2D.zoom < zoom, "the wheel turned forward did not zoom in: ${camera.camera2D.zoom}")
        }
    }

    @Test
    fun `a press on a gizmo is the gizmo's in the Scene tab, and the game's in the Game tab`() {
        open().use { ui ->
            val onGizmo = screenOf(ui, origin())
            val before = origin()
            assertTrue(ui.press(onGizmo), "the Scene tab took no press")
            ui.dragTo(Offset(onGizmo.x + DRAG, onGizmo.y))
            ui.release()
            assertEquals(1, gizmo.presses, "a press on the Scene tab's gizmo did not reach it")
            assertNear(before.x, origin().x, "a drag that began on a gizmo moved the camera")

            ui.click(EditorTags.GAME_TAB)
            ui.settle()
            ui.click(EditorTags.GAME_GIZMOS)
            ui.settle()
            assertTrue(views.game.showGizmos, "the Game tab's toggle did not turn its gizmos on")
            assertFalse(ui.click(onGizmo), "the Game tab took a click on a gizmo the game should have had")
            assertEquals(1, gizmo.presses, "a click in the Game tab reached a gizmo")
        }
    }

    @Test
    fun `in 3D a secondary drag orbits the editor camera and leaves the 2D camera where it was`() {
        open().use { ui ->
            assertTrue(ui.click(EditorTags.DIMENSION), "the 2D / 3D switch took no click")
            ui.settle()
            assertEquals(ViewDimension.ThreeD, camera.dimension)

            val yaw = camera.yawDegrees
            val pitch = camera.pitchDegrees
            val x = camera.camera2D.position.x
            val from = ui.offCentre(EditorTags.SCENE_VIEW)
            ui.press(from, PointerButton.Secondary)
            ui.dragTo(Offset(from.x + DRAG, from.y + DRAG))
            ui.release(PointerButton.Secondary)
            assertTrue(abs(camera.yawDegrees - yaw) > 1f, "a drag across did not turn the orbit: ${camera.yawDegrees}")
            assertTrue(camera.pitchDegrees > pitch, "a drag down did not raise the eye: ${camera.pitchDegrees}")
            assertEquals(x, camera.camera2D.position.x, "a 3D drag moved the 2D camera")

            // The middle button pans the orbit's centre instead.
            val target = camera.targetX
            ui.press(from, PointerButton.Tertiary)
            ui.dragTo(Offset(from.x + DRAG, from.y))
            ui.release(PointerButton.Tertiary)
            assertTrue(abs(camera.targetX - target) > 0.01f || abs(camera.targetY) > 0.01f, "a middle drag did not pan the centre")
        }
    }

    // --- fixture -------------------------------------------------------------------------

    /** Where world (0, 0) is in the Scene view, in view pixels from the bottom left. */
    private fun origin(): ViewPoint = ViewPoint().also { camera.project(0f, 0f, 0f, it) }

    /**
     * A point on [tag] clear of the gizmo on world (0, 0), which starts in the middle of the view, and
     * clear of the panels docked over the view's edges.
     */
    private fun UiTest.offCentre(tag: String): Offset {
        val centre = node(tag).boundsInRoot.centre
        return Offset(centre.x - OFF_CENTRE, centre.y + OFF_CENTRE)
    }

    /** View pixels per screen unit, through the Scene view's letterbox. */
    private fun viewPerScreen(ui: UiTest): Float {
        val box = ui.node(EditorTags.SCENE_VIEW).boundsInRoot
        return 1f / minOf(box.width.toInt().toFloat() / VIEW_WIDTH, box.height.toInt().toFloat() / VIEW_HEIGHT)
    }

    /** The screen point over view point [view]: [WorldViewport.toView][dev.wildware.udea.render.view.WorldViewport.toView] backwards. */
    private fun screenOf(ui: UiTest, view: ViewPoint): Offset {
        val box = ui.node(EditorTags.SCENE_VIEW).boundsInRoot
        val width = box.width.toInt().toFloat()
        val height = box.height.toInt().toFloat()
        val scale = minOf(width / VIEW_WIDTH, height / VIEW_HEIGHT)
        val left = (width - VIEW_WIDTH * scale) / 2f
        val bottom = (height - VIEW_HEIGHT * scale) / 2f
        return Offset(box.left + left + view.x * scale, box.top + height - (bottom + view.y * scale))
    }

    private fun assertNear(expected: Float, actual: Float, message: String) {
        assertTrue(abs(expected - actual) < 1f, "$message: expected $expected, was $actual")
    }

    /** A placeholder gizmo: a square [SIZE] view pixels across on world (0, 0), counting presses. */
    private class Square : GizmoLayer {
        private val at = ViewPoint()
        var presses = 0

        override fun draw(canvas: GizmoCanvas) {
            if (canvas.project(0f, 0f, 0f, at)) canvas.fill(at.x - SIZE / 2f, at.y - SIZE / 2f, SIZE, SIZE, Rgba.of(1f, 1f, 0f))
        }

        override fun press(canvas: GizmoCanvas, viewX: Float, viewY: Float): Boolean {
            if (!canvas.project(0f, 0f, 0f, at)) return false
            val hit = abs(viewX - at.x) <= SIZE / 2f && abs(viewY - at.y) <= SIZE / 2f
            if (hit) presses++
            return hit
        }

        companion object {
            const val SIZE = 40f
        }
    }

    private companion object {
        const val VIEW_WIDTH = 640
        const val VIEW_HEIGHT = 360

        /** How far each drag goes, in screen units. */
        const val DRAG = 60f

        /** How far from the middle of the view a drag starts, each way, in screen units. */
        const val OFF_CENTRE = 100f
    }
}
