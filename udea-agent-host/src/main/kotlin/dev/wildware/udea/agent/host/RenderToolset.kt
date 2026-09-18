package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentToolArg
import dev.wildware.udea.agent.AgentToolDef
import dev.wildware.udea.agent.ToolModule
import dev.wildware.udea.agent.dispatch.AgentContext
import dev.wildware.udea.agent.tools.ContextualToolDef
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * What the render toolset needs from a renderer, and the whole of it.
 *
 * ## Why the port is here and the pixels are not
 *
 * `udea-render` owns the pixel path - the offscreen `FrameBuffer`, the alpha stomp, the PNG
 * encode, and the `FrameCaptureSlot` that fulfils a request *after* the frame is rendered and
 * before the buffer is swapped, which is what makes a capture reproducible rather than
 * whenever-the-HTTP-thread-asked. The arrow cannot point the other way -
 * `ReleaseRules.CLASSPATH_RULE` fails any release build that resolves `:udea-agent-host`, so a
 * renderer that depended on this module would put the agent surface in the shipped game.
 *
 * A port rather than a direct call because a game may hand the toolset a renderer that is not
 * `udea-render` at all; `OffscreenRenderControl` in this module is the implementation for the one
 * that is, and `PresentationControl` in `udea-render` is the other half of it.
 *
 * ## Why capture hands back a `Future` and does not just return the bytes
 *
 * A tool runs inside a `SimBarrier` drain, and on an `Offscreen` or `Windowed` host the thread
 * running that drain **is** the render thread: `KoolBackend.drive` hands the frame callback to
 * the render thread, and `GameLoop.frame` ticks and then renders on it. A `capture()` that
 * blocked until a frame settled it would be waiting for the thread it is running on.
 * `ToolRegistry` states the rule in as many words - *it must not block, sleep or wait on another
 * thread*.
 *
 * So the implementation queues the request and returns; a frame drawn later settles the future -
 * on Kool that is the *next* frame after the one that queued it, since a request is claimed at
 * one frame's capture point and only read at the top of the next - and the tool collects it from
 * [AgentContext.answerWhenReady], polled once per host iteration until the future is settled or a
 * grace period runs out. No blocking, and the command still completes with a real answer rather
 * than a promise.
 */
public interface RenderControl {

    /** Framebuffer width in pixels. Used to clamp a region and to report the clamped bounds. */
    public val framebufferWidth: Int

    /** Framebuffer height in pixels. */
    public val framebufferHeight: Int

    /**
     * Queues a capture of the **next frame drawn** and returns immediately.
     *
     * ## Why there is no `afterTick` here any more
     *
     * The renderer can hold a request until a named tick has finished - `FrameCaptureSlot` does
     * exactly that, and `GameHost.screenshot` uses it. This port does not expose that, and the
     * reason was the dispatcher rather than the pixel path: a capture tool queues the request and
     * assembles its answer in `AgentContext.answerLater`, which used to run **once**, so a request
     * for a tick not yet simulated had no callback left to be served from.
     *
     * `AgentContext.answerWhenReady` (issue #211) removed that specific constraint - a tool's
     * answer can now be polled across as many host iterations as it needs - so the reasoning
     * above no longer forces this argument's absence by itself. It stays gone anyway: every value
     * the toolset could have accepted selected the identical frame regardless (the next one this
     * renderer draws), so the argument was inert while reading, in the schema, as though a
     * screenshot could be aimed at a moment. [CaptureFrame.tick] is what an agent actually needs
     * and is the tick the renderer stamped, not one this module assumed. Bringing back an
     * `afterTick` that genuinely waits for a *named future* tick is a design change with its own
     * questions - what a still-paused host should answer, chiefly - and belongs to whichever
     * ticket wants that feature, not to this correction.
     *
     * @param region `null` for the whole framebuffer. Already validated against the framebuffer
     *   by the caller, so an implementation may read it as given.
     * @return a future the renderer settles at the capture point of the frame that serves it. A
     *   failure arrives as an [ExecutionException]; its cause's message is reported to the agent.
     */
    public fun capture(region: PixelRegion?): Future<CaptureFrame>

