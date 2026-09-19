package dev.wildware.udea.editor

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
 * The Inspector panel (issue #235) against the tool surface's own seam, with no game behind it: it
 * reads what the selection shares from `editor.common_fields`, shows **Mixed** where the entities
 * disagree, and sets a field on all of them with one `editor.set_field`.
 *
 * The simulation's half is played by hand, as in `EditorSessionTest`. The real tools applying that
 * one call as one undoable edit over a real `moba` world is `MobaInspectorTest`'s.
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
    fun `setting a field writes every selected entity with one set_field, and Set with nothing typed sends nothing`() {
        open().use { ui ->
            selectTwo(ui)

            assertTrue(ui.click(InspectorTags.set(TEAM)), "the Set button took no click:\n${ui.dump()}")
            assertEquals(emptyList(), drain().map { it.name }, "Set with nothing typed sent a call")

            assertTrue(ui.click(InspectorTags.field(TEAM)), "the team box took no click")
            ui.type("1")
            ui.click(InspectorTags.set(TEAM))

            val writes = drain().filter { it.name == "editor.set_field" }
            assertEquals(1, writes.size, "one Set sent ${writes.size} writes: $writes")
            assertEquals(
                mapOf("id" to "3,5", "component" to "GameUnit", "field" to "team", "value" to "1"),
                writes.single().args,
                "the write does not name every selected entity",
            )
            assertEquals(author, writes.single().session, "the write must land in the editor's own undo history")
        }
    }

    /**
     * Opens on a selection of entities 3 and 5, as `editor.selection` answers it, which disagree on
     * `GameUnit.team` and agree that `Position.x` is 4.5.
     */
    private fun selectTwo(ui: UiTest) {
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
        bridge.complete(
            read.id,
            AgentResult.Ok(
                """{"entities":2,"fields":[""" +
                    """{"component":"GameUnit","field":"team","mixed":true},""" +
                    """{"component":"Position","field":"x","mixed":false,"value":4.5}]}""",
            ),
        )
        session.frame()
        ui.settle()
    }

    private companion object {
        const val TEAM = "GameUnit.team"
        const val X = "Position.x"
    }
}
