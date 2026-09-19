package dev.wildware.moba.editor

import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.moba.MobaGame
import dev.wildware.moba.Position
import dev.wildware.moba.agent.MobaAgent
import dev.wildware.moba.entry.MobaLaunchLevel
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.editor.EditorSession
import dev.wildware.udea.editor.EditorTags
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The editor window's buttons against the real `EditorToolset`, in a real `moba` world (issue #194).
 *
 * `EditorSessionTest` in `udea-editor` answers the window's calls by hand. This is the other half:
 * the same window wired exactly as `runEditor` wires it - `MobaAgent.attach` with the editor tools,
 * `MobaEditor.session` over it - with the frame loop pumped by hand in place of a render thread, and
 * a click on the button. What it proves is the issue's third criterion: the click is an `editor.*`
 * call, it changes the world, and the change is in the undo history of the author the window files
 * under - the history an agent sending `session=editor` reads, and not only the panel's copy of it.
 */
class MobaEditorTest {

    private val wiring = MobaAgent.Wiring()
    private val host: GameHost =
        MobaGame.host(RenderMode.Headless, extraModules = wiring.extraModules, level = MobaLaunchLevel.bytes())
    private val session: MobaAgent.Session = MobaAgent.attach(host, RenderMode.Headless, null, wiring, editor = true)

    @AfterTest
    fun close() {
        session.close("test over")
    }

    @Test
    fun `the editor opens paused, and the frames it pumps do not tick the world`() {
        val tickBefore = host.ctx.clock.tick
        val editor = MobaEditor.session(host, session, viewport = {})

        assertTrue(host.time.paused, "the editor opened on a running world")
        uiTest { editor.window.content() }.use { ui ->
            frames(ui, editor)
            assertEquals(tickBefore, host.ctx.clock.tick, "the world ticked under the editor")
            assertTrue(ui.text(EditorTags.STATUS).startsWith("Paused"), "the status line says \"${ui.text(EditorTags.STATUS)}\"")
        }
    }

    @Test
    fun `the spawn button adds a unit beside the player, paused, in the editor author's undo history`() {
        val editor = MobaEditor.session(host, session, viewport = {})
        val playerX = positionOf(session.player).first
        val tickBefore = host.ctx.clock.tick

        uiTest { editor.window.content() }.use { ui ->
            frames(ui, editor)
            assertTrue(ui.click(EditorTags.SPAWN), "the spawn button took no click:\n${ui.dump()}")
            frames(ui, editor)

            // What an agent calling `editor.history` with `session=editor` is told.
            val edits = historyAsAnAgent()
            assertEquals(listOf("editor.spawn"), edits.map { it.first }, "the edits filed under `editor` were $edits")
            val spawn = edits.single()
            val spawned = NetId.ofRaw(spawn.second)
            val (x, _) = assertNotNull(positionOrNull(spawned), "the history names $spawned, and it is not in the world")
            assertEquals(playerX + MobaEditor.SPAWN_OFFSET_X, x, "the skeleton is not beside the player")

            assertEquals(tickBefore, host.ctx.clock.tick, "the editor must stay paused: the world ticked")
            val panel = ui.text(EditorTags.HISTORY)
            assertTrue("editor.spawn" in panel && "#${spawn.second}" in panel, "the history panel shows \"$panel\"")

            // And the button's edit is undoable from the same window: the unit goes, the history empties.
            assertTrue(ui.click(EditorTags.UNDO), "the undo button took no click:\n${ui.dump()}")
            frames(ui, editor)
            assertNull(positionOrNull(spawned), "undo left $spawned in the world")
            assertEquals(emptyList(), historyAsAnAgent())
            assertTrue("Nothing to undo" in ui.text(EditorTags.HISTORY), "the panel shows \"${ui.text(EditorTags.HISTORY)}\"")
        }
    }

    /** A few frames of the order `runWithGl` runs them in: pump the loop, then the editor's frame. */
    private fun frames(ui: UiTest, editor: EditorSession) {
        repeat(FRAMES) {
            session.loop.pump(1f / 60f)
            editor.frame()
            ui.settle()
        }
    }

    /**
     * `editor.history` as an outside agent calls it: its own command, under the session label
     * `editor`, interned through the same table the HTTP surface uses.
     */
    private fun historyAsAnAgent(): List<Pair<String, Int>> {
        val command = AgentCommand("editor.history", mapOf("limit" to "20"), session = wiring.sessions.intern(MobaEditor.AUTHOR))
        wiring.bridge.submit(command)
        session.loop.pump(1f / 60f)
        val answer = wiring.bridge.commandResults().single { it.id == command.id }.result
        check(answer is AgentResult.Ok) { "editor.history failed: $answer" }
        return Json.parseToJsonElement(answer.json).jsonObject.getValue("edits").jsonArray.map {
            val edit = it.jsonObject
            edit.getValue("tool").jsonPrimitive.content to edit.getValue("id").jsonPrimitive.int
        }
    }

    private fun positionOf(id: NetId): Pair<Float, Float> = checkNotNull(positionOrNull(id)) { "$id is not in the world" }

    private fun positionOrNull(id: NetId): Pair<Float, Float>? {
        val entity = host.ctx[CoreModule.NET_IDS].resolveOrNull(id) ?: return null
        return with(host.world) { entity.getOrNull(Position)?.let { it.x to it.y } }
    }

    private companion object {
        /** Enough for a click to be sent, run and answered, and for the history read it causes to come back. */
        const val FRAMES = 6
    }
}
