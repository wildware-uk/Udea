package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentSubmission
import dev.wildware.udea.agent.dispatch.AgentRuntime
import dev.wildware.udea.agent.dispatch.ToolIndex
import dev.wildware.udea.agent.state.DigestSources
import dev.wildware.udea.agent.state.LoopStatus
import dev.wildware.udea.agent.state.StateDigest
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.UdeaGameDef
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs a render tool the way the engine runs one: submitted to the bridge, drained onto the
 * barrier, dispatched, and answered — including the answer that arrives *after* the tick.
 *
 * ## Why the tests do not just call the toolset
 *
 * `screenshot` and `screenshot_region` are `ContextualToolDef`s that queue a capture and answer
 * through `AgentContext.answerLater`, and an `AgentContext` has an internal constructor: outside
 * `udea-agent` there is no way to make one, which is deliberate — a tool must not be able to keep
 * a context and reach the world from an HTTP thread later. So the only way to exercise a capture
 * tool at all is the real path, and that is a good thing: the ordering between "the tool queues a
 * capture", "the frame is drawn" and "the deferred answer is assembled" is the part that is easy
 * to get wrong, and calling the method directly would test none of it.
 *
 * [pump] is one iteration of [AgentGameLoop], which is what a host runs.
 */
internal class RenderToolsHarness(
    mode: RenderMode = RenderMode.Headless,
    control: RenderControl? = null,
    val artifacts: AgentArtifacts? = null,
) {

    val bridge = AgentBridge()

    val host = GameHost(mode, UdeaGameDef(modules = emptyList()))

    val toolset = RenderToolset(mode, control, artifacts)

    private val tools = ToolIndex.builder()
        .module(AgentHostTools)
        .toolset(toolset)
        .toolset(ArtifactToolset(artifacts))
        .build()

    private val runtime = AgentRuntime(
        bridge = bridge,
        tools = tools,
        world = host.world,
        ctx = host.ctx,
        digest = StateDigest(bridge, DigestSources(loop = Paused(host))),
    )

    private val loop = AgentGameLoop(host, runtime)

    init {
        // Paused, like every agent session that is inspecting something. It is also the harder
        // case for a capture: with no tick to run, `AgentRuntime` drains the barrier in
        // `afterFrame`, which is after the frame has been drawn - see `AgentGameLoop.pump`.
        host.loop.paused = true
    }

    /** One host iteration with a zero wall delta, so nothing here reads a clock. */
    fun pump() {
        loop.pump(0f)
    }

    /**
     * Submits [name], pumps until it completes, and returns its answer.
     *
     * Two pumps at most: one to queue and answer a capture, and a second only if a tool ever
     * needs another. A tool that has not completed by then is a defect and is reported as one
     * rather than waited out.
     */
    fun call(name: String, vararg args: Pair<String, String>): AgentResult {
        val submission = bridge.submit(AgentCommand(name, args.toMap()))
        val accepted = submission as? AgentSubmission.Accepted
        assertNotNull(accepted, "the bridge refused $name: $submission")
        repeat(MAX_PUMPS) {
            pump()
            if (bridge.completedCommandId() >= accepted.commandId) {
                val result = bridge.commandResults().lastOrNull { it.id == accepted.commandId }
                assertNotNull(result, "$name completed but recorded no result")
                return result.result
            }
        }
        error("$name did not complete within $MAX_PUMPS host iterations")
    }

    /** Asserts [name] failed with [kind], and returns the message for a further assertion. */
    fun refusal(name: String, vararg args: Pair<String, String>, kind: String): String {
        val result = call(name, *args)
        assertTrue(result is AgentResult.Failed, "$name was expected to fail, got $result")
        assertEquals(kind, result.error.kind.id, "$name failed with the wrong kind: ${result.error}")
        return result.error.message
    }

    /** Asserts [name] succeeded, and returns its JSON. */
    fun ok(name: String, vararg args: Pair<String, String>): String {
        val result = call(name, *args)
        assertTrue(result is AgentResult.Ok, "$name was expected to succeed, got $result")
        return result.json
    }

    private class Paused(private val host: GameHost) : LoopStatus {
        override val paused: Boolean get() = host.loop.paused
        override val timeScale: Float get() = host.loop.timeScale
        override val fps: Float get() = 0f
    }

    private companion object {
        const val MAX_PUMPS = 4
    }
}