    /**
     * Points the camera at world [x],[y] with the given [zoom], and stops following.
     *
     * @return [CameraOutcome.APPLIED], or [CameraOutcome.NO_CAMERA_BOUND] when this renderer
     *   draws with a fixed projection. It returned `Unit`, and `render.set_camera` answered `ok`
     *   for a renderer with no camera in it - an agent then screenshots, sees the same frame, and
     *   has nothing to attribute it to. An implementation that cannot move a view must say so.
     */
    public fun setCamera(x: Float, y: Float, zoom: Float): CameraOutcome

    /**
     * Follows the entity with this network id, or stops following when [netId] is `null`.
     *
     * @return [CameraOutcome.APPLIED] only when the camera will genuinely track it. Every other
     *   value names a reason it will not - no camera, a camera bound to no world, an id that
     *   resolves to nothing, an entity with no pose to follow - and an implementation must not
     *   report `APPLIED` for a request it has merely accepted.
     */
    public fun followEntity(netId: NetId?): CameraOutcome

    /** Sets debug draw on or off, or toggles when [enabled] is `null`. Returns the new state. */
    public fun toggleDebugDraw(enabled: Boolean?): Boolean
}

/**
 * What a camera command actually did, as it crosses the port.
 *
 * The presentation side has an enum of its own (`udea-render`'s `CameraOutcome`) and the adapter
 * maps one onto the other. Two enums rather than one shared type because this port is what a game
 * implements to hand the toolset *any* renderer: an implementation that is not `udea-render` must
 * still be able to answer these questions, and it must not have to name a render type to do it.
 *
 * Every value except [APPLIED] becomes a typed [AgentResult] failure with its own error kind, so
 * an agent reads a specific reason - `no_camera_bound`, `entity_not_followable` - rather than a
 * success followed by a screenshot that did not change.
 */
public enum class CameraOutcome {

    /** A live camera took the request; the next frame applies it. */
    APPLIED,

    /** This renderer has no camera at all: it draws with a fixed projection. */
    NO_CAMERA_BOUND,

    /** A camera exists but is bound to no world, so it can never resolve a follow target. */
    CAMERA_NOT_BOUND,

    /** The network id resolves to no live entity. */
    NO_SUCH_ENTITY,

    /**
     * The entity is live, but nothing gives it a drawn position, so following it would leave the
     * camera exactly where it is.
     */
    NOT_FOLLOWABLE,
}

/**
 * One captured frame, as it crosses the port.
 *
 * Carries the dimensions and the tick **the renderer stamped it with** rather than the ones this
 * module assumed. Since a capture is always of the next frame drawn, [tick] is the only thing
 * that tells an agent *when* it is looking at - and it has to come from the frame. A result that
 * echoed back a tick this module had guessed would let an agent compare a picture of tick 199
 * against its expectation for tick 200 and conclude the game had changed.
 */
public class CaptureFrame(
    /** Width of [image] in pixels. */
    public val width: Int,
    /** Height of [image] in pixels. */
    public val height: Int,
    /** The simulation tick the frame was drawn at, as the renderer read the clock. */
    public val tick: Long,
    /** PNG bytes, colour type 6, every alpha byte 255. */
    public val image: ByteArray,
) {
    override fun toString(): String =
        "CaptureFrame(${width}x$height at tick $tick, ${image.size} bytes)"
}

/** A rectangle of the framebuffer, in pixels from the bottom-left, as GL counts them. */
public class PixelRegion(
    /** Left edge. */
    public val x: Int,
    /** Bottom edge. */
    public val y: Int,
    /** Width, at least 1. */
    public val w: Int,
    /** Height, at least 1. */
    public val h: Int,
) {
    init {
        require(w > 0 && h > 0) { "a capture region is at least 1x1, was ${w}x$h" }
    }

    override fun toString(): String = "PixelRegion($x, $y, ${w}x$h)"
}

