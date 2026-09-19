package dev.wildware.udea.editor

import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.activity.AgentSessions
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.blueprint.BlueprintId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Asset panel and Save (issue #195) against the bridge, with no game behind it.
 *
 * As `EditorSessionTest` does for the Create and History panels, these tests play the game's half
 * by hand - drain what the window sent, answer it - and assert on the calls and on what the panel
 * shows. What the calls do to a real file and a real daemon is `AssetsSaveToolsTest`'s job in
 * `udea-agent`, and the whole path from a keypress to a file on disk is `MobaEditorSaveTest`'s.
 */
class EditorAssetsTest {

    private val bridge = AgentBridge()
    private val sessions = AgentSessions()
    private val author = sessions.intern("editor")

    private val session = EditorSession(
        tools = EditorTools(bridge, author),
        tick = { Tick(0) },
        paused = { true },
        spawn = EditorSpawn("Spawn", BlueprintId("skeleton"), 0f, 0f),
        views = EditorViews.detached(),
    )

    private fun drain(): List<AgentCommand> = ArrayList<AgentCommand>().also { bridge.drain(it) }

    /**
     * Everything the window sent, minus the reads it makes by itself - the History panel's, which are
     * `EditorSessionTest`'s, and the selection's, which are `ScenePickingTest`'s.
     */
    private fun sent(): List<AgentCommand> = drain().filter { it.name != "editor.history" && it.name != "editor.selection" }

    private fun answer(command: AgentCommand, result: AgentResult, ui: UiTest) {
        bridge.complete(command.id, result)
        session.frame()
        ui.settle()
    }

    /** Opens `character/soldier_idle_sheet` in the panel, answering its `assets.fields` with [FIELDS]. */
    private fun opened(ui: UiTest) {
        drain()
        assertTrue(ui.click(EditorAssetTags.ID), "no asset id field:\n${ui.dump()}")
        ui.type("character/soldier_idle_sheet")
        assertTrue(ui.click(EditorAssetTags.OPEN), "no Open button:\n${ui.dump()}")
        val read = sent().single()
        assertEquals("assets.fields", read.name)
        assertEquals(mapOf("id" to "character/soldier_idle_sheet"), read.args)
        assertEquals(author, read.session)
        answer(read, AgentResult.Ok(FIELDS), ui)
    }

    @Test
    fun `opening an asset lists its values, and a computed one says why it is read-only and where`() {
        uiTest { session.window.content() }.use { ui ->
            opened(ui)

            val fields = ui.text(EditorAssetTags.FIELDS)
            assertTrue("columns" in fields, fields)
            assertTrue("scale  read-only: set by `val soldierScale` on line 8" in fields, fields)
        }
    }

    @Test
    fun `an asset listed over two pages is read to the end before the panel shows it`() {
        uiTest { session.window.content() }.use { ui ->
            drain()
            ui.click(EditorAssetTags.ID)
            ui.type("character/soldier")
            ui.click(EditorAssetTags.OPEN)
            answer(
                sent().single(),
                AgentResult.Ok("""{"id":"character/soldier","total":2,"next":1,"fields":[{"name":"size","line":3,"editable":true,"text":"1F","type":"Float","value":"1.0"}]}"""),
                ui,
            )
            val second = sent().single()
            assertEquals(mapOf("id" to "character/soldier", "from" to "1"), second.args)
            answer(
                second,
                AgentResult.Ok("""{"id":"character/soldier","total":2,"fields":[{"name":"health","line":4,"editable":true,"text":"100F","type":"Float","value":"100.0"}]}"""),
                ui,
            )
            assertEquals(emptyList(), sent().map { it.name }, "the last page asked for another")
            assertTrue("Editing character/soldier" in ui.text(EditorAssetTags.MESSAGE), ui.text(EditorAssetTags.MESSAGE))
            assertTrue(ui.click(EditorAssetTags.field("size")) && ui.click(EditorAssetTags.field("health")), "a page is missing:\n${ui.dump()}")
        }
    }

    @Test
    fun `Ctrl+S saves each changed value with assets set, and only the changed ones`() {
        uiTest { session.window.content() }.use { ui ->
            opened(ui)
            assertTrue(ui.click(EditorAssetTags.field("columns")), "no field for columns:\n${ui.dump()}")
            ui.key(Key.Backspace)
            ui.type("7")

            ui.key(Key.S, Modifiers(Modifiers.CONTROL))

            val save = sent().single()
            assertEquals("assets.set", save.name)
            assertEquals(mapOf("id" to "character/soldier_idle_sheet", "field" to "columns", "value" to "7"), save.args)
            assertEquals(author, save.session)
        }
    }

    @Test
    fun `a save the game took says so, and one it could not take says it applies on the next launch`() {
        uiTest { session.window.content() }.use { ui ->
            opened(ui)
            editColumns(ui, "7")
            ui.click(EditorAssetTags.SAVE)
            answer(sent().single(), AgentResult.Ok("""{"path":"moba/game/assets/character/soldier.udea.kts","changed":true,"applied":true}"""), ui)
            val took = ui.text(EditorAssetTags.MESSAGE)
            assertTrue("Saved `columns` = 7 into moba/game/assets/character/soldier.udea.kts. The running game has it now." in took, took)

            editColumns(ui, "8")
            ui.click(EditorAssetTags.SAVE)
            answer(
                sent().single { it.name == "assets.set" },
                AgentResult.Ok("""{"path":"moba/game/assets/character/soldier.udea.kts","changed":true,"applied":false,"code":"reload_requires_restart"}"""),
                ui,
            )
            val later = ui.text(EditorAssetTags.MESSAGE)
            assertTrue("It applies on the next launch." in later, later)
        }
    }

    @Test
    fun `a refused save is shown with the tool's reason`() {
        uiTest { session.window.content() }.use { ui ->
            opened(ui)
            editColumns(ui, "7")
            ui.click(EditorAssetTags.SAVE)
            answer(sent().single(), AgentResult.failed(AgentErrorKind("read_only_field"), "`columns` is read-only: set by `val x` on line 2"), ui)
            val shown = ui.text(EditorAssetTags.MESSAGE)
            assertTrue("Not saved: `columns` is read-only: set by `val x` on line 2" in shown, shown)
        }
    }

    @Test
    fun `saving with nothing changed sends nothing and says so`() {
        uiTest { session.window.content() }.use { ui ->
            opened(ui)
            ui.key(Key.S, Modifiers(Modifiers.CONTROL))
            assertEquals(emptyList(), sent().map { it.name })
            assertTrue("Nothing to save" in ui.text(EditorAssetTags.MESSAGE), ui.text(EditorAssetTags.MESSAGE))
        }
    }

    @Test
    fun `save as new creates the copy, then saves the changed values into the copy`() {
        uiTest { session.window.content() }.use { ui ->
            opened(ui)
            editColumns(ui, "4")
            assertTrue(ui.click(EditorAssetTags.NEW_NAME), "no new-name field:\n${ui.dump()}")
            ui.type("soldier_idle_copy")
            assertTrue(ui.click(EditorAssetTags.SAVE_AS_NEW), "no Save as new button:\n${ui.dump()}")

            val create = sent().single()
            assertEquals("assets.create", create.name)
            assertEquals(mapOf("from" to "character/soldier_idle_sheet", "name" to "soldier_idle_copy"), create.args)
            answer(
                create,
                AgentResult.Ok("""{"id":"character/soldier_idle_copy","created":true,"path":"moba/game/assets/character/soldier_idle_copy.udea.kts","changed":true,"applied":false,"code":"reload_requires_restart"}"""),
                ui,
            )

            val set = sent().single { it.name == "assets.set" }
            assertEquals(mapOf("id" to "character/soldier_idle_copy", "field" to "columns", "value" to "4"), set.args)
            val shown = ui.text(EditorAssetTags.MESSAGE)
            assertTrue("Created character/soldier_idle_copy in moba/game/assets/character/soldier_idle_copy.udea.kts" in shown, shown)
        }
    }

    private fun editColumns(ui: UiTest, value: String) {
        ui.click(EditorAssetTags.field("columns"))
        repeat(4) { ui.key(Key.Backspace) }
        ui.type(value)
    }

    private companion object {
        /** `assets.fields`' answer for a sheet with one value set by a constant, as the tool renders it. */
        const val FIELDS = """{"id":"character/soldier_idle_sheet","kind":"spriteSheet","file":"moba/game/assets/character/soldier.udea.kts","total":3,"fields":[""" +
            """{"name":"name","line":56,"editable":false,"reason":"the asset's name, which is its id, on line 56","reasonLine":56},""" +
            """{"name":"columns","line":59,"editable":true,"text":"6","type":"Int","value":"6"},""" +
            """{"name":"scale","line":60,"editable":false,"reason":"set by `val soldierScale` on line 8","reasonLine":8}]}"""
    }
}