/**
 * A [RenderControl] with no renderer behind it, driven by the test.
 *
 * The capture future is settled by hand, which is how the ordering assertions are made: a test
 * that settles it before pumping is a renderer that drew the frame in time, and one that never
 * settles it is a render loop that has stopped.
 */
internal class FakeRenderControl(
    override val framebufferWidth: Int = 64,
    override val framebufferHeight: Int = 32,
) : RenderControl {

    /** Every capture asked for, in order. */
    val requests = ArrayList<String>()

    /** The futures handed back, in request order, so a test can settle them. */
    val pending = ArrayList<CompletableFuture<CaptureFrame>>()

    var camera: String? = null
        private set

    /**
     * What this renderer answers a camera command with.
     *
     * A field rather than a constant because "no camera is wired" is a *state a real renderer is
     * in*, not a separate class: `PresentationControl` built without a `CameraRig` answers
     * `NO_CAMERA` to every camera command and serves captures perfectly well. A test that had to
     * write a second fake to reach that state would be testing its own fake.
     */
    var cameraOutcome: CameraOutcome = CameraOutcome.APPLIED

    /** What a non-null follow request answers. Separate: only following can fail this way. */
    var followOutcome: CameraOutcome = CameraOutcome.APPLIED

    var followed: NetId? = null
        private set

    var followCalls: Int = 0
        private set

    var debugDraw: Boolean = false
        private set

    /** Settled the moment it is requested, which is what a live renderer does within the frame. */
    var settleImmediately: Boolean = true

    /** The bytes handed back. Distinct per capture so two artifacts cannot be confused. */
    var nextImage: () -> ByteArray = { PNG_HEADER + byteArrayOf(requests.size.toByte()) }

    override fun capture(region: PixelRegion?): Future<CaptureFrame> {
        requests += "${region ?: "full"}"
        val future = CompletableFuture<CaptureFrame>()
        pending += future
        if (settleImmediately) {
            future.complete(
                CaptureFrame(
                    width = region?.w ?: framebufferWidth,
                    height = region?.h ?: framebufferHeight,
                    tick = CAPTURED_TICK,
                    image = nextImage(),
                ),
            )
        }
        return future
    }

    override fun setCamera(x: Float, y: Float, zoom: Float): CameraOutcome {
        if (cameraOutcome != CameraOutcome.APPLIED) return cameraOutcome
        camera = "$x,$y,$zoom"
        return CameraOutcome.APPLIED
    }

    override fun followEntity(netId: NetId?): CameraOutcome {
        if (cameraOutcome != CameraOutcome.APPLIED) return cameraOutcome
        // A stop is always applied where a camera exists: there is nothing to resolve.
        val outcome = if (netId == null) CameraOutcome.APPLIED else followOutcome
        if (outcome != CameraOutcome.APPLIED) return outcome
        followed = netId
        followCalls++
        return CameraOutcome.APPLIED
    }

    override fun toggleDebugDraw(enabled: Boolean?): Boolean {
        debugDraw = enabled ?: !debugDraw
        return debugDraw
    }

    companion object {
        /** Not a real PNG: the toolset files bytes, it does not decode them. */
        val PNG_HEADER: ByteArray = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte())

        /**
         * The tick the renderer stamps a frame with, deliberately unlike anything the test asks
         * for, so an assertion that the result carries it cannot pass by coincidence.
         */
        const val CAPTURED_TICK: Long = 4_242L
    }
}
