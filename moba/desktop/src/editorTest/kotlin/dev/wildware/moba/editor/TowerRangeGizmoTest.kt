package dev.wildware.moba.editor

import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.moba.MobaGame
import dev.wildware.moba.Position
import dev.wildware.moba.agent.MobaAgent
import dev.wildware.moba.entry.MobaLaunchLevel
import dev.wildware.moba.lane.LaneGeometry
import dev.wildware.moba.lane.Tower
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.editor.EditorSession
import dev.wildware.udea.editor.EditorTags
import dev.wildware.udea.editor.EditorViews
import dev.wildware.udea.editor.gizmo.Axis
import dev.wildware.udea.editor.gizmo.Drag
import dev.wildware.udea.editor.gizmo.DragConstraint
import dev.wildware.udea.editor.gizmo.GizmoTarget
import dev.wildware.udea.editor.gizmo.HandleShape
import dev.wildware.udea.editor.gizmo.Mark
import dev.wildware.udea.editor.gizmo.Snap
import dev.wildware.udea.editor.gizmo.WorldPoint
import dev.wildware.udea.editor.gizmo.handles
import dev.wildware.udea.editor.gizmo.marks
import dev.wildware.udea.render.view.EditorCamera
import dev.wildware.udea.render.view.ViewPoint
import dev.wildware.udea.render.view.WorldViewport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tower's range ring (issue #236): the gizmo `moba` writes by hand against the public gizmo API,
 * read as values, read as source, and dragged through the editor window on a real tower in a real
 * `moba` world.
 */
class TowerRangeGizmoTest {

    private val wiring = MobaAgent.Wiring()
    private val host: GameHost =
        MobaGame.host(RenderMode.Headless, extraModules = wiring.extraModules, level = MobaLaunchLevel.bytes())
    private val session: MobaAgent.Session = MobaAgent.attach(host, RenderMode.Headless, null, wiring, editor = true)

    @AfterTest
    fun close() {
        session.close("test over")
    }

    // --- the gizmo, as values -------------------------------------------------------------------

    @Test
    fun `the ring is marked at the tower's range, and its grip on the rim drags the range along X`() {
        val tower = NetId.of(index = 30, generation = 0)
        val target = GizmoTarget(tower, Tower(attackRange = 100f), WorldPoint(10f, 20f))
        val grip = TowerRangeGizmo.handles(target).single()

        assertEquals(listOf(Mark(WorldPoint(10f, 20f), HandleShape.Circle(100f))), TowerRangeGizmo.marks(target))
        assertEquals(WorldPoint(110f, 20f), grip.at, "the grip is not on the rim along X")
        assertEquals(HandleShape.Line(WorldPoint(10f, 20f)), grip.shape, "the grip has no spoke back to the tower")
        assertEquals(DragConstraint.Along(Axis.X), grip.constraint)
        // Dragged 25 further out: the range is 25 longer, and it snaps to the grid.
        val written = grip.drag(Drag(grip.at, WorldPoint(135f, 20f))).single()
        assertEquals("attackRange", written.field.value)
        assertEquals(125f, written.value)
        assertEquals(Snap.Grid, written.snap)
        // Grabbed 40 beyond the rim and dragged onto the tower: 100 less 140 is never a negative range.
        assertEquals(0f, grip.drag(Drag(WorldPoint(150f, 20f), WorldPoint(10f, 20f))).single().value)
    }

    @Test
    fun `the gizmo imports nothing but the public gizmo API, the game's own tower and Fleks`() {
        val imports = importsOf(Path.of(SOURCE))
        assertTrue(imports.isNotEmpty(), "read no imports from $SOURCE, so this proves nothing")
        val outside = imports.filterNot { name -> ALLOWED.any { name.startsWith(it) } }
        assertEquals(emptyList(), outside, "TowerRangeGizmo reaches past the public gizmo API")
    }

    @Test
    fun `the import scan reads import lines, not a comment that mentions one`() {
        val file = Files.createTempFile("gizmo", ".kt")
        try {
            Files.writeString(
                file,
                """
                package x

                import dev.wildware.udea.editor.gizmo.Gizmo
                import dev.wildware.udea.render.view.GizmoCanvas

                // import dev.wildware.udea.editor.EditorSession is not an import
                """.trimIndent(),
            )
            assertEquals(listOf("dev.wildware.udea.editor.gizmo.Gizmo", "dev.wildware.udea.render.view.GizmoCanvas"), importsOf(file))
        } finally {
            Files.delete(file)
        }
    }

    // --- dragged, in the editor -------------------------------------------------------------------

