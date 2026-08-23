package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentToolArg
import dev.wildware.udea.agent.AgentToolDef
import dev.wildware.udea.agent.ToolModule
import dev.wildware.udea.core.host.CaptureOutcome
import dev.wildware.udea.core.host.RenderMode
import kotlin.reflect.KClass

/**
 * What the render toolset needs from a renderer, and the whole of it.
 *
 * ## Why the port is here and the pixels are not
 *
 * `udea-render` owns the pixel path - the offscreen `FrameBuffer`, the alpha stomp, the PNG
 * encode, and the `FrameCaptureSlot` that fulfils a request *after* the frame is rendered and
 * before the buffer is swapped, which is what makes `screenshot(afterTick = T)` reproducible
 * rather than whenever-the-HTTP-thread-asked. None of that can live in this module: it has no GL
 * on its classpath and would fail `udeaVerifyHeadless` for naming a GL type.
 *
 * So this is the seam. A renderer implements it; this module declares the tools, decides what
 * `Headless` answers, and files the bytes in the artifact store. `afterTick` is carried across
 * the seam rather than resolved here, because only the implementation knows where a frame
 * boundary is.
 */
public interface RenderControl {

    /** Framebuffer width in pixels. Used to clamp a region and to report the clamped bounds. */
    public val framebufferWidth: Int

    /** Framebuffer height in pixels. */
    public val framebufferHeight: Int

    /**
     * Captures the frame, or the region of it, as encoded image bytes.
     *
     * @param region `null` for the whole framebuffer. Already validated against the framebuffer
     *   by the caller, so an implementation may read it as given.
     * @param afterTick capture the first frame rendered after this simulation tick, or `null` for
     *   the next frame. An implementation that cannot schedule captures may ignore it; it must
     *   not block waiting for the tick, because the simulation thread is the caller.
     */
    public fun capture(region: PixelRegion?, afterTick: Long?): CaptureOutcome

    /** Points the camera at world [x],[y] with the given [zoom]. */
    public fun setCamera(x: Float, y: Float, zoom: Float)

    /** Follows the entity with this network id, or stops following when [netId] is `null`. */
    public fun followEntity(netId: Long?)

