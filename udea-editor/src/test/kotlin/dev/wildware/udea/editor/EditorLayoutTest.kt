package dev.wildware.udea.editor

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.activity.AgentSessions
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.blueprint.BlueprintId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The world view's own rectangle (issue #234, reopened): the Scene and Game tabs sit in the gap the
 * docked panels leave, and no panel and no divider between panels lies over them.
 *
 * The panels and the dividers are found by the tags ComposeGL's docking puts on them
 * (`debugwindow:<id>` on a window's frame, `debugwindow:divider:<n>` on a divider), not by anything the
 * editor computes: the assertion is about where the toolkit really drew its panels, so a wrong guess
 * in the editor about where they are fails here rather than agreeing with itself.
 *
 * `GlEditorLayoutTest` holds the same rule on a real backend, in pixels.
 */
class EditorLayoutTest {

    private val views = EditorViews.detached(VIEW_WIDTH, VIEW_HEIGHT)

    private val session = EditorSession(
        tools = EditorTools(AgentBridge(), AgentSessions().intern("editor")),
        tick = { Tick(0) },
        paused = { true },
        spawn = EditorSpawn("Spawn", BlueprintId("skeleton"), 0f, 0f),
        views = views,
    )

    private fun open(): UiTest = uiTest(Size(WIDTH, HEIGHT)) { session.window.content() }.also { it.settle() }

    @Test
    fun `the Scene tab fills the gap between the docked panels and lies under none of them`() {
        open().use { ui -> assertBetweenPanels(ui, EditorTags.SCENE_VIEW) }
    }

    @Test
    fun `the Game tab fills the same gap`() {
        open().use { ui ->
            ui.click(EditorTags.GAME_TAB)
            ui.settle()
            assertBetweenPanels(ui, EditorTags.GAME_VIEW)
        }
    }

    @Test
    fun `dragging the divider beside the view moves the view's edge with it`() {
        open().use { ui ->
            val before = ui.node(EditorTags.SCENE_VIEW).boundsInRoot
            val divider = dividers(ui).single { abs(it.right - before.left) < 1f }
            val from = divider.centre
            assertTrue(ui.press(from), "the divider took no press")
            ui.dragTo(Offset(from.x + DRAG, from.y))
            ui.release()
            ui.settle()

            val after = ui.node(EditorTags.SCENE_VIEW).boundsInRoot
            assertEquals(before.left + DRAG, after.left, 1f, "the view's left edge did not follow the divider")
            assertEquals(before.right, after.right, 1f, "the view's right edge moved with a divider on its left")
            assertBetweenPanels(ui, EditorTags.SCENE_VIEW)
        }
    }

    @Test
    fun `a panel floated off its dock gives its room to the view, and docked along the bottom takes room from below`() {
        open().use { ui ->
            val before = ui.node(EditorTags.SCENE_VIEW).boundsInRoot
            val create = ui.node("debugwindow:${EditorTags.CREATE_PANEL}").boundsInRoot
            // A press on the panel's empty lower part puts focus on its frame, which hears the keys
            // that float and dock it.
            assertTrue(ui.click(Offset(create.centre.x, create.bottom - EMPTY)), "the Create panel took no press")
            assertTrue(ui.key(Key.F, Modifiers.Primary + Modifiers.Alt), "the Create panel did not float")
            ui.settle()

            val floated = ui.node(EditorTags.SCENE_VIEW).boundsInRoot
            assertEquals(create.left, floated.left, 1f, "the view did not take the room the floated panel left")
            assertEquals(before.right, floated.right, 1f, "floating the Create panel moved the view's right edge")

            assertTrue(ui.key(Key.Down, Modifiers.Primary + Modifiers.Alt), "the Create panel did not dock along the bottom")
            ui.settle()
            val docked = ui.node(EditorTags.SCENE_VIEW).boundsInRoot
            val bottom = ui.node("debugwindow:${EditorTags.CREATE_PANEL}").boundsInRoot
            val context = "view $docked, Create panel $bottom"
            assertTrue(bottom.top > docked.top && bottom.width > bottom.height, "the Create panel is not along the bottom: $context")
            assertEquals(bottom.top - DIVIDER, docked.bottom, 1f, "the view does not end at the divider above the Create panel: $context")
            for (other in PANELS.map { ui.node("debugwindow:$it").boundsInRoot } + dividers(ui)) {
                assertFalse(docked.overlaps(other), "the view lies under $other: $context")
            }
        }
    }

    @Test
    fun `a press on a panel beside the Scene tab never moves its camera, and one just inside the view does`() {
        open().use { ui ->
            val view = ui.node(EditorTags.SCENE_VIEW).boundsInRoot
            val camera = views.camera.camera2D
            val onPanel = Offset(view.left - DIVIDER - EDGE, view.centre.y)
            val inView = Offset(view.left + EDGE, view.centre.y)

            val x = camera.position.x
            ui.press(onPanel)
            ui.dragTo(Offset(onPanel.x - DRAG, onPanel.y))
            ui.release()
            assertEquals(x, camera.position.x, "a drag on the Create panel moved the Scene tab's camera")

            assertTrue(ui.press(inView), "a press just inside the Scene tab's left edge was not taken")
            ui.dragTo(Offset(inView.x + DRAG, inView.y))
            ui.release()
            assertTrue(abs(camera.position.x - x) > 0.01f, "a drag just inside the Scene tab did not move its camera")
        }
    }

    @Test
    fun `a click just inside the Game tab goes to the game, and one on the panel beside it is the panel's`() {
        open().use { ui ->
            ui.click(EditorTags.GAME_TAB)
            ui.settle()
            val view = ui.node(EditorTags.GAME_VIEW).boundsInRoot
            assertFalse(ui.click(Offset(view.right - EDGE, view.centre.y)), "the window took a click just inside the Game tab")
            assertTrue(ui.click(Offset(view.right + DIVIDER + EDGE, view.centre.y)), "a click on the panel beside the Game tab went through to the game")
        }
    }

    // --- fixture -------------------------------------------------------------------------

    /**
     * The view tagged [tag] overlaps no docked panel and no divider, and reaches each panel beside it:
     * its left edge is the right edge of the divider on its left, and so on round. Top and bottom are
     * the edges of the area the panels are docked in, which the panels span.
     */
    private fun assertBetweenPanels(ui: UiTest, tag: String) {
        val view = ui.node(tag).boundsInRoot
        val panels = PANELS.map { ui.node("debugwindow:$it").boundsInRoot }
        val dividers = dividers(ui)
        val context = "view $view, panels $panels, dividers $dividers"
        assertTrue(view.width > 0f && view.height > 0f, "the view has no area: $context")
        for (panel in panels + dividers) assertFalse(view.overlaps(panel), "the view lies under $panel: $context")

        val left = dividers.filter { it.right <= view.left + 1f }.maxOf { it.right }
        val right = dividers.filter { it.left >= view.right - 1f }.minOf { it.left }
        assertEquals(left, view.left, 1f, "the view does not start at the divider on its left: $context")
        assertEquals(right, view.right, 1f, "the view does not end at the divider on its right: $context")
        val top = panels.minOf { it.top }
        val bottom = panels.maxOf { it.bottom }
        assertEquals(top, view.top, 1f, "the view does not start where the panels do: $context")
        assertEquals(bottom, view.bottom, 1f, "the view does not end where the panels do: $context")
    }

    /** Where ComposeGL drew every divider between docked panes. */
    private fun dividers(ui: UiTest): List<Rect> {
        val found = ArrayList<Rect>()
        ui.root.forEach { node -> if (node.testTag?.startsWith("debugwindow:divider:") == true) found += node.boundsInRoot }
        return found
    }

    private companion object {
        const val WIDTH = 1280f
        const val HEIGHT = 720f
        const val VIEW_WIDTH = 640
        const val VIEW_HEIGHT = 360

        /** The docked panels' ids. */
        val PANELS = listOf(EditorTags.CREATE_PANEL, EditorTags.ASSET_PANEL, EditorTags.HISTORY_PANEL)

        /** ComposeGL's divider between two docked panes, in design units. */
        const val DIVIDER = 6f

        /** How far inside or outside an edge a press lands, in design units. */
        const val EDGE = 3f

        /** How far each drag goes, in design units. */
        const val DRAG = 40f

        /** How far above a panel's bottom edge a press lands on nothing but the panel's frame. */
        const val EMPTY = 20f
    }
}