/**
 * `screenshot`, `screenshot_region`, `set_camera`, `follow_entity`, `toggle_debug_draw`.
 *
 * ## The `Headless` contract
 *
 * Every tool here answers `{"ok":false,"error":{"kind":"no_render_context"}}` in
 * [RenderMode.Headless]. Never an exception, never a stall, never a blank image - and the
 * distinction is the point. An agent doing visual verification reads a blank image as "the screen
 * is black" and a thrown exception as "the tool is broken"; neither is true and both cost a
 * debugging round trip. `no_render_context` says the toolset is not live in this mode, which is
 * actionable. `completedCommandId` still advances, so a caller polling for the answer is released
 * by the command finishing rather than by it succeeding.
 *
 * ## How a screenshot completes
 *
 * `screenshot` and `screenshot_region` are [ContextualToolDef]s: they queue the capture, hand the
 * future to [AgentContext.answerWhenReady], and the answer is assembled once a frame has actually
 * settled it - on Kool that is the frame *after* the one that queued it, so it is polled for
 * rather than assumed after a single tick. The command completes with the artifact id, so
 * `completedCommandId` still means "the picture exists", not "the picture was asked for".
 *
 * A capture is always of the **next frame drawn**, and the answer reports the tick the renderer
 * stamped that frame with. There is no way to aim one at a chosen tick from here, and the tools
 * no longer publish an argument that says there is: see [RenderControl.capture] for why the
 * dispatcher, not the pixel path, is what makes that impossible today.
 */
