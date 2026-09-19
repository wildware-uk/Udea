package dev.wildware.udea.editor

import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.activity.AgentSessions
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.blueprint.BlueprintId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor window against the tool surface's own seam, [AgentBridge], with no game behind it.
 *
 * What the window promises is that it is a screen over the tools (issue #190): a button is a tool
 * call filed under the editor's author, and what the panels show is what the tools answered. So
 * these tests play the simulation's half by hand - drain the queue, answer each command - and assert
 * on the commands the window sent and on what it drew from the answers. The real `EditorToolset`
 * answering the real calls is `MobaEditorTest`'s job, in the game that wires it.
 */
class EditorSessionTest {

    private val bridge = AgentBridge()
    private val sessions = AgentSessions()
    private val author = sessions.intern(EDITOR)
    private var tick = Tick(0)
    private var paused = true

    private val session = EditorSession(
        tools = EditorTools(bridge, author),
        tick = { tick },
        paused = { paused },
        spawn = EditorSpawn("Spawn skeleton at centre", BlueprintId("skeleton"), SPAWN_X, SPAWN_Y),
        views = EditorViews.detached(),
    )

    private fun open(): UiTest = uiTest { session.window.content() }

    /** What the simulation would have been sent since the last call. */
    private fun drain(): List<AgentCommand> = ArrayList<AgentCommand>().also { bridge.drain(it) }

    /**
     * What the simulation would have been sent since the last call, less the selection's own reads
     * (issue #235), which `ScenePickingTest` answers, and the play edits' (issue #238), which
     * `MobaPlayKeepPanelTest` does: this suite is about the History panel.
     */
    private fun drainHistoryAndEdits(): List<AgentCommand> =
        drain().filter { it.name != "editor.selection" && it.name != "editor.play_edits" }

    @Test
    fun `the spawn button calls editor spawn at the configured point, filed under the editor's author`() {
        open().use { ui ->
            drain()
            assertTrue(ui.click(EditorTags.SPAWN), "the spawn button took no click:\n${ui.dump()}")

            val sent = drain().single { it.name == "editor.spawn" }
            assertEquals(mapOf("blueprint" to "skeleton", "x" to "$SPAWN_X", "y" to "$SPAWN_Y"), sent.args)
            assertEquals(author, sent.session, "the edit must land in the editor's own undo history")
        }
    }

    @Test
    fun `an edit's answer refreshes the history panel from editor history, for the editor's author`() {
        open().use { ui ->
            drain()
            ui.click(EditorTags.SPAWN)
            val spawn = drainHistoryAndEdits().single()
            bridge.complete(spawn.id, AgentResult.Ok("""{"id":42}"""))
            session.frame()

            val history = drainHistoryAndEdits().single()
            assertEquals("editor.history", history.name)
            assertEquals(author, history.session, "a history read under any other author lists someone else's edits")
            bridge.complete(
                history.id,
                AgentResult.Ok("""{"author":"editor","size":1,"edits":[{"sequence":7,"tool":"editor.spawn","id":42}]}"""),
            )
            session.frame()
            ui.settle()

            val shown = ui.text(EditorTags.HISTORY)
            assertTrue("editor.spawn" in shown && "#42" in shown, "the history panel shows \"$shown\"")
        }
    }

    @Test
    fun `an edit another caller files under the editor's author shows in the panel too`() {
        // The editor is a screen over the tool surface: an agent sending session=editor is the same
        // author, so its edits are the window's history as much as a click's are.
        open().use { ui ->
            drain()
            session.frame()
            val agentsEdit = AgentCommand("editor.move", mapOf("id" to "3"), session = author)
            bridge.submit(agentsEdit)
            drain()
            bridge.complete(agentsEdit.id, AgentResult.Ok("{}"))
            session.frame()

            val history = drainHistoryAndEdits().single()
            assertEquals("editor.history", history.name)
            bridge.complete(
                history.id,
                AgentResult.Ok("""{"author":"editor","size":1,"edits":[{"sequence":1,"tool":"editor.move","id":3}]}"""),
            )
            session.frame()
            ui.settle()
            assertTrue("editor.move" in ui.text(EditorTags.HISTORY))
        }
    }

    @Test
    fun `its own reads do not trigger more, so an idle window sends nothing`() {
        open().use {
            drain()
            session.frame()
            // The first frame reads the History panel's list, the selection and the play edits, once each.
            val first = drain()
            assertEquals(listOf("editor.history", "editor.selection", "editor.play_edits"), first.map { it.name })
            bridge.complete(first[0].id, AgentResult.Ok("""{"author":"editor","size":0,"edits":[]}"""))
            bridge.complete(first[1].id, AgentResult.Ok("""{"you":"editor","authors":[]}"""))
            bridge.complete(first[2].id, AgentResult.Ok("""{"playing":false,"edits":[]}"""))
            repeat(IDLE_FRAMES) { session.frame() }
            assertEquals(emptyList(), drain().map { it.name }, "an idle editor kept calling tools")
        }
    }

    @Test
    fun `the undo button and Ctrl+Z both call editor undo as the editor's author`() {
        open().use { ui ->
            drain()
            ui.click(EditorTags.UNDO)
            ui.key(Key.Z, Modifiers(Modifiers.CONTROL))
            val sent = drain()
            assertEquals(listOf("editor.undo", "editor.undo"), sent.map { it.name })
            assertTrue(sent.all { it.session == author })
        }
    }

    @Test
    fun `a refused edit is shown, not swallowed`() {
        open().use { ui ->
            drain()
            ui.click(EditorTags.SPAWN)
            val spawn = drainHistoryAndEdits().single()
            bridge.complete(spawn.id, AgentResult.failed(NO_SPAWNER, "this editor was given no spawner"))
            session.frame()
            ui.settle()
            assertTrue("no spawner" in ui.text(EditorTags.STATUS), "the status line shows \"${ui.text(EditorTags.STATUS)}\"")
        }
    }

    @Test
    fun `the status line says paused and names the tick`() {
        open().use { ui ->
            tick = Tick(TICK)
            session.frame()
            ui.settle()
            val status = ui.text(EditorTags.STATUS)
            assertTrue("Paused" in status && "$TICK" in status, "the status line shows \"$status\"")
            paused = false
            session.frame()
            ui.settle()
            assertTrue("Running" in ui.text(EditorTags.STATUS))
        }
    }

    private companion object {
        const val EDITOR = "editor"
        const val SPAWN_X = 12.5f
        const val SPAWN_Y = -3.0f
        const val TICK = 412L
        const val IDLE_FRAMES = 10
        val NO_SPAWNER = dev.wildware.udea.agent.AgentErrorKind("no_spawner")
    }
}