    /** Sets debug draw on or off, or toggles when [enabled] is `null`. Returns the new state. */
    public fun toggleDebugDraw(enabled: Boolean?): Boolean
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
 * ## What is real here and what is not
 *
 * Real: the declarations, the `Headless` behaviour, region validation with a clamped-bounds error
 * message, and the hand-off of bytes to [AgentArtifacts]. Not here: the pixels. A host that wires
 * `control = null` - which is every host today, because no renderer implements [RenderControl]
 * yet - gets the same typed `no_render_context` a `Headless` process does, which is honest: there
 * is no context this toolset can reach.
 */
public class RenderToolset(
    /** Reported by `/health`; the reason `Headless` refuses. */
    private val mode: RenderMode,
    /** The renderer, or `null` when none is wired. */
    private val control: RenderControl? = null,
    /** Where captures are filed. */
    private val artifacts: AgentArtifacts? = null,
) {

    /** Captures the whole framebuffer. */
    public fun screenshot(afterTick: Long?): AgentResult = capture(null, afterTick)

    /**
     * Captures a region, in framebuffer pixels from the bottom-left.
     *
     * An out-of-bounds or empty rectangle is a typed error that **names the clamped bounds**,
     * rather than a silent clamp: an agent that asked for 800x600 of a 640x480 framebuffer and got
     * a 640x480 image back would compare it against the wrong expectation and conclude the game
     * had changed.
     */
    public fun screenshotRegion(x: Int, y: Int, w: Int, h: Int, afterTick: Long?): AgentResult {
        val renderer = control ?: return unavailable()
        if (mode == RenderMode.Headless) return unavailable()
        val width = renderer.framebufferWidth
        val height = renderer.framebufferHeight
        if (w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > width || y + h > height) {
            return AgentResult.failed(
                dev.wildware.udea.agent.AgentErrorKind.BAD_ARGUMENT,
                "screenshot_region($x, $y, $w, $h) is not inside the framebuffer; the largest " +
                    "region here is (0, 0, $width, $height)",
            )
        }
        return capture(PixelRegion(x, y, w, h), afterTick)
    }

    /** Points the camera. */
    public fun setCamera(x: Float, y: Float, zoom: Float): AgentResult {
        val renderer = control ?: return unavailable()
        if (mode == RenderMode.Headless) return unavailable()
        renderer.setCamera(x, y, zoom)
        return AgentResult.ok {
            put("x", x)
            put("y", y)
            put("zoom", zoom)
        }
    }

    /** Follows an entity, or stops following when [netId] is negative. */
    public fun followEntity(netId: Long): AgentResult {
        val renderer = control ?: return unavailable()
        if (mode == RenderMode.Headless) return unavailable()
        val target = netId.takeIf { it >= 0 }
        renderer.followEntity(target)
        return AgentResult.ok { put("following", target ?: -1L) }
    }

    /** Turns debug draw on, off, or over. */
    public fun toggleDebugDraw(enabled: Boolean?): AgentResult {
        val renderer = control ?: return unavailable()
        if (mode == RenderMode.Headless) return unavailable()
        return AgentResult.ok { put("debugDraw", renderer.toggleDebugDraw(enabled)) }
    }

    private fun capture(region: PixelRegion?, afterTick: Long?): AgentResult {
        val renderer = control ?: return unavailable()
        if (mode == RenderMode.Headless) return unavailable()
        val store = artifacts ?: return AgentResult.failed(
            AgentHostErrors.NO_ARTIFACT_STORE,
            "this instance has no artifact store, so a capture has nowhere to go",
        )
        return when (val outcome = renderer.capture(region, afterTick)) {
            is CaptureOutcome.Unavailable -> AgentResult.failed(
                // The renderer's own token, not a translated one: `RenderUnavailable.code` is
                // stable across renames precisely so that it can be reported verbatim.
                dev.wildware.udea.agent.AgentErrorKind(outcome.reason.code),
                "the renderer could not capture a frame: ${outcome.reason.code}",
            )

            is CaptureOutcome.Captured -> {
                val id = store.put(outcome.image, AgentArtifacts.PNG)
                    ?: return AgentResult.failed(
                        AgentHostErrors.NO_ARTIFACT_STORE,
                        "the capture succeeded but could not be written to ${store.root}",
                    )
                val artifact = store.get(id)
                // Path first, id second: the path is ~10 tokens and covers the same-machine case,
                // and the id is what a remote agent hands to GET /artifact. Bytes never travel in
                // a digest.
                AgentResult.ok {
                    put("artifactId", id.value)
                    put("path", artifact?.path?.toString())
                    put("w", region?.w ?: renderer.framebufferWidth)
                    put("h", region?.h ?: renderer.framebufferHeight)
                    put("tick", afterTick ?: -1L)
                }
            }
        }
    }

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
}

/**
 * The tools this module publishes, as a [ToolModule].
 *
 * Hand-written rather than emitted by KSP, for the reason [CompareArtifactsTool] gives. It is
 * **not** a `ServiceLoader` entry: a host registers it explicitly, because the toolsets it names
 * need constructor arguments the host owns - the artifact store, the render mode, the renderer -
 * and a service entry cannot be handed any of them.
 *
 * ```
 * val index = ToolIndex.builder()
 *     .discover()                                    // the generated modules
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
    override val inputSchema: String,
) : AgentToolDef<RenderToolset> {
    override val owner: KClass<*> = RenderToolset::class
}

/** `render.screenshot`. */
public object ScreenshotTool : RenderToolDef(
    name = "render.screenshot",
    description = "Capture the whole frame as a PNG and file it in the artifact store. Reach for " +
        "it whenever a number cannot tell you what the screen looks like - before and after a " +
        "command, or either side of a rewind - and pass the two ids to render.compare_artifacts. " +
        "Returns the file path (open it directly if you are on this machine) and an artifact id " +
        "(fetch it with GET /artifact if you are not). Answers no_render_context in Headless.",
    args = listOf(
        AgentToolArg(
            "afterTick",
            "integer",
            "Capture the first frame rendered after this simulation tick, making the capture " +
                "reproducible. Omit to capture the next frame.",
            required = false,
            default = "",
        ),
    ),
    inputSchema = """{"type":"object","properties":{"afterTick":{"type":"integer","description":""" +
        """"Capture the first frame after this tick. Omit for the next frame."}},"required":[]}""",
) {
    override fun invoke(receiver: RenderToolset, command: AgentCommand): Any? =
        receiver.screenshot(if ("afterTick" in command) command.long("afterTick") else null)
}

/** `render.screenshot_region`. */
public object ScreenshotRegionTool : RenderToolDef(
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
        AgentToolArg(
            "afterTick",
            "integer",
            "Capture the first frame rendered after this tick. Omit for the next frame.",
            required = false,
            default = "",
        ),
    ),
    inputSchema = """{"type":"object","properties":{"x":{"type":"integer","description":"Left edge."},""" +
        """"y":{"type":"integer","description":"Bottom edge."},"w":{"type":"integer","description":"Width."},""" +
        """"h":{"type":"integer","description":"Height."},"afterTick":{"type":"integer",""" +
        """"description":"Capture after this tick."}},"required":["x","y","w","h"]}""",
) {
    override fun invoke(receiver: RenderToolset, command: AgentCommand): Any? =
        receiver.screenshotRegion(
            x = command.int("x"),
            y = command.int("y"),
            w = command.int("w"),
            h = command.int("h"),
            afterTick = if ("afterTick" in command) command.long("afterTick") else null,
        )
}

