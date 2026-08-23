package dev.wildware.udea.agent.host

import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The five render tools, driven through the real dispatch path against a renderer a test controls.
 *
 * The GL half is `OffscreenRenderToolsTest`, which runs the identical tools against a real driver.
 * This half is about the decisions the toolset makes — what it refuses, what it reports, and when
 * it answers — and those are worth checking with no window in the way, because they are the ones
 * an agent reads.
 */
class RenderToolsetTest {

    @TempDir
    lateinit var temp: Path

    // --- capture ------------------------------------------------------------------------------

    @Test
    fun `a screenshot completes in the iteration that asked for it`() {
        val control = FakeRenderControl()
        val harness = harness(control)

        val json = harness.ok("render.screenshot")

        assertContains(json, """"artifactId":"cap_0000"""")
        assertContains(json, """"w":64""")
        assertContains(json, """"h":32""")
        assertEquals(listOf("full@next"), control.requests)
    }

    /**
     * The result carries the tick the **renderer** stamped the frame with, not the one the agent
     * asked for.
     *
     * An agent that asked for `afterTick = 200`, was quietly handed the frame drawn at 199 and
     * read its own request back would compare the picture against the wrong expectation and
     * conclude the game had changed. The only way to notice is for the answer to come from the
     * frame.
     */
    @Test
    fun `the reported tick is the one the frame was drawn at`() {
        val harness = harness(FakeRenderControl())
        // The clock has to have passed the requested tick, or the request is refused before the
        // renderer sees it - which is `afterTick in the future is refused` below.
        harness.host.loop.stepTicks(3)

        val json = harness.ok("render.screenshot", "afterTick" to "1")

        assertContains(json, """"tick":${FakeRenderControl.CAPTURED_TICK}""")
    }

    /**
     * Two captures in one iteration both complete, with different artifact ids.
     *
     * The failure this rules out is the one the old `ScreenCapture` had by construction: a single
     * `lastPath` field that two overlapping requests raced for, so the second caller read the
     * first caller's screenshot and never knew.
     */
    @Test
    fun `two overlapping screenshots get distinct artifact ids`() {
        val control = FakeRenderControl()
        val harness = harness(control)

        val first = harness.ok("render.screenshot")
        val second = harness.ok("render.screenshot_region", "x" to "0", "y" to "0", "w" to "8", "h" to "4")

        val a = artifactId(first)
        val b = artifactId(second)
        assertNotEquals(a, b, "two captures were filed under one id")
        assertEquals(listOf("full@next", "PixelRegion(0, 0, 8x4)@next"), control.requests)
        assertNotNull(harness.artifacts?.get(ArtifactId.parse(a)!!))
        assertNotNull(harness.artifacts?.get(ArtifactId.parse(b)!!))
    }

    @Test
    fun `a region outside the framebuffer is refused with the bounds that would fit`() {
        val control = FakeRenderControl(framebufferWidth = 64, framebufferHeight = 32)
        val harness = harness(control)

        val message = harness.refusal(
            "render.screenshot_region",
            "x" to "0", "y" to "0", "w" to "800", "h" to "600",
            kind = "bad_argument",
        )

        assertContains(message, "(0, 0, 64, 32)")
        assertEquals(emptyList(), control.requests, "a refused region must not reach the renderer")
    }

    @Test
    fun `a negative region origin is refused rather than clamped`() {
        val control = FakeRenderControl()
        val harness = harness(control)

        harness.refusal(
            "render.screenshot_region",
            "x" to "-1", "y" to "0", "w" to "8", "h" to "8",
            kind = "bad_argument",
        )

        assertEquals(emptyList(), control.requests)
    }

    /**
     * `afterTick` naming a tick that has not been simulated is refused, and the refusal says what
     * to do instead.
     *
     * The renderer would hold such a request across frames quite happily. The reason it is refused
     * here is the threading one: on an `Offscreen` host the thread that would draw those frames is
     * the one the tool is running on, so the answer could never be assembled. An agent told
     * `tick_not_reached` with the current tick in the message can act; one left waiting cannot.
     */
    @Test
    fun `afterTick in the future is refused with the current tick`() {
        val control = FakeRenderControl()
        val harness = harness(control)

        val message = harness.refusal(
            "render.screenshot",
            "afterTick" to "5000",
            kind = "tick_not_reached",
        )

        assertContains(message, "afterTick=5000")
        assertContains(message, "time.step")
        assertEquals(emptyList(), control.requests)
    }

    /**
     * A capture the renderer never serves fails as `capture_failed`, and the loop keeps running.
     *
     * A render loop that has died is the realistic way to get here, and the important half of the
     * assertion is the second one: the command completes, so a caller polling
     * `completedCommandId` is released rather than reporting a healthy host as frozen.
     */
    @Test
    fun `a frame that is never drawn fails the command instead of stalling the loop`() {
        val control = FakeRenderControl().apply { settleImmediately = false }
        val harness = harness(control)

        val message = harness.refusal("render.screenshot", kind = "capture_failed")

        assertContains(message, "render loop has stopped drawing")
        assertTrue(bridgeAdvanced(harness), "completedCommandId did not advance for a failed capture")
    }

    /** A renderer that refuses the request outright is reported, not thrown into the dispatcher. */
    @Test
    fun `a renderer that throws on submit is reported as capture_failed`() {
        val harness = harness(
            object : RenderControl by FakeRenderControl() {
                override fun capture(region: PixelRegion?, afterTick: Long?) =
                    throw IllegalStateException("no pixel source")
            },
        )

        val message = harness.refusal("render.screenshot", kind = "capture_failed")

        assertContains(message, "no pixel source")
    }

    /** A capture with a renderer but nowhere to put the bytes says which of the two is missing. */
    @Test
    fun `a capture with no artifact store is refused before the renderer is asked`() {
        val control = FakeRenderControl()
        val harness = RenderToolsHarness(RenderMode.Offscreen, control, artifacts = null)

        harness.refusal("render.screenshot", kind = "no_artifact_store")

        assertEquals(emptyList(), control.requests)
    }

    // --- camera, follow, debug ----------------------------------------------------------------

    @Test
    fun `set_camera reaches the renderer and echoes what it applied`() {
        val control = FakeRenderControl()
        val harness = harness(control)

        val json = harness.ok("render.set_camera", "x" to "3.5", "y" to "-2", "zoom" to "0.5")

        assertEquals("3.5,-2.0,0.5", control.camera)
        assertContains(json, """"zoom":0.5""")
    }

    /**
     * A zoom of zero is refused rather than passed on.
     *
     * `camera.zoom = 0` collapses the projection matrix, so the next capture is a frame of
     * nothing — which an agent reads as "the game went black" and starts debugging.
     */
    @Test
    fun `set_camera refuses a zoom that would collapse the projection`() {
        val control = FakeRenderControl()
        val harness = harness(control)

        harness.refusal(
            "render.set_camera",
            "x" to "0", "y" to "0", "zoom" to "0",
            kind = "bad_argument",
        )

        assertNull(control.camera, "a refused zoom must not reach the renderer")
    }

    @Test
    fun `follow_entity passes the packed NetId through and minus one stops following`() {
        val control = FakeRenderControl()
        val harness = harness(control)
        val id = NetId.of(index = 7, generation = 2)

        harness.ok("render.follow_entity", "netId" to id.raw.toString())
        assertEquals(id, control.followed)

        harness.ok("render.follow_entity", "netId" to "-1")
        assertNull(control.followed, "-1 must stop following")
        assertEquals(2, control.followCalls)
    }

    /** A word with reserved bits set is a bad argument, not an engine defect. */
    @Test
    fun `follow_entity refuses an id this engine cannot hold`() {
        val control = FakeRenderControl()
        val harness = harness(control)

        harness.refusal("render.follow_entity", "netId" to "16777216", kind = "bad_argument")

        assertEquals(0, control.followCalls)
    }

    @Test
    fun `toggle_debug_draw sets, clears and flips`() {
        val control = FakeRenderControl()
        val harness = harness(control)

        assertContains(harness.ok("render.toggle_debug_draw", "enabled" to "true"), """"debugDraw":true""")
        assertContains(harness.ok("render.toggle_debug_draw", "enabled" to "false"), """"debugDraw":false""")
        assertContains(harness.ok("render.toggle_debug_draw"), """"debugDraw":true""")
        assertTrue(control.debugDraw)
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun harness(control: RenderControl) = RenderToolsHarness(
        mode = RenderMode.Offscreen,
        control = control,
        artifacts = AgentArtifacts(temp),
    )

    private fun artifactId(json: String): String =
        Regex(""""artifactId":"([^"]+)"""").find(json)?.groupValues?.get(1)
            ?: error("no artifactId in $json")

    private fun bridgeAdvanced(harness: RenderToolsHarness): Boolean =
        harness.bridge.completedCommandId() > 0
}