public class RenderToolset(
    /** Reported by `/health`; the reason `Headless` refuses. */
    private val mode: RenderMode,
    /** The renderer, or `null` when none is wired. */
    private val control: RenderControl? = null,
    /** Where captures are filed. */
    private val artifacts: AgentArtifacts? = null,
    /**
     * How long [poll] keeps rechecking a capture before reporting a dead render loop.
     *
     * A constructor parameter rather than the [CAPTURE_GRACE_MILLIS] constant directly, so a
     * test can shrink it: real production code should wait out a few real frames on a slow
     * driver, but a test simulating a render loop that never settles a capture should not have
     * to sleep out that same real budget to observe it.
     */
    private val captureGraceMillis: Long = CAPTURE_GRACE_MILLIS,
) {

    /**
     * Captures the whole framebuffer.
     *
     * @return `null` once the capture has been queued, which is the idiom `AgentContext` fixes
     *   for a tool that answers later: the dispatcher skips its own completion, and a value here
     *   would be a second answer to one command id.
     */
    public fun screenshot(context: AgentContext): AgentResult? = capture(null, context)

    /**
     * Captures a region, in framebuffer pixels from the bottom-left.
     *
     * An out-of-bounds or empty rectangle is a typed error that **names the clamped bounds**,
     * rather than a silent clamp: an agent that asked for 800x600 of a 640x480 framebuffer and got
     * a 640x480 image back would compare it against the wrong expectation and conclude the game
     * had changed.
     */
    public fun screenshotRegion(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        context: AgentContext,
    ): AgentResult? {
        val renderer = live() ?: return unavailable()
        val width = renderer.framebufferWidth
        val height = renderer.framebufferHeight
        if (w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > width || y + h > height) {
            return AgentResult.failed(
                AgentErrorKind.BAD_ARGUMENT,
                "screenshot_region($x, $y, $w, $h) is not inside the framebuffer; the largest " +
                    "region here is (0, 0, $width, $height)",
            )
        }
        return capture(PixelRegion(x, y, w, h), context)
    }

    /** Points the camera. */
    public fun setCamera(x: Float, y: Float, zoom: Float): AgentResult {
        val renderer = live() ?: return unavailable()
        if (!x.isFinite() || !y.isFinite()) {
            return AgentResult.failed(
                AgentErrorKind.BAD_ARGUMENT,
                "set_camera needs a finite world position, was ($x, $y)",
            )
        }
        if (!(zoom > 0f) || !zoom.isFinite()) {
            return AgentResult.failed(
                AgentErrorKind.BAD_ARGUMENT,
                "set_camera zoom is a positive multiplier, was $zoom; zero or less collapses the " +
                    "projection and draws a frame of nothing",
            )
        }
        val outcome = renderer.setCamera(x, y, zoom)
        if (outcome != CameraOutcome.APPLIED) return cameraRefusal("set_camera", outcome, null)
        return AgentResult.ok {
            put("x", x)
            put("y", y)
            put("zoom", zoom)
        }
    }

    /** Follows an entity, or stops following when [netId] is negative. */
    public fun followEntity(netId: Long): AgentResult {
        val renderer = live() ?: return unavailable()
        if (netId < 0L) {
            val stopped = renderer.followEntity(null)
            if (stopped != CameraOutcome.APPLIED) {
                return cameraRefusal("follow_entity", stopped, null)
            }
            return AgentResult.ok { put("following", -1L) }
        }
        // `NetId.ofRaw` refuses a word with reserved bits set, which is what an agent that
        // invented an id rather than reading one out of a query would hand over. Turned into a
        // typed bad_argument here rather than left to throw: the dispatcher would report it as
        // `tool_threw`, which reads as an engine defect instead of a wrong argument.
        val id = runCatching { NetId.ofRaw(netId.toInt()) }.getOrNull()
        if (id == null || netId > Int.MAX_VALUE) {
            return AgentResult.failed(
                AgentErrorKind.BAD_ARGUMENT,
                "$netId is not a NetId this engine can hold; pass the packed value an entity " +
                    "query returned, or -1 to stop following",
            )
        }
        val outcome = renderer.followEntity(id)
        if (outcome != CameraOutcome.APPLIED) return cameraRefusal("follow_entity", outcome, netId)
        return AgentResult.ok { put("following", netId) }
    }

    /** Turns debug draw on, off, or over. */
    public fun toggleDebugDraw(enabled: Boolean?): AgentResult {
        val renderer = live() ?: return unavailable()
        return AgentResult.ok { put("debugDraw", renderer.toggleDebugDraw(enabled)) }
    }

    /**
     * Queues the capture now and answers for it once a frame has settled it.
     *
     * The two halves are deliberately split across [AgentContext.answerWhenReady]: the request
     * has to be queued *before* a frame is drawn, and the answer can only be assembled once one
     * has actually read the pixels back - which, on Kool, is not the same frame: a request is
     * claimed at one frame's capture point and only read at the top of the next
     * (`RenderPipeline`'s "Kool draws after this returns"). [AgentContext.answerLater] runs its
     * work exactly once, one tick after this call, which is one frame too early on a host whose
     * dispatch and render share a thread - every `Offscreen` and `Windowed` host driven by
     * `KoolBackend.drive` - because that thread cannot draw the second frame while it is blocked
     * waiting for it. [poll] is checked again on the next tick, and the next, until the frame
     * that settles it has actually happened.
     */
    private fun capture(region: PixelRegion?, context: AgentContext): AgentResult? {
        val renderer = live() ?: return unavailable()
        val store = artifacts ?: return AgentResult.failed(
            AgentHostErrors.NO_ARTIFACT_STORE,
            "this instance has no artifact store, so a capture has nowhere to go",
        )

        val pending = runCatching { renderer.capture(region) }.getOrElse { failure ->
            return AgentResult.failed(
                AgentHostErrors.CAPTURE_FAILED,
                "the renderer refused the capture request: ${failure.message ?: failure}",
            )
        }

        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(captureGraceMillis)
        context.answerWhenReady { poll(pending, store, region, deadlineNanos) }
        // The `answerWhenReady` idiom: the dispatcher skips its own completion when a tool has
        // deferred its answer, so returning anything here would be a second answer under one
        // command id.
        return null
    }

    /**
     * Checks whether [pending] has settled, without blocking for it.
     *
     * Returns `null` - "not ready, ask again next tick" - until either the frame that serves
     * [pending] has actually been drawn, or [deadlineNanos] has passed. The grace period is
     * bounded for the same two reasons it always was: a host whose render loop has died must
     * report that rather than wedge the caller forever, and a host pumped on a separate thread
     * from its render loop should not wait indefinitely for a frame that a slow driver is simply
     * still getting to.
     */
    private fun poll(
        pending: Future<CaptureFrame>,
        store: AgentArtifacts,
        region: PixelRegion?,
        deadlineNanos: Long,
    ): AgentResult? {
        if (!pending.isDone) {
            if (System.nanoTime() < deadlineNanos) return null
            pending.cancel(false)
            return AgentResult.failed(
                AgentHostErrors.CAPTURE_FAILED,
                "no frame was drawn for this capture within ${captureGraceMillis}ms; the " +
                    "render loop has stopped drawing",
            )
        }
        return file(pending, store, region)
    }

    /** Reads a [pending] already known to be settled, and files it. */
    private fun file(
        pending: Future<CaptureFrame>,
        store: AgentArtifacts,
        region: PixelRegion?,
    ): AgentResult {
        val frame = try {
            // Already known done by the caller (`poll`): this reads the value, it does not wait.
            pending.get()
        } catch (failed: ExecutionException) {
            val cause = failed.cause ?: failed
            return AgentResult.failed(
                AgentHostErrors.CAPTURE_FAILED,
                "the renderer could not capture a frame: ${cause.message ?: cause}",
            )
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return AgentResult.failed(
                AgentHostErrors.CAPTURE_FAILED,
                "the simulation thread was interrupted while collecting a capture",
            )
        }

        val id = store.put(frame.image, AgentArtifacts.PNG)
            ?: return AgentResult.failed(
                AgentHostErrors.NO_ARTIFACT_STORE,
                "the capture succeeded but could not be written to ${store.root}",
            )
        val artifact = store.get(id)
        // Path first, id second: the path is ~10 tokens and covers the same-machine case,
        // and the id is what a remote agent hands to GET /artifact. Bytes never travel in
        // a digest.
        return AgentResult.ok {
            put("artifactId", id.value)
            put("path", artifact?.path?.toString())
            put("w", frame.width)
            put("h", frame.height)
            put("tick", frame.tick)
            put("region", region?.toString())
        }
    }

    /**
     * Turns a camera command that did nothing into the error that says why.
     *
     * One place, because the two camera tools share every reason: an agent that gets
     * `no_camera_bound` from `set_camera` and something vaguer from `follow_entity` learns two
     * vocabularies for one fact. [CameraOutcome.APPLIED] is not a refusal and is a programming
     * error here rather than a message an agent should ever read.
     */
    private fun cameraRefusal(tool: String, outcome: CameraOutcome, netId: Long?): AgentResult =
        when (outcome) {
            CameraOutcome.APPLIED ->
                error("render.$tool asked for a refusal for an applied camera command")

            CameraOutcome.NO_CAMERA_BOUND -> AgentResult.failed(
                AgentHostErrors.NO_CAMERA_BOUND,
                "render.$tool cannot move the view: no camera is wired into this renderer, so it " +
                    "draws with a fixed projection. Screenshots still work and show the whole " +
                    "framebuffer; there is nothing to aim.",
            )

            CameraOutcome.CAMERA_NOT_BOUND -> AgentResult.failed(
                AgentHostErrors.CAMERA_NOT_BOUND,
                "render.$tool cannot follow anything: this renderer's camera was never bound to " +
                    "a world, so it can resolve no entity. render.set_camera still works. This " +
                    "is a host wiring fault - the camera was not registered with the render " +
                    "registry the pipeline was built from - and no argument value fixes it.",
            )

            CameraOutcome.NO_SUCH_ENTITY -> AgentResult.failed(
                AgentErrorKind.NO_SUCH_ENTITY,
                "$netId is not a live entity, so the camera has nothing to follow. Pass an id a " +
                    "current entity query returned; an id from before a rewind or a destroy is " +
                    "stale and resolves to nothing.",
            )

            CameraOutcome.NOT_FOLLOWABLE -> AgentResult.failed(
                AgentHostErrors.ENTITY_NOT_FOLLOWABLE,
                "entity $netId exists but has no drawn position for the camera to track - " +
                    "nothing interpolates it, which for this engine means it has no physics " +
                    "body. Following it would leave the camera exactly where it is, so the " +
                    "request is refused instead of accepted. Use render.set_camera with the " +
                    "position a world query reports for it.",
            )
        }

    /** The renderer, or `null` when this process has no live render context. */
    private fun live(): RenderControl? = if (mode == RenderMode.Headless) null else control

    private fun unavailable(): AgentResult = AgentResult.failed(
        AgentHostErrors.NO_RENDER_CONTEXT,
        if (mode == RenderMode.Headless) {
            "this process runs in RenderMode.Headless: there is no GL context to read pixels " +
                "from, so the render toolset is not live. Drive the game through /state and the " +
                "world tools instead."
        } else {
            "no renderer is wired into this agent host, so the render toolset is not live"
        },
    )

    override fun toString(): String = "RenderToolset($mode, control=${control != null})"

    private companion object {

        /**
         * How long the deferred answer waits for a frame that should already have been drawn.
         *
         * Half a second: long enough to absorb a stalled frame on a loaded machine, short enough
         * that a host which shares its thread between simulation and rendering - where crossing
         * this is a defect, not a delay - reports rather than freezing the game for a human.
         */
        const val CAPTURE_GRACE_MILLIS: Long = 500L
    }
}

