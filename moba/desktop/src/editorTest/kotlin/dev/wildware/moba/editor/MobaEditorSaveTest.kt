package dev.wildware.moba.editor

import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.moba.MobaGame
import dev.wildware.moba.agent.MobaAgent
import dev.wildware.moba.agent.MobaAssetTools
import dev.wildware.moba.entry.MobaLaunchLevel
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.editor.EditorAssetTags
import dev.wildware.udea.editor.EditorSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #195 end to end: `moba`'s own asset scripts, the real asset daemon, the real `assets.*`
 * tools and the editor window wired exactly as `runEditor` wires it, driven by keys and clicks.
 *
 * It works on a copy of `moba/game/assets` under this project's `build/`, never on the real tree:
 * a test that saved into the checked-in scripts would leave the working tree dirty whenever it
 * failed half way. The copy keeps the real paths below the root, so a diff here reads like a diff of
 * the real file.
 *
 * The first criterion is checked the way the issue words it - `git diff` of the saved file against
 * the file before - rather than by a comparison of this test's own devising.
 */
class MobaEditorSaveTest {

    private val root: Path = Path(checkNotNull(System.getProperty(PROJECT_DIR)) { "$PROJECT_DIR is not set" })
        .resolve("build/tmp/editor-save").also {
            it.toFile().deleteRecursively()
            it.createDirectories()
        }
    private val assets: Path = root.resolve("moba/game/assets")
    private val soldier: Path = assets.resolve("character/soldier.udea.kts")

    private val restore: Map<String, String?> = listOf(
        MobaAssetTools.ASSET_ROOT_PROPERTY,
        MobaAssetTools.REPO_ROOT_PROPERTY,
    ).associateWith { System.getProperty(it) }

    private val wiring = MobaAgent.Wiring()
    private val host: GameHost
    private val session: MobaAgent.Session

    init {
        assets.parent.createDirectories()
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        Path(checkNotNull(System.getProperty(GAME_ASSETS)) { "$GAME_ASSETS is not set" }).copyToRecursively(assets, followLinks = false)
        System.setProperty(MobaAssetTools.ASSET_ROOT_PROPERTY, assets.toString())
        System.setProperty(MobaAssetTools.REPO_ROOT_PROPERTY, root.toString())
        host = MobaGame.host(RenderMode.Headless, extraModules = wiring.extraModules, level = MobaLaunchLevel.bytes())
        session = MobaAgent.attach(host, RenderMode.Headless, null, wiring, editor = true)
    }

    @AfterTest
    fun close() {
        session.close("test over")
        restore.forEach { (name, value) -> if (value == null) System.clearProperty(name) else System.setProperty(name, value) }
    }

    @Test
    fun `changing one value in a commented script and pressing Ctrl+S changes exactly that value`() {
        val editor = MobaEditor.session(host, session, viewport = {})
        val before = soldier.readText()
        val original = root.resolve("soldier-before.udea.kts").also { Files.writeString(it, before) }

        uiTest { editor.window.content() }.use { ui ->
            open(ui, editor, "character/soldier")
            replace(ui, EditorAssetTags.field("health"), "120")
            ui.key(Key.S, Modifiers(Modifiers.CONTROL))
            frames(ui, editor)

            val message = ui.text(EditorAssetTags.MESSAGE)
            assertTrue("Saved `health` = 120 into moba/game/assets/character/soldier.udea.kts. The running game has it now." in message, message)
            assertEquals(listOf("-    health = 100F,", "+    health = 120F,"), gitDiff(original, soldier), "the save changed more than the one value")

            // And back: the second save leaves the file byte for byte as it was, comments and all.
            replace(ui, EditorAssetTags.field("health"), "100")
            ui.key(Key.S, Modifiers(Modifiers.CONTROL))
            frames(ui, editor)
            assertEquals(before, soldier.readText())
        }
    }

    @Test
    fun `a value set by the file's own constant is read-only, with the constant and its line`() {
        val editor = MobaEditor.session(host, session, viewport = {})
        val before = soldier.readText()

        uiTest { editor.window.content() }.use { ui ->
            open(ui, editor, "character/soldier_idle_sheet")

            val fields = ui.text(EditorAssetTags.FIELDS)
            assertTrue("scale  read-only: set by `val soldierScale` on line 8" in fields, fields)
        }
        // What an agent is told when it tries anyway, through the same tool Save uses.
        val refused = call("assets.set", "id" to "character/soldier_idle_sheet", "field" to "scale", "value" to "2")
        assertTrue(refused is AgentResult.Failed && "set by `val soldierScale` on line 8" in refused.error.message, "$refused")
        assertEquals(before, soldier.readText())
    }