/** `render.set_camera`. */
public object SetCameraTool : RenderToolDef(
    name = "render.set_camera",
    description = "Move the camera to a world position and zoom level. Reach for it when what you " +
        "want to look at is off screen, or too small to judge, before taking a screenshot. It " +
        "changes presentation only and cannot affect the simulation. Answers no_render_context in " +
        "Headless.",
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
    inputSchema = """{"type":"object","properties":{"x":{"type":"number","description":"World x."},""" +
        """"y":{"type":"number","description":"World y."},"zoom":{"type":"number",""" +
        """"description":"Zoom factor, default 1."}},"required":["x","y"]}""",
) {
    override fun invoke(receiver: RenderToolset, command: AgentCommand): Any? =
        receiver.setCamera(command.float("x"), command.float("y"), command.float("zoom", 1f))
}

/** `render.follow_entity`. */
public object FollowEntityTool : RenderToolDef(
    name = "render.follow_entity",
    description = "Keep the camera on one entity by network id, so it stays framed as it moves. " +
        "Reach for it before stepping the simulation forward to watch what one unit does. Pass -1 " +
        "to stop following and leave the camera where it is. Answers no_render_context in Headless.",
    args = listOf(
        AgentToolArg(
            "netId",
            "integer",
            "Network id of the entity to follow, as reported by an entity query. -1 stops following.",
            required = true,
            default = null,
        ),
    ),
    inputSchema = """{"type":"object","properties":{"netId":{"type":"integer","description":""" +
        """"Entity network id to follow; -1 stops following."}},"required":["netId"]}""",
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
            default = "",
        ),
    ),
    inputSchema = """{"type":"object","properties":{"enabled":{"type":"boolean","description":""" +
        """"true shows the overlay, false hides it; omit to toggle."}},"required":[]}""",
) {
    override fun invoke(receiver: RenderToolset, command: AgentCommand): Any? =
        receiver.toggleDebugDraw(if ("enabled" in command) command.bool("enabled") else null)
}
