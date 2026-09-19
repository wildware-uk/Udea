package dev.wildware.udea.editor

import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentError
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.activity.AgentSessions
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.blueprint.BlueprintId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Inspector panel (issue #235) against the tool surface's own seam, with no game behind it: it
 * reads what the selection shares from `editor.common_fields`, shows **Mixed** where the entities
 * disagree, and writes what is typed to all of them through one G1 edit session: live while typing,
 * one commit on Enter or on leaving the box, and a cancel when the box holds what the field cannot.
 *
 * The simulation's half is played by hand, as in `EditorSessionTest`. The real tools writing those
 * calls over a real `moba` world, and filing one undoable edit, is `MobaInspectorTest`'s.
 */
class InspectorTest {

    private val bridge = AgentBridge()
    private val author = AgentSessions().intern("editor")

    private val session = EditorSession(
        tools = EditorTools(bridge, author),
        tick = { Tick(0) },
        paused = { true },
        spawn = EditorSpawn("Spawn", BlueprintId("skeleton"), 0f, 0f),
        views = EditorViews.detached(),
    )

    private fun open(): UiTest = uiTest { session.window.content() }

    private fun drain(): List<AgentCommand> = ArrayList<AgentCommand>().also { bridge.drain(it) }

    @Test
    fun `a field the selection disagrees on shows Mixed, and one it agrees on shows the value`() {
        open().use { ui ->
            selectTwo(ui)

            val team = ui.texts(InspectorTags.field(TEAM))
            val x = ui.texts(InspectorTags.field(X))
            assertEquals(listOf(MIXED), team, "the field the two disagree on shows $team")
            assertEquals(listOf("4.5"), x, "the field the two agree on shows $x")
            assertTrue("2 entities" in ui.text(InspectorTags.HEADING), "the heading says \"${ui.text(InspectorTags.HEADING)}\"")
        }
    }

    @Test
    fun `typing into a field writes every selected entity live through one edit session, with no Set button`() {
        open().use { ui ->
            selectTwo(ui)
            val shown = ui.texts(InspectorTags.FIELDS)
            assertTrue(TEAM in shown, "the fields are not where this looks for a button: $shown")
            assertFalse("Set" in shown, "the panel still has a Set button: $shown")

            assertTrue(ui.click(InspectorTags.field(TEAM)), "the team box took no click:\n${ui.dump()}")
            ui.type("1")
            session.frame()
            val begin = drain().single { it.name == "editor.begin_edit" }
            assertEquals(mapOf("entities" to "3,5", "fields" to TEAM), begin.args, "the edit is not over every selected entity")
            assertEquals(author, begin.session, "the edit must land in the editor's own undo history")
            bridge.complete(begin.id, AgentResult.Ok("""{"sessionId":7,"start":[]}"""))
            session.frame()
            val first = drain().single { it.name == "editor.update_edit" }
            assertEquals(mapOf("sessionId" to "7", "values" to "$TEAM=1"), first.args)
            bridge.complete(first.id, AgentResult.Ok("""{"sessionId":7,"written":2}"""))

            ui.type("2")
            session.frame()
            val second = drain().single { it.name == "editor.update_edit" }
            assertEquals(mapOf("sessionId" to "7", "values" to "$TEAM=12"), second.args, "the second keystroke did not write the whole value")
            bridge.complete(second.id, AgentResult.Ok("""{"sessionId":7,"written":2}"""))
            session.frame()
            assertEquals(emptyList(), drain().map { it.name }.filter { it != "editor.common_fields" && it != "editor.history" && it != "editor.selection" }, "nothing is committed while typing")
        }
    }

    @Test
    fun `Enter commits the typing as one undo entry`() {
        open().use { ui ->
            selectTwo(ui)
            typeAndAnswer(ui, "12")
            ui.key(Key.Enter)
            session.frame()
            val sent = drain().map { it.name }
            assertEquals(1, sent.count { it == "editor.commit_edit" }, "Enter sent $sent")
            assertEquals(0, sent.count { it == "editor.set_field" || it == "editor.cancel_edit" }, "Enter sent $sent")
        }
    }

    @Test
    fun `leaving the field commits the typing as one undo entry`() {
        open().use { ui ->
            selectTwo(ui)
            typeAndAnswer(ui, "3")
            // Somewhere else to be: the other field.
            assertTrue(ui.click(InspectorTags.field(X)), "the x box took no click")
            session.frame()
            val sent = drain().map { it.name }
            assertEquals(1, sent.count { it == "editor.commit_edit" }, "leaving the field sent $sent")
        }
    }

    @Test
    fun `a value the field cannot hold is never kept, so the edit is cancelled rather than committed`() {
        open().use { ui ->
            selectTwo(ui)
            assertTrue(ui.click(InspectorTags.field(TEAM)), "the team box took no click")
            ui.type("-")
            session.frame()
            val begin = drain().single { it.name == "editor.begin_edit" }
            bridge.complete(begin.id, AgentResult.Ok("""{"sessionId":7,"start":[]}"""))
            session.frame()
            val update = drain().single { it.name == "editor.update_edit" }
            // What the tool answers for a half-typed number: refused, and nothing written.
            bridge.complete(update.id, AgentResult.Failed(AgentError(AgentErrorKind.BAD_ARGUMENT, "'-' is not an Int")))
            session.frame()
            ui.key(Key.Enter)
            session.frame()
            val sent = drain().map { it.name }
            assertEquals(1, sent.count { it == "editor.cancel_edit" }, "a refused value was not cancelled: $sent")
            assertEquals(0, sent.count { it == "editor.commit_edit" }, "a refused value was committed: $sent")
        }
    }

    /**
     * A selection with more in common than the bridge carries inline to an outside agent: a moba
     * character, once its `Transform3D` is editable (issue #237), answers `editor.common_fields` with
     * about 1500 characters. The bridge hands an outside caller a handle for an answer that size;
     * the Inspector is in this process, reads it whole, and lists every field.
     */
    @Test
    fun `a selection with more fields in common than an outside agent gets inline still lists every one`() {
        val rows = (0 until MANY).joinToString(",") { index ->
            """{"component":"Transform3D","field":"field$index","mixed":false,"value":$index.5}"""
        }
        val answer = """{"entities":2,"fields":[$rows]}"""
        assertTrue(answer.length > AgentBridge.MAX_DELIVERABLE_RESULT_CHARS, "the answer fits inline after all: ${answer.length} characters")
        open().use { ui ->
            selectTwo(ui, answer)

            for (index in 0 until MANY) {
                val field = "Transform3D.field$index"
                assertEquals(listOf("$index.5"), ui.texts(InspectorTags.field(field)), "the Inspector does not show $field:\n${ui.dump()}")
            }
        }
    }

    /** Clicks the team box, types [text], and answers the edit session it opens. */
    private fun typeAndAnswer(ui: UiTest, text: String) {
        assertTrue(ui.click(InspectorTags.field(TEAM)), "the team box took no click")
        ui.type(text)
        session.frame()
        val begin = drain().single { it.name == "editor.begin_edit" }
        bridge.complete(begin.id, AgentResult.Ok("""{"sessionId":7,"start":[]}"""))
        session.frame()
        val update = drain().single { it.name == "editor.update_edit" }
        assertEquals(mapOf("sessionId" to "7", "values" to "$TEAM=$text"), update.args)
        bridge.complete(update.id, AgentResult.Ok("""{"sessionId":7,"written":2}"""))
        session.frame()
        drain()
    }

    /**
     * Opens on a selection of entities 3 and 5, as `editor.selection` answers it, which disagree on
     * `GameUnit.team` and agree that `Position.x` is 4.5.
     */
    private fun selectTwo(ui: UiTest, fields: String = TWO_FIELDS) {
        session.frame()
        for (command in drain()) {
            when (command.name) {
                "editor.history" -> bridge.complete(command.id, AgentResult.Ok("""{"author":"editor","size":0,"edits":[]}"""))
                "editor.selection" ->
                    bridge.complete(command.id, AgentResult.Ok("""{"you":"editor","authors":[{"author":"editor","ids":[3,5]}]}"""))
            }
        }
        session.frame()
        val read = drain().single { it.name == "editor.common_fields" }
        assertEquals(mapOf("entities" to "3,5"), read.args, "the Inspector read another selection's fields")
        bridge.complete(read.id, AgentResult.Ok(fields))
        session.frame()
        ui.settle()
    }

    private companion object {
        const val TEAM = "GameUnit.team"
        const val X = "Position.x"

        /** `editor.common_fields` for entities 3 and 5: they disagree on the team, and agree on x. */
        const val TWO_FIELDS = """{"entities":2,"fields":[""" +
            """{"component":"GameUnit","field":"team","mixed":true},""" +
            """{"component":"Position","field":"x","mixed":false,"value":4.5}]}"""

        /** Fields enough that `editor.common_fields` answers above the inline ceiling. */
        const val MANY = 24
    }
}
