package dev.wildware.udea.agent.host

import dev.wildware.udea.core.host.RenderMode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * `editor.screenshot` (issue #234) through the real dispatch path, against views a test controls.
 *
 * What a real view's pass holds - gizmos in it, none in the capturable frame - is
 * `GlWorldViewportTest`'s; this is about which view a call reads and what it answers when there is
 * none to read.
 */
class EditorViewToolsetTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun `each view is captured from its own pass and filed with the view named`() {
        val views = FakeViews()
        val toolset = EditorViewToolset(RenderMode.Offscreen, AgentArtifacts(temp)).apply { bind(views) }
        val harness = harness(toolset)

        val scene = harness.ok("editor.screenshot", "view" to "scene")
        val game = harness.ok("editor.screenshot", "view" to "game")

        assertEquals(listOf(EditorView.Scene, EditorView.Game), views.asked)
        assertContains(scene, """"view":"scene"""")
        assertContains(game, """"view":"game"""")
        assertContains(scene, """"tick":${FakeViews.TICK}""")
        assertContentEquals(FakeViews.bytes(EditorView.Scene), Files.readAllBytes(Path.of(path(scene))))
        assertContentEquals(FakeViews.bytes(EditorView.Game), Files.readAllBytes(Path.of(path(game))))
    }

    @Test
    fun `an editor with no window open answers no_editor_window`() {
        val harness = harness(EditorViewToolset(RenderMode.Offscreen, AgentArtifacts(temp)))

        val message = harness.refusal("editor.screenshot", "view" to "scene", kind = "no_editor_window")

        assertContains(message, "render.screenshot")
    }

    @Test
    fun `a view that is not scene or game is refused and names the two`() {
        val views = FakeViews()
        val harness = harness(EditorViewToolset(RenderMode.Offscreen, AgentArtifacts(temp)).apply { bind(views) })

        val message = harness.refusal("editor.screenshot", "view" to "inspector", kind = "bad_argument")

        assertContains(message, "scene")
        assertContains(message, "game")
        assertEquals(emptyList(), views.asked)
    }

    @Test
    fun `Headless answers no_render_context, as every capture tool does`() {
        val views = FakeViews()
        val harness = harness(EditorViewToolset(RenderMode.Headless, AgentArtifacts(temp)).apply { bind(views) }, RenderMode.Headless)

        harness.refusal("editor.screenshot", "view" to "game", kind = "no_render_context")

        assertEquals(emptyList(), views.asked)
    }

    // --- fixture -------------------------------------------------------------------------

    private fun harness(toolset: EditorViewToolset, mode: RenderMode = RenderMode.Offscreen) =
        RenderToolsHarness(mode = mode, artifacts = AgentArtifacts(temp), editorViews = toolset)

    private fun path(json: String): String =
        Regex(""""path":"([^"]+)"""").find(json)?.groupValues?.get(1) ?: error("no path in $json")

    /** Two views that answer at once, each with bytes of its own so a crossed wire cannot pass. */
    private class FakeViews : EditorViewControl {
        val asked = ArrayList<EditorView>()

        override fun capture(view: EditorView): CompletableFuture<CaptureFrame> {
            asked += view
            return CompletableFuture.completedFuture(CaptureFrame(8, 4, TICK, bytes(view)))
        }

        companion object {
            const val TICK: Long = 777L

            fun bytes(view: EditorView): ByteArray = FakeRenderControl.PNG_HEADER + byteArrayOf(view.ordinal.toByte(), 42)
        }
    }
}
