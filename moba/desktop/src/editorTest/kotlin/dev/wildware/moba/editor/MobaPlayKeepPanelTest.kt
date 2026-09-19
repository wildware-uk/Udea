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
import dev.wildware.moba.level.GameUnit
import dev.wildware.moba.level.Team
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.editor.EditorSession
import dev.wildware.udea.editor.EditorTags
import dev.wildware.udea.editor.EditorViews
import dev.wildware.udea.editor.InspectorTags
import dev.wildware.udea.editor.PlayEditTags
import dev.wildware.udea.editor.PlaybackTags
import dev.wildware.udea.render.view.EditorCamera
import dev.wildware.udea.render.view.ViewPoint
import dev.wildware.udea.render.view.WorldViewport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tuning a running `moba` in the editor window and keeping the result (issue #238): the tower's range
 * ring dragged in the Scene tab while the game plays, the change kept from the "Changes during Play"
 * panel or the Inspector's Keep pin, and Stop.
 *
 * Wired as `runEditor` wires it, with the frame loop pumped by hand in place of a render thread, as
 * `TowerRangeGizmoTest` does: a Scene tab over a detached view, one world unit to a view pixel,
 * looking straight at the tower.
 */
class MobaPlayKeepPanelTest {

    private val wiring = MobaAgent.Wiring()
    private val host: GameHost =
        MobaGame.host(RenderMode.Headless, extraModules = wiring.extraModules, level = MobaLaunchLevel.bytes())
    private val session: MobaAgent.Session = MobaAgent.attach(host, RenderMode.Headless, null, wiring, editor = true)

    @AfterTest
    fun close() {
        session.close("test over")
    }

    @Test
    fun `a tower's range dragged during play reaches the game on the next tick, and kept, it is the stopped world's range`() {
        runUntilTowers()
        val tower = hostileTower()
        val place = positionOf(tower)
        // The player stands off the tower's X axis (where the ring's grip is), beyond the range the
        // tower is built with and inside the one the drag gives it.
        asAnAgent("editor.move", "id" to "${session.player.raw}", "x" to "${place.x}", "y" to "${place.y + PLAYER_DISTANCE}")
        val (camera, editor) = editorOn(tower)

        uiTest { editor.window.content() }.use { ui ->
            frames(ui, editor)
            click(ui, editor, PlaybackTags.PLAY)
            asAnAgent("editor.select", "entities" to "${tower.raw}")
            frames(ui, editor)
            assertEquals(NetId.NONE.raw, targetOf(tower), "the tower is shooting before its range reaches the player")

            // The drag, one frame of the running game per pointer move, watching what the game does.
            val seen = ArrayList<Seen>()
            val rim = ViewPoint().also { camera.project(place.x + rangeOf(tower), place.y, 0f, it) }
            assertTrue(ui.press(screenOf(ui, rim)), "the Scene tab took no press")
            watch(ui, editor, tower, seen)
            for (step in 1..DRAG_STEPS) {
                ui.dragTo(screenOf(ui, ViewPoint(rim.x + DRAG_PIXELS * step / DRAG_STEPS, rim.y)))
                watch(ui, editor, tower, seen)
            }
            ui.release()
            repeat(FRAMES) { watch(ui, editor, tower, seen) }

            val reached = seen.firstOrNull { it.range >= PLAYER_DISTANCE } ?: error("the drag never took the range past the player: $seen")
            val shotAt = seen.firstOrNull { it.target == session.player.raw } ?: error("the tower never turned on the player: $seen")
            assertTrue(shotAt.tick <= reached.tick + 1, "the game saw the new range at tick ${shotAt.tick}, not the tick after ${reached.tick}: $seen")
            assertTrue(seen.none { it.target == session.player.raw && it.range < PLAYER_DISTANCE }, "the tower shot at the player out of its range: $seen")
            assertTrue(!host.time.paused, "the drag paused the game")
            val dragged = rangeOf(tower)

            // Kept from the panel, by its Keep toggle.
            val edit = playEdits().single()
            assertEquals("editor.commit_edit", edit.getValue("tool").jsonPrimitive.content, "the drag is not one play edit: $edit")
            val editId = edit.getValue("editId").jsonPrimitive.long
            click(ui, editor, PlayEditTags.keep(editId))
            assertTrue(playEdits().single().getValue("kept").jsonPrimitive.boolean, "the panel's toggle did not keep the drag")

            click(ui, editor, PlaybackTags.STOP)

            assertTrue(host.time.paused, "Stop left the game running")
            assertTrue(abs(rangeOf(tower) - dragged) < TOLERANCE, "the stopped world's range is ${rangeOf(tower)}, and the drag left $dragged")
            assertEquals(listOf("editor.commit_edit", "editor.move"), historyAsAnAgent(), "the kept drag is not an undo entry after Stop")
            click(ui, editor, EditorTags.UNDO)
            assertEquals(LaneGeometry.TOWER_RANGE, rangeOf(tower), "undo after Stop did not put the built range back")
        }
    }

    @Test
    fun `the inspector pins a field changed during play, and its pin keeps and unkeeps that change`() {
        runUntilTowers()
        val tower = hostileTower()
        val (_, editor) = editorOn(tower)

        uiTest { editor.window.content() }.use { ui ->
            frames(ui, editor)
            click(ui, editor, PlaybackTags.PLAY)
            asAnAgent("editor.select", "entities" to "${tower.raw}")
            frames(ui, editor)
            ui.assertDoesNotExist(PlayEditTags.pin(RANGE))

            asAnAgent("editor.set_field", "id" to "${tower.raw}", "component" to "Tower", "field" to "attackRange", "value" to "210")
            frames(ui, editor)
            ui.assertExists(PlayEditTags.pin(RANGE))
            // Only the field the play changed carries a pin.
            ui.assertExists(InspectorTags.field("Tower.team"))
            ui.assertDoesNotExist(PlayEditTags.pin("Tower.team"))

            click(ui, editor, PlayEditTags.pin(RANGE))
            assertTrue(playEdits().single().getValue("kept").jsonPrimitive.boolean, "the pin did not keep the change")
            click(ui, editor, PlayEditTags.pin(RANGE))
            assertTrue(!playEdits().single().getValue("kept").jsonPrimitive.boolean, "the pin did not unkeep the change")
            click(ui, editor, PlayEditTags.pin(RANGE))

            click(ui, editor, PlaybackTags.STOP)

            assertEquals(210f, rangeOf(tower), "the pinned change did not survive Stop")
        }
    }

    @Test
    fun `the panel says why an edit to a unit spawned during play cannot be kept, and offers no toggle for it`() {
        runUntilTowers()
        val (_, editor) = editorOn(hostileTower())

        uiTest { editor.window.content() }.use { ui ->
            frames(ui, editor)
            click(ui, editor, PlaybackTags.PLAY)
            click(ui, editor, EditorTags.SPAWN)

            val spawn = playEdits().single()
            ui.assertDoesNotExist(PlayEditTags.keep(spawn.getValue("editId").jsonPrimitive.long))
            assertTrue("spawned during Play" in ui.text(PlayEditTags.LIST), "the panel shows \"${ui.text(PlayEditTags.LIST)}\"")
        }
    }

    // --- fixture ----------------------------------------------------------------------------------

    /** What the game was doing after one frame of the drag. */
    private data class Seen(val tick: Long, val range: Float, val target: Int)

    private fun watch(ui: UiTest, editor: EditorSession, tower: NetId, seen: MutableList<Seen>) {
        session.loop.pump(1f / 60f)
        editor.frame()
        ui.settle()
        seen += Seen(host.ctx.clock.tick.value, rangeOf(tower), targetOf(tower))
    }

    /** The editor over a Scene tab looking straight at [tower], one world unit to a view pixel. */
    private fun editorOn(tower: NetId): Pair<EditorCamera, EditorSession> {
        val camera = EditorCamera(worldWidth = VIEW_WIDTH.toFloat(), worldHeight = VIEW_HEIGHT.toFloat())
        val place = positionOf(tower)
        camera.camera2D.position.x = place.x
        camera.camera2D.position.y = place.y
        camera.fit(VIEW_WIDTH, VIEW_HEIGHT)
        val views = EditorViews(
            scene = WorldViewport.detached(camera, VIEW_WIDTH, VIEW_HEIGHT),
            game = WorldViewport.detached(null, VIEW_WIDTH, VIEW_HEIGHT),
        )
        return camera to MobaEditor.session(host, session, views)
    }

    /** A tower on the side the player fights, so it shoots the player once the player is in range. */
    private fun hostileTower(): NetId {
        val team = with(host.world) { entityOf(session.player)[GameUnit].team }
        return towers().first { with(host.world) { Team.isHostile(entityOf(it)[Tower].team, team) } }
    }

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

    private fun targetOf(id: NetId): Int = with(host.world) { entityOf(id)[Tower].targetRaw }

    private fun entityOf(id: NetId) = checkNotNull(host.ctx[CoreModule.NET_IDS].resolveOrNull(id)) { "$id is not in the world" }

    /** Where view pixel [view] is on the window, as `TowerRangeGizmoTest` finds it. */
    private fun screenOf(ui: UiTest, view: ViewPoint): Offset {
        val box = ui.node(EditorTags.SCENE_VIEW).boundsInRoot
        val width = box.width.toInt().toFloat()
        val height = box.height.toInt().toFloat()
        val scale = minOf(width / VIEW_WIDTH, height / VIEW_HEIGHT)
        val left = (width - VIEW_WIDTH * scale) / 2f
        val bottom = (height - VIEW_HEIGHT * scale) / 2f
        return Offset(box.left + left + view.x * scale, box.top + height - (bottom + view.y * scale))
    }

    private fun click(ui: UiTest, editor: EditorSession, tag: String) {
        assertTrue(ui.click(tag), "$tag took no click:\n${ui.dump()}")
        frames(ui, editor)
    }

    private fun frames(ui: UiTest, editor: EditorSession) {
        repeat(FRAMES) {
            session.loop.pump(1f / 60f)
            editor.frame()
            ui.settle()
        }
    }

    private fun playEdits(): List<JsonObject> =
        Json.parseToJsonElement(asAnAgent("editor.play_edits")).jsonObject.getValue("edits").jsonArray.map { it.jsonObject }

    /** The tools of the editor author's history, newest first, as an agent reads them. */
    private fun historyAsAnAgent(): List<String> =
        Json.parseToJsonElement(asAnAgent("editor.history", "limit" to "20")).jsonObject.getValue("edits").jsonArray
            .map { it.jsonObject.getValue("tool").jsonPrimitive.content }

    /** [tool] as an outside agent calls it, under the editor's author. */
    private fun asAnAgent(tool: String, vararg args: Pair<String, String>): String {
        val command = AgentCommand(tool, args.toMap(), session = wiring.sessions.intern(MobaEditor.AUTHOR))
        wiring.bridge.submit(command)
        var pumps = 0
        while (wiring.bridge.completedCommandId() < command.id) {
            check(pumps++ < MAX_PUMPS) { "$tool did not complete in $MAX_PUMPS frames" }
            session.loop.pump(1f / 60f)
        }
        val answer = wiring.bridge.commandResults().single { it.id == command.id }.result
        check(answer is AgentResult.Ok) { "$tool failed: $answer" }
        return answer.json
    }

    private companion object {
        const val RANGE = "Tower.attackRange"
        const val VIEW_WIDTH = 640
        const val VIEW_HEIGHT = 360

        /** How far from the tower the player stands: past the built range, inside the dragged one. */
        const val PLAYER_DISTANCE = 200f

        /** How far the grip is dragged out, in view pixels: one world unit each. */
        const val DRAG_PIXELS = 80f
        const val DRAG_STEPS = 8
        const val FRAMES = 6
        const val TOWER_BUDGET = 60
        const val MAX_PUMPS = 10
        const val TOLERANCE = 1e-3f
    }
}