/**
 * The tools this module publishes, as a [ToolModule].
 *
 * Hand-written rather than emitted by KSP, for the reason [CompareArtifactsTool] gives. It is
 * **not** on a generated registry: a host registers it explicitly, because the toolsets it names
 * need constructor arguments the host owns - the artifact store, the render mode, the renderer -
 * and a generated list cannot be handed any of them.
 *
 * ```
 * val index = ToolIndex.builder()
 *     .registry(MobaUdeaRegistry)                    // the generated modules
 *     .module(AgentHostTools)                        // this one
 *     .toolset(ArtifactToolset(artifacts))
 *     .toolset(RenderToolset(mode, control, artifacts))
 *     .build()
 * ```
 */
public object AgentHostTools : ToolModule {

    override val moduleName: String = "UdeaAgentHost"

    override val tools: List<AgentToolDef<*>> = listOf(
        CompareArtifactsTool,
        FollowEntityTool,
        ScreenshotTool,
        ScreenshotRegionTool,
        SetCameraTool,
        ToggleDebugDrawTool,
    ).sortedBy { it.name }
}

/** Base for this module's hand-written render declarations: everything but `invoke`. */
public abstract class RenderToolDef(
    override val name: String,
    override val description: String,
    override val args: List<AgentToolArg>,
) : AgentToolDef<RenderToolset> {

    /**
     * Built from [args] by [ToolSchema], never written by hand.
     *
     * The two were separate literals and had drifted: the hand-written schemas declared no
     * dialect, allowed additional properties, wrote an empty `required` array the generator
     * omits, and did not fold defaults into their descriptions - a second dialect on one surface,
     * which is exactly what the single-mechanism rule exists to stop. Derived, they cannot
     * disagree with the argument list beside them, and `ToolSchemaTest` checks the derivation
     * against a real generated tool's own schema.
     */
    override val inputSchema: String = ToolSchema.of(args)

    override val owner: KClass<*> = RenderToolset::class
}