    @Test
    fun `save as new writes a generated asset the asset compiler accepts with no diagnostics`() {
        val editor = MobaEditor.session(host, session, viewport = {})

        uiTest { editor.window.content() }.use { ui ->
            open(ui, editor, "character/soldier_idle_sheet")
            replace(ui, EditorAssetTags.field("columns"), "3")
            assertTrue(ui.click(EditorAssetTags.NEW_NAME), "no new-name box:\n${ui.dump()}")
            ui.type("soldier_idle_half")
            assertTrue(ui.click(EditorAssetTags.SAVE_AS_NEW), "no Save as new button:\n${ui.dump()}")
            frames(ui, editor)

            val message = ui.text(EditorAssetTags.MESSAGE)
            assertTrue("Created character/soldier_idle_half in moba/game/assets/character/soldier_idle_half.udea.kts." in message, message)
            assertTrue("The running game gets new assets on its next launch." in message, message)
        }
        val created = assets.resolve("character/soldier_idle_half.udea.kts")
        assertTrue(created.exists(), "no new file")
        assertTrue("columns = 3," in created.readText() && "scale = 1.58F," in created.readText(), created.readText())

        val validate = call("assets.validate")
        check(validate is AgentResult.Ok) { "assets.validate failed: $validate" }
        assertTrue("\"ok\":true" in validate.json && "\"diagnostics\":[]" in validate.json, validate.json)
    }

    /** Types [id] into the Asset panel and opens it. */
    private fun open(ui: UiTest, editor: EditorSession, id: String) {
        frames(ui, editor)
        assertTrue(ui.click(EditorAssetTags.ID), "no asset id box:\n${ui.dump()}")
        ui.type(id)
        assertTrue(ui.click(EditorAssetTags.OPEN), "no Open button:\n${ui.dump()}")
        frames(ui, editor)
        assertTrue("Editing $id" in ui.text(EditorAssetTags.MESSAGE), ui.text(EditorAssetTags.MESSAGE))
    }

    /** Clears the box tagged [tag] and types [value] into it. */
    private fun replace(ui: UiTest, tag: String, value: String) {
        assertTrue(ui.click(tag), "no box $tag:\n${ui.dump()}")
        repeat(CLEAR) { ui.key(Key.Backspace) }
        ui.type(value)
    }

    /** A few frames of the order `runWithGl` runs them in: pump the loop, then the editor's frame. */
    private fun frames(ui: UiTest, editor: EditorSession) {
        repeat(FRAMES) {
            session.loop.pump(1f / 60f)
            editor.frame()
            ui.settle()
        }
    }

    private fun call(tool: String, vararg args: Pair<String, String>): AgentResult {
        val command = AgentCommand(tool, args.toMap(), session = wiring.sessions.intern(MobaEditor.AUTHOR))
        wiring.bridge.submit(command)
        session.loop.pump(1f / 60f)
        return wiring.bridge.commandResults().single { it.id == command.id }.result
    }

    private companion object {
        const val PROJECT_DIR = "udea.moba.projectDir"
        const val GAME_ASSETS = "udea.moba.gameAssets"

        /** Enough for a save to be sent, run, answered, and the panel's re-read to come back. */
        const val FRAMES = 8

        /** More backspaces than any value these tests clear has characters. */
        const val CLEAR = 8

        /**
         * The changed lines of `git diff --no-index` from [before] to [after], without the header and
         * hunk lines: what a reviewer of the saved file would read.
         */
        fun gitDiff(before: Path, after: Path): List<String> {
            val process = ProcessBuilder("git", "diff", "--no-index", "--no-color", "-U0", before.toString(), after.toString())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(DIFF_SECONDS, TimeUnit.SECONDS)) { "git diff did not finish" }
            return output.lines().filter { (it.startsWith("-") || it.startsWith("+")) && !it.startsWith("---") && !it.startsWith("+++") }
        }

        const val DIFF_SECONDS = 30L
    }
}