    @Test
    fun `dragging a selected tower's rim out in the Scene tab lengthens its range, as one undo entry`() {
        runUntilTowers()
        val tower = towers().first()
        // One world unit to a view pixel, looking straight at the tower, so its whole ring is in view.
        val camera = EditorCamera(worldWidth = VIEW_WIDTH.toFloat(), worldHeight = VIEW_HEIGHT.toFloat())
        val place = positionOf(tower)
        camera.camera2D.position.x = place.x
        camera.camera2D.position.y = place.y
        camera.fit(VIEW_WIDTH, VIEW_HEIGHT)
        val views = EditorViews(
            scene = WorldViewport.detached(camera, VIEW_WIDTH, VIEW_HEIGHT),
            game = WorldViewport.detached(null, VIEW_WIDTH, VIEW_HEIGHT),
        )
        val editor = MobaEditor.session(host, session, views)

        uiTest { editor.window.content() }.use { ui ->
            frames(ui, editor)
            asAnAgent("editor.select", "entities" to "${tower.raw}")
            frames(ui, editor)
            val before = rangeOf(tower)
            assertEquals(LaneGeometry.TOWER_RANGE, before, "a tower is not built with the constant range")
            val rim = ViewPoint().also { camera.project(place.x + before, place.y, 0f, it) }

            assertTrue(ui.press(screenOf(ui, rim)), "the Scene tab took no press")
            for (step in 1..DRAG_STEPS) ui.dragTo(screenOf(ui, ViewPoint(rim.x + DRAG_PIXELS * step / DRAG_STEPS, rim.y)))
            ui.release()
            frames(ui, editor)

            val expected = before + DRAG_PIXELS / camera.projection.scaleX
            assertTrue(abs(expected - rangeOf(tower)) < TOLERANCE, "the range is ${rangeOf(tower)}, and the drag made it $expected")
            assertEquals(listOf("editor.commit_edit"), historyAsAnAgent(), "one drag of the ring is not one undo entry")
        }
    }

    // --- fixture ----------------------------------------------------------------------------------

    /** Every name imported by [file], read off its `import` lines alone. */
    private fun importsOf(file: Path): List<String> =
        Files.readAllLines(file).map { it.trim() }.filter { it.startsWith("import ") }.map { it.removePrefix("import ").trim() }

    private fun runUntilTowers() {
        repeat(TOWER_BUDGET) {
            if (towers().isNotEmpty()) return
            session.loop.pump(1f / 60f)
        }
        check(towers().isNotEmpty()) { "no tower was placed within $TOWER_BUDGET frames" }
    }

    private fun towers(): List<NetId> {
        val netIds = host.ctx[CoreModule.NET_IDS]
        val found = ArrayList<NetId>()
        with(host.world) { family { all(Tower, Position) }.forEach { found += netIds.netIdOf(it) } }
        return found
    }

    private fun positionOf(id: NetId): Position = with(host.world) { entityOf(id)[Position] }

    private fun rangeOf(id: NetId): Float = with(host.world) { entityOf(id)[Tower].attackRange }

    private fun entityOf(id: NetId) = checkNotNull(host.ctx[CoreModule.NET_IDS].resolveOrNull(id)) { "$id is not in the world" }

    /**
     * Where view pixel [view] is on the window: the Scene tab's box, less the letterbox the view is
     * fitted into, with y turned from up to down.
     */
    private fun screenOf(ui: UiTest, view: ViewPoint): Offset {
        val box = ui.node(EditorTags.SCENE_VIEW).boundsInRoot
        val width = box.width.toInt().toFloat()
        val height = box.height.toInt().toFloat()
        val scale = minOf(width / VIEW_WIDTH, height / VIEW_HEIGHT)
        val left = (width - VIEW_WIDTH * scale) / 2f
        val bottom = (height - VIEW_HEIGHT * scale) / 2f
        return Offset(box.left + left + view.x * scale, box.top + height - (bottom + view.y * scale))
    }

    /** A few frames in the order `runWithGl` runs them: pump the loop, then the editor's frame. */
    private fun frames(ui: UiTest, editor: EditorSession) {
        repeat(FRAMES) {
            session.loop.pump(1f / 60f)
            editor.frame()
            ui.settle()
        }
    }

    /** [tool] as an outside agent calls it, under the editor's author. */
    private fun asAnAgent(tool: String, vararg args: Pair<String, String>): String {
        val command = AgentCommand(tool, args.toMap(), session = wiring.sessions.intern(MobaEditor.AUTHOR))
        wiring.bridge.submit(command)
        session.loop.pump(1f / 60f)
        val answer = wiring.bridge.commandResults().single { it.id == command.id }.result
        check(answer is AgentResult.Ok) { "$tool failed: $answer" }
        return answer.json
    }

    /** The tools of the editor author's history, newest first, as an agent reads them. */
    private fun historyAsAnAgent(): List<String> =
        Json.parseToJsonElement(asAnAgent("editor.history", "limit" to "20")).jsonObject.getValue("edits").jsonArray
            .map { it.jsonObject.getValue("tool").jsonPrimitive.content }

    private companion object {
        /** The gizmo's source, from this project's directory, where Gradle runs its tests. */
        const val SOURCE = "src/editor/kotlin/dev/wildware/moba/editor/TowerRangeGizmo.kt"

        /** What the gizmo may import: the public gizmo API, the component it edits, and Fleks' type. */
        val ALLOWED = listOf("dev.wildware.udea.editor.gizmo.", "dev.wildware.moba.lane.Tower", "com.github.quillraven.fleks.ComponentType")

        const val VIEW_WIDTH = 640
        const val VIEW_HEIGHT = 360
        const val DRAG_PIXELS = 30f
        const val DRAG_STEPS = 4
        const val FRAMES = 6
        const val TOWER_BUDGET = 60
        const val TOLERANCE = 1e-3f
    }
}