/**
 * Base for the two capture declarations, which need the [AgentContext] of the command.
 *
 * See [ContextualToolDef]: a capture is queued during the tick and answered after it, and
 * `answerLater` lives on the context. The two-argument [invoke] is unreachable through
 * `ToolIndex` and refuses rather than capturing without being able to answer.
 */
public abstract class CaptureToolDef(
    override val name: String,
    override val description: String,
    override val args: List<AgentToolArg>,
) : ContextualToolDef<RenderToolset> {

    /** Derived from [args]. See [RenderToolDef.inputSchema] for why it is not written by hand. */
    override val inputSchema: String = ToolSchema.of(args)

    override val owner: KClass<*> = RenderToolset::class

    override fun invoke(receiver: RenderToolset, command: AgentCommand): Any? =
        throw UnsupportedOperationException(
            "$name answers after the tick and needs the AgentContext of the command it is " +
                "serving; call the three-argument invoke, which is what ToolIndex does",
        )
}

/** `render.screenshot`. */
public object ScreenshotTool : CaptureToolDef(
    name = "render.screenshot",
    description = "Capture the next frame drawn as a PNG and file it in the artifact store. Reach " +
        "for it whenever a number cannot tell you what the screen looks like - before and after a " +
        "command, or either side of a rewind - and pass the two ids to render.compare_artifacts. " +
        "Returns the file path (open it directly if you are on this machine), an artifact id " +
        "(fetch it with GET /artifact if you are not) and 'tick', the simulation tick the frame " +
        "was drawn at - read it rather than assuming, because a time tool sent in the same batch " +
        "runs after this capture. Answers no_render_context in Headless.",
    args = emptyList(),
) {
    override fun invoke(
        receiver: RenderToolset,
        command: AgentCommand,
        context: AgentContext,
    ): Any? = receiver.screenshot(context = context)
}

