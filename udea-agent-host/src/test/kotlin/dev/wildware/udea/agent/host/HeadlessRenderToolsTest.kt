package dev.wildware.udea.agent.host

import dev.wildware.udea.core.host.RenderMode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `RenderMode.Headless`: every render tool refuses, by name, and the loop never notices.
 *
 * ## Why this is a whole test class
 *
 * It is the behaviour the render toolset exists to *not* get wrong. An agent doing visual
 * verification reads a blank image as "the screen is black" and a thrown exception as "the tool is
 * broken"; neither is true in a headless process, and both cost a debugging round trip on a
 * question the host could have answered in one token. `no_render_context` says the toolset is not
 * live in this mode, which is actionable — `/health` reports the mode, so an agent can even avoid
 * asking.
 *
 * The second half matters as much as the first: `completedCommandId` advances for every refusal.
 * A command that never completes is a healthy game reported as frozen, and a caller polling for it
 * cannot tell that apart from a crash.
 */
class HeadlessRenderToolsTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun `every render tool answers no_render_context and completes`() {
        val harness = RenderToolsHarness(RenderMode.Headless, control = null, artifacts = artifacts())

        val calls: List<Pair<String, Array<Pair<String, String>>>> = listOf(
            "render.screenshot" to emptyArray(),
            "render.screenshot" to arrayOf("afterTick" to "1"),
            "render.screenshot_region" to
                arrayOf("x" to "0", "y" to "0", "w" to "8", "h" to "8"),
            "render.set_camera" to arrayOf("x" to "1", "y" to "2"),
            "render.follow_entity" to arrayOf("netId" to "0"),
            "render.toggle_debug_draw" to emptyArray(),
        )

        for ((name, args) in calls) {
            // Command ids come from a counter shared by every bridge in the JVM, so what is
            // asserted is that the mark *moved* - which is the property a caller polls for.
            val before = harness.bridge.completedCommandId()

            val message = harness.refusal(name, *args, kind = "no_render_context")

            assertContains(message, "RenderMode.Headless")
            assertTrue(
                harness.bridge.completedCommandId() > before,
                "$name refused but did not advance completedCommandId",
            )
        }
    }

    /**
     * A host with a render context but no renderer wired says something different, on purpose.
     *
     * The kind is the same — there is no context this toolset can reach either way — but the
     * message separates "this mode has no GL" from "somebody forgot to wire a renderer", because
     * the remedies are a different person's problem.
     */
    @Test
    fun `a mode with a context but no renderer says so in the message`() {
        val harness = RenderToolsHarness(RenderMode.Offscreen, control = null, artifacts = artifacts())

        val message = harness.refusal("render.toggle_debug_draw", kind = "no_render_context")

        assertContains(message, "no renderer is wired")
        assertTrue("Headless" !in message, "an Offscreen host must not blame the mode: $message")
    }

    /**
     * A renderer wired into a `Headless` host is still refused.
     *
     * The mode is the authority, not the presence of an object: a host constructed in `Headless`
     * never invoked its `PresentationFactory`, so anything in that slot is a wiring mistake, and
     * calling it would be reaching for a GL context that was never created.
     */
    @Test
    fun `a renderer wired into a Headless host is ignored`() {
        val control = FakeRenderControl()
        val harness = RenderToolsHarness(RenderMode.Headless, control, artifacts())

        harness.refusal("render.screenshot", kind = "no_render_context")

        assertEquals(emptyList(), control.requests, "a Headless host must not ask a renderer")
    }

    /**
     * `render.compare_artifacts` keeps working, which is the point of it being engine-side and
     * GL-free: a CI job can diff two captures that were produced somewhere else entirely.
     */
    @Test
    fun `compare_artifacts still answers in Headless`() {
        val store = artifacts()
        val harness = RenderToolsHarness(RenderMode.Headless, control = null, artifacts = store)
        val a = requireNotNull(store.put(onePixelPng(), AgentArtifacts.PNG))
        val b = requireNotNull(store.put(onePixelPng(), AgentArtifacts.PNG))

        val json = harness.ok("render.compare_artifacts", "a" to a.value, "b" to b.value)

        assertContains(json, """"identical":true""")
    }

    private fun artifacts() = AgentArtifacts(temp)

    /** A 1x1 opaque white PNG, encoded by the JDK so the bytes are a real image. */
    private fun onePixelPng(): ByteArray {
        val image = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, -1)
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "png", out)
        return out.toByteArray()
    }
}
