package dev.wildware.udea.editor

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.editor.gizmo.DragConstraint
import dev.wildware.udea.editor.gizmo.Gizmo
import dev.wildware.udea.editor.gizmo.GizmoFrame
import dev.wildware.udea.editor.gizmo.GizmoScope
import dev.wildware.udea.editor.gizmo.GizmoTarget
import dev.wildware.udea.editor.gizmo.HandleLayer
import dev.wildware.udea.editor.gizmo.HandleShape
import dev.wildware.udea.editor.gizmo.Plane
import dev.wildware.udea.editor.gizmo.WorldPoint
import dev.wildware.udea.editor.gizmo.handles
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.view.EditorCamera
import dev.wildware.udea.render.view.GizmoLayer
import dev.wildware.udea.render.view.PickBounds
import dev.wildware.udea.render.view.ViewPoint
import dev.wildware.udea.render.view.WorldViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Selecting in the Scene tab (issue #235) through the window's own pointer, with no GL: click,
 * Shift-click and box select, what is in front, and a gizmo handle before any entity.
 *
 * The pointer goes in through the window, as a person's does, and what is asserted is the selection
 * an agent reads with `editor.selection` - the real `EditorToolset` answers the window's
 * `editor.select` calls ([EditorToolLoop]). The entities are made pickable by [Squares], a render
 * system that reports squares, which is all a game's own render system has to do. Every check that
 * something was selected has a check beside it that a miss selects nothing, so a window that
 * selected everything, or nothing, fails one or the other.
 *
 * `GlScenePickingTest` does the same through a real Kool backend and real mouse and keyboard events.
 */
class ScenePickingTest {

    private val loop = EditorToolLoop(GameHost(RenderMode.Headless, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList())))

    private val left = loop.spawn()
    private val right = loop.spawn()
    private val far = loop.spawn()

    /** The Scene tab's camera, fitted up front so the squares can be placed in view pixels. */
    private val camera = EditorCamera().also { it.fit(VIEW_WIDTH, VIEW_HEIGHT) }

    private fun session(vararg sources: PickBounds, handles: GizmoLayer? = null): EditorSession {
        val views = EditorViews(
            scene = WorldViewport.detached(camera, VIEW_WIDTH, VIEW_HEIGHT, sources.toList()),
            game = WorldViewport.detached(null, VIEW_WIDTH, VIEW_HEIGHT),
        )
        views.scene.gizmos = handles
        return EditorSession(
            tools = EditorTools(loop.bridge, loop.author),
            tick = { Tick(0) },
            paused = { true },
            spawn = EditorSpawn("Spawn", BlueprintId("skeleton"), 0f, 0f),
            views = views,
        )
    }

    /** Three squares apart from one another: [left] and [right] either side of the middle, [far] above. */
    private fun apart(): Squares = Squares(
        Squares.Square(left, worldX(-SPREAD), worldY(0f), unitsFor(HALF)),
        Squares.Square(right, worldX(SPREAD), worldY(0f), unitsFor(HALF)),
        Squares.Square(far, worldX(0f), worldY(SPREAD), unitsFor(HALF)),
    )

    @Test
    fun `a click selects the entity under it, and a click on nothing selects nothing`() {
        val session = session(apart())
        open(session).use { ui ->
            click(ui, session, empty())
            assertEquals(emptyList(), loop.selection(), "a click on nothing selected something")

            click(ui, session, at(-SPREAD, 0f))
            assertEquals(listOf(left), loop.selection(), "a click on the left square")

            click(ui, session, at(SPREAD, 0f))
            assertEquals(listOf(right), loop.selection(), "a click replaces the selection")

            click(ui, session, empty())
            assertEquals(emptyList(), loop.selection(), "a click on nothing must clear the selection")
        }
    }

    @Test
    fun `Shift-click adds and takes away, and a Shift-click on nothing changes nothing`() {
        val session = session(apart())
        open(session).use { ui ->
            click(ui, session, at(-SPREAD, 0f))
            shiftClick(ui, session, at(SPREAD, 0f))
            assertEquals(setOf(left, right), loop.selection().toSet(), "Shift-click did not add")

            shiftClick(ui, session, empty())
            assertEquals(setOf(left, right), loop.selection().toSet(), "a Shift-click on nothing changed the selection")

            shiftClick(ui, session, at(-SPREAD, 0f))
            assertEquals(listOf(right), loop.selection(), "Shift-click on a selected entity did not take it out")
        }
    }

    @Test
    fun `a drag from empty space selects every entity the box touches, and a box over nothing selects nothing`() {
        val session = session(apart())
        open(session).use { ui ->
            click(ui, session, at(-SPREAD, 0f))

            // A box round the empty corner below-left touches nothing: it replaces the selection with nothing.
            drag(ui, session, at(-2 * SPREAD, -2 * SPREAD), at(-SPREAD - 2 * HALF, -SPREAD))
            assertEquals(emptyList(), loop.selection(), "a box over nothing selected something")

            // From empty space below, across both lower squares, short of the one above.
            drag(ui, session, at(-2 * SPREAD, -SPREAD), at(2 * SPREAD, 0f))
            assertEquals(setOf(left, right), loop.selection().toSet(), "the box did not select what it touched")

            // Held Shift, a box adds.
            ui.keyDown(Key.Shift, Modifiers(Modifiers.SHIFT))
            drag(ui, session, at(-SPREAD, SPREAD + 2 * HALF), at(SPREAD, SPREAD))
            ui.keyUp(Key.Shift)
            assertEquals(setOf(left, right, far), loop.selection().toSet(), "a Shift box did not add")
        }
    }

    @Test
    fun `where entities overlap the front one is picked, and clicking again picks the one behind`() {
        // [left] is reported first and [right] second, over the same spot: [right] is drawn in front.
        val session = session(
            Squares(
                Squares.Square(left, worldX(0f), worldY(0f), unitsFor(HALF)),
                Squares.Square(right, worldX(0f), worldY(0f), unitsFor(HALF)),
            ),
        )
        open(session).use { ui ->
            click(ui, session, at(0f, 0f))
            assertEquals(listOf(right), loop.selection(), "the front entity was not picked first")
            click(ui, session, at(0f, 0f))
            assertEquals(listOf(left), loop.selection(), "a second click did not reach the entity behind")
            click(ui, session, at(0f, 0f))
            assertEquals(listOf(right), loop.selection(), "a third click did not come round to the front again")
        }
    }

    @Test
    fun `an entity reported by a later render system is in front of one reported by an earlier`() {
        // Each system reports one entity over the same spot. The later system draws over the earlier.
        val session = session(
            Squares(Squares.Square(right, worldX(0f), worldY(0f), unitsFor(HALF))),
            Squares(Squares.Square(left, worldX(0f), worldY(0f), unitsFor(HALF))),
        )
        open(session).use { ui ->
            click(ui, session, at(0f, 0f))
            assertEquals(listOf(left), loop.selection(), "the later system's entity is drawn in front, and was not picked first")
        }
    }

    @Test
    fun `a gizmo handle is hit before the entity under it, and the entity is picked beside it`() {
        // A test gizmo on the public API: one handle at the left square's centre.
        val marker = Marker()
        val handles = HandleLayer { GizmoFrame.of(MarkerGizmo.handles(GizmoTarget(left, marker, WorldPoint(worldX(-SPREAD), worldY(0f))))) }
        val session = session(apart(), handles = handles)
        open(session).use { ui ->
            click(ui, session, at(-SPREAD, 0f))
            assertNotNull(handles.pressed, "the press on the handle did not reach it")
            assertEquals(emptyList(), loop.selection(), "a press the handle took also selected the entity under it")

            // On the same square, clear of the handle: the entity's.
            click(ui, session, at(-SPREAD, HALF - 2f))
            assertNull(handles.pressed, "a press clear of the handle was taken by it")
            assertEquals(listOf(left), loop.selection(), "a press beside the handle did not select the entity")
        }
    }

    // --- fixture -------------------------------------------------------------------------

    private fun open(session: EditorSession): UiTest = uiTest { session.window.content() }.also { ui ->
        // The page moves into the gap once the panels have said where they are.
        ui.settle()
        frames(ui, session)
    }

    private fun click(ui: UiTest, session: EditorSession, at: ViewPoint) {
        assertTrue(ui.click(screenOf(ui, at)), "the Scene tab took no click")
        frames(ui, session)
    }

    private fun shiftClick(ui: UiTest, session: EditorSession, at: ViewPoint) {
        ui.keyDown(Key.Shift, Modifiers(Modifiers.SHIFT))
        click(ui, session, at)
        ui.keyUp(Key.Shift)
    }

    private fun drag(ui: UiTest, session: EditorSession, from: ViewPoint, to: ViewPoint) {
        assertTrue(ui.press(screenOf(ui, from)), "the Scene tab took no press")
        ui.dragTo(screenOf(ui, to))
        ui.release()
        frames(ui, session)
    }

    /** Sends, runs and delivers what the window asked for. */
    private fun frames(ui: UiTest, session: EditorSession) {
        repeat(FRAMES) {
            session.frame()
            loop.pump()
            ui.settle()
        }
    }

    /** View point ([dx], [dy]) view pixels from the middle of the view. */
    private fun at(dx: Float, dy: Float): ViewPoint = ViewPoint(VIEW_WIDTH / 2f + dx, VIEW_HEIGHT / 2f + dy)

    /** A point on the view no square covers. */
    private fun empty(): ViewPoint = at(-2 * SPREAD, -SPREAD)

    /** The world x drawn [dx] view pixels right of the middle of the view. */
    private fun worldX(dx: Float): Float = worldOf(at(dx, 0f)).x

    private fun worldY(dy: Float): Float = worldOf(at(0f, dy)).y

    private fun worldOf(view: ViewPoint): ViewPoint = ViewPoint().also { camera.unproject(view.x, view.y, it) }

    /** World units that cover [pixels] view pixels. */
    private fun unitsFor(pixels: Float): Float = pixels / camera.projection.scaleX

    private companion object {
        const val VIEW_WIDTH = 640
        const val VIEW_HEIGHT = 360

        /** How far each square's centre is from the middle of the view, in view pixels. */
        const val SPREAD = 60f

        /** Half a square's side, in view pixels. */
        const val HALF = 16f

        /** Enough for a click to be sent, run and answered, and for the reads it causes to come back. */
        const val FRAMES = 4
    }
}

/** Where the view point [view] is on the screen: `WorldViewport.toView` backwards, through the letterbox. */
internal fun screenOf(ui: UiTest, view: ViewPoint, viewWidth: Int = 640, viewHeight: Int = 360): Offset {
    val box = ui.node(EditorTags.SCENE_VIEW).boundsInRoot
    val width = box.width.toInt().toFloat()
    val height = box.height.toInt().toFloat()
    val scale = minOf(width / viewWidth, height / viewHeight)
    val left = (width - viewWidth * scale) / 2f
    val bottom = (height - viewHeight * scale) / 2f
    return Offset(box.left + left + view.x * scale, box.top + height - (bottom + view.y * scale))
}

/** A component with nothing in it, for a gizmo to be about. */
private class Marker : Component<Marker> {
    override fun type(): ComponentType<Marker> = Marker

    companion object : ComponentType<Marker>()
}

/** A gizmo written only against the public API: one point handle at its target's origin. */
private object MarkerGizmo : Gizmo<Marker> {
    override val component: ComponentType<Marker> = Marker

    override fun GizmoScope<Marker>.build(target: GizmoTarget<Marker>) {
        handle(at = target.origin, shape = HandleShape.Point, constraint = DragConstraint.Across(Plane.XY)) { }
    }
}