/** `render.screenshot_region`. */
public object ScreenshotRegionTool : CaptureToolDef(
    name = "render.screenshot_region",
    description = "Capture a rectangle of the frame as a PNG, in framebuffer pixels measured from " +
        "the bottom-left. Use it to watch one part of the screen - a health bar, a minimap - " +
        "without diffing the whole frame, which is dominated by whatever else moved. A rectangle " +
        "outside the framebuffer is refused with the largest region that would fit, rather than " +
        "silently clamped. Answers no_render_context in Headless.",
    args = listOf(
        AgentToolArg("x", "integer", "Left edge in framebuffer pixels.", required = true, default = null),
        AgentToolArg("y", "integer", "Bottom edge in framebuffer pixels.", required = true, default = null),
        AgentToolArg("w", "integer", "Width in pixels, at least 1.", required = true, default = null),
        AgentToolArg("h", "integer", "Height in pixels, at least 1.", required = true, default = null),
    ),
) {
    override fun invoke(
        receiver: RenderToolset,
        command: AgentCommand,
        context: AgentContext,
    ): Any? = receiver.screenshotRegion(
        x = command.int("x"),
        y = command.int("y"),
        w = command.int("w"),
        h = command.int("h"),
        context = context,
    )
}

/** `render.set_camera`. */
public object SetCameraTool : RenderToolDef(
    name = "render.set_camera",
    description = "Move the camera to a world position and zoom level. Reach for it when what you " +
        "want to look at is off screen, or too small to judge, before taking a screenshot. It " +
        "stops the camera following an entity, changes presentation only, and cannot affect the " +
        "simulation. The move lands on the next frame drawn, so the next screenshot shows it. " +
        "Answers no_camera_bound on a renderer that draws with a fixed projection, and " +
        "no_render_context in Headless.",
    args = listOf(
        AgentToolArg("x", "number", "World x to centre on.", required = true, default = null),
        AgentToolArg("y", "number", "World y to centre on.", required = true, default = null),
        AgentToolArg(
            "zoom",
            "number",
            "Zoom factor; 1 is the default framing, larger shows more world.",
            required = false,
            default = "1",
        ),
    ),
) {
    override fun invoke(receiver: RenderToolset, command: AgentCommand): Any? =
        receiver.setCamera(command.float("x"), command.float("y"), command.float("zoom", 1f))
}

/** `render.follow_entity`. */
public object FollowEntityTool : RenderToolDef(
    name = "render.follow_entity",
    description = "Keep the camera on one entity by network id, so it stays framed as it moves. " +
        "Reach for it before stepping the simulation forward to watch what one unit does. Pass -1 " +
        "to stop following and leave the camera where it is. The request is checked before it is " +
        "accepted: an id that resolves to nothing answers no_such_entity, and an entity the " +
        "camera could not actually track - one with no physics body, so nothing draws it at a " +
        "position - answers entity_not_followable rather than ok. Answers no_camera_bound where " +
        "there is no camera, and no_render_context in Headless.",
    args = listOf(
        AgentToolArg(
            "netId",
            "integer",
            "Network id of the entity to follow, as reported by an entity query. -1 stops following.",
            required = true,
            default = null,
        ),
    ),
) {
    override fun invoke(receiver: RenderToolset, command: AgentCommand): Any? =
        receiver.followEntity(command.long("netId"))
}

/** `render.toggle_debug_draw`. */
public object ToggleDebugDrawTool : RenderToolDef(
    name = "render.toggle_debug_draw",
    description = "Turn the debug overlay on or off - collision shapes, bounds and whatever else " +
        "the game draws there. Reach for it when a screenshot looks right but the simulation does " +
        "not behave, because the overlay shows the geometry the game is actually using. Omit " +
        "'enabled' to flip whatever it currently is. Answers no_render_context in Headless.",
    args = listOf(
        AgentToolArg(
            "enabled",
            "boolean",
            "true to show the overlay, false to hide it. Omit to toggle.",
            required = false,
            default = null,
        ),
    ),
) {
    override fun invoke(receiver: RenderToolset, command: AgentCommand): Any? =
        receiver.toggleDebugDraw(if ("enabled" in command) command.bool("enabled") else null)
}
