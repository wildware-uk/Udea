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
        assertEquals(listOf("full"), control.requests)
    }

    /**
     * The result carries the tick the **renderer** stamped the frame with, and nothing else.
     *
     * A capture is always of the next frame drawn, so this field is the only thing that tells an
     * agent *when* it is looking at. A result that echoed back a tick this module had assumed
     * would let an agent compare a picture of tick 199 against its expectation for 200 and
     * conclude the game had changed.
     */
    @Test
    fun `the reported tick is the one the frame was drawn at`() {
        val harness = harness(FakeRenderControl())
        harness.host.loop.stepTicks(3)

        val json = harness.ok("render.screenshot")

        assertContains(json, """"tick":${FakeRenderControl.CAPTURED_TICK}""")
    }

    /**
     * The capture tools publish no `afterTick`, because this host cannot honour one.
     *
     * They did, and it did nothing: a tick still in the future was refused - correctly, the
     * answer is assembled in the same host iteration and no frame drawn in it could serve one -
     * and a tick already simulated selected the very frame a request with no tick at all
     * selects. Every accepted value behaved identically to omitting the argument, while the
     * schema told a model a screenshot could be aimed at a moment.
     *
     * Asserted over the published declarations rather than over a call, because the schema is
     * what a model reads: an argument that survives only in the schema is still a lie.
     */
    @Test
    fun `no capture tool advertises an argument this host cannot honour`() {
        val captures = AgentHostTools.tools.filterIsInstance<CaptureToolDef>()

        assertEquals(2, captures.size, "the capture tools are screenshot and screenshot_region")
        for (tool in captures) {
            assertTrue(
                tool.args.none { it.name == "afterTick" },
                "${tool.name} still declares afterTick",
            )
            assertTrue("afterTick" !in tool.inputSchema, "${tool.name}'s schema still declares it")
        }
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
        assertEquals(listOf("full", "PixelRegion(0, 0, 8x4)"), control.requests)
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
                override fun capture(region: PixelRegion?) =
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

    /**
     * A renderer with no camera refuses both camera tools by name instead of answering `ok`.
     *
     * This is the failure the whole outcome enum exists for. `set_camera` used to answer
     * `{"x":..,"y":..,"zoom":..}` and `follow_entity` `{"following":n}` on a renderer that drew
     * with a fixed projection - the agent then screenshots, sees the same framing, and has
     * nothing to attribute it to. There is no round trip that recovers from that: every later
     * observation is consistent with a camera that moved somewhere uninteresting.
     */
    @Test
    fun `both camera tools refuse by name when no camera is bound`() {
        val control = FakeRenderControl().apply { cameraOutcome = CameraOutcome.NO_CAMERA_BOUND }
        val harness = harness(control)

        val placed = harness.refusal(
            "render.set_camera",
            "x" to "1", "y" to "2",
            kind = "no_camera_bound",
        )
        assertContains(placed, "fixed projection")

        harness.refusal("render.follow_entity", "netId" to "0", kind = "no_camera_bound")
        harness.refusal("render.follow_entity", "netId" to "-1", kind = "no_camera_bound")

        assertNull(control.camera, "a refused placement must not be recorded as applied")
        assertEquals(0, control.followCalls)
    }

    /**
     * A camera that exists but is bound to no world says *that*, not `no_camera_bound`.
     *
     * Different remedies: one is "this renderer has no camera and never will", the other is "a
     * camera was built and never registered with the render registry". The first is a property of
     * the game, the second is a wiring bug somebody can fix in a line.
     */
    @Test
    fun `a camera bound to no world refuses a follow with its own kind`() {
        val control = FakeRenderControl().apply {
            followOutcome = CameraOutcome.CAMERA_NOT_BOUND
        }
        val harness = harness(control)

        val message = harness.refusal("render.follow_entity", "netId" to "0", kind = "camera_not_bound")

        assertContains(message, "set_camera still works")
        assertEquals(0, control.followCalls)
        // ...and placing the camera on such a rig is genuinely fine, so it must still succeed.
        harness.ok("render.set_camera", "x" to "1", "y" to "1")
    }

    /**
     * An entity nothing draws at a position is refused as `entity_not_followable`.
     *
     * `moba` had this written down against itself: its units carry `Position` and no
     * `PhysicsBody`, nothing interpolates them, and `render.follow_entity` answered `ok` while
     * the camera sat exactly where it was. `no_such_entity` would be the wrong answer - the
     * entity is there - and `ok` was a worse one.
     */
    @Test
    fun `an entity with no drawn position is refused rather than followed`() {
        val control = FakeRenderControl().apply { followOutcome = CameraOutcome.NOT_FOLLOWABLE }
        val harness = harness(control)

        val message = harness.refusal(
            "render.follow_entity",
            "netId" to "5",
            kind = "entity_not_followable",
        )

        assertContains(message, "entity 5")
        assertContains(message, "render.set_camera")
        assertEquals(0, control.followCalls, "a refused follow reached the camera anyway")
    }

    /** A stale id is a `no_such_entity`, which is the kind the rest of the surface uses for it. */
    @Test
    fun `an id that resolves to nothing is refused as no_such_entity`() {
        val control = FakeRenderControl().apply { followOutcome = CameraOutcome.NO_SUCH_ENTITY }
        val harness = harness(control)

        harness.refusal("render.follow_entity", "netId" to "9", kind = "no_such_entity")

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
