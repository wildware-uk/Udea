package dev.wildware.udea.render.control

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.render.RenderPipeline
import dev.wildware.udea.render.camera.CameraRig
import dev.wildware.udea.render.capture.CaptureRegion
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.CaptureResult
import dev.wildware.udea.render.draw.DebugDraw
import java.util.concurrent.CompletableFuture

/**
 * Everything a live renderer can be asked to do from outside the render thread, and nothing else.
 *
 * ## What this is for
 *
 * The agent's `render` toolset — `screenshot`, `screenshot_region`, `set_camera`,
 * `follow_entity`, `toggle_debug_draw` — is declared in `udea-agent-host` against a port called
 * `RenderControl`. This is the presentation-side half of that pair: the real framebuffer, the
 * real capture queue, the real camera and the real debug switch.
 *
 * ## Why this class does not implement `RenderControl` directly
 *
 * It cannot, and the reason is a release gate rather than taste. `ReleaseRules.CLASSPATH_RULE`
 * (`UDEA-REL-002`) fails any release build whose runtime classpath resolves `:udea-agent-host`,
 * because the agent surface mutates the live simulation over loopback HTTP and spec 4 requires it
 * *absent* from a shipped game rather than merely disabled. `moba` depends on `udea-render`, so a
 * dependency from here onto `udea-agent-host` would drag the agent host into every shipped
 * classpath and make that gate impossible to pass. The arrow the other way is equally barred:
 * `udea-agent-host` is a headless module (`UDEA-MG-002`), and `RenderModuleGraphTest` fails if any
 * headless module's bytecode so much as names a `udea.render` type.
 *
 * So the adapter — a dozen lines that implement `RenderControl` by delegating here — belongs to
 * whoever assembles a host out of both, which is the game module or the launcher, and is the one
 * place allowed to see both sides. `udea-agent-host`'s `OffscreenRenderControl` (test sources, and
 * the Phase 1 demo's composition root) is that adapter today. **Be blunt about what that means:
 * until `UdeaAgentPlugin` has a plugin id and a `moba` run task, no *shipped* main-source class
 * wires these two together — the wiring exists and is executed, but from a demo entry point.**
 *
 * ## Threading
 *
 * Every method here is safe to call from any thread, and none of them blocks. That is not a
 * nicety: on an `Offscreen` or `Windowed` host the simulation thread **is** the render thread, so
 * a tool that waited for the next frame would be waiting for itself. [capture] hands back a
 * future the pipeline settles at the capture point of a frame it is about to draw; the camera and
 * the debug switch are consumed at a frame boundary by the systems that own them.
 */
public class PresentationControl(
    /** The live pipeline. Its capture slot and its offscreen target are what this exposes. */
    private val pipeline: RenderPipeline,
    /** The camera rig, or `null` for a host that draws with a fixed projection. */
    private val camera: CameraRig? = null,
    /** The shared debug switch, or `null` for a host with no debug renderers. */
    private val debug: DebugDraw? = null,
) {

    /** Width of every full-frame capture, in pixels. The framebuffer's, never the window's. */
    public val framebufferWidth: Int get() = pipeline.offscreen.width

    /** Height of every full-frame capture, in pixels. */
    public val framebufferHeight: Int get() = pipeline.offscreen.height

    /** True when this renderer can actually read pixels back. False leaves capture unavailable. */
    public val capturable: Boolean get() = pipeline.capture != null

    /**
     * Queues a capture and returns the future the render thread settles.
     *
     * @param region the rectangle to read, in framebuffer pixels from the bottom-left, or `null`
     *   for the whole frame. Checked against the framebuffer at the capture point, so an
     *   out-of-range rectangle completes the future exceptionally rather than reading past the
     *   end of the surface.
     * @param afterTick serve only once this simulation tick has finished, or `null` for the next
     *   frame drawn. A tick still in the future leaves the request queued across frames — which
     *   is correct here and is precisely why the caller must not block on the result from the
     *   render thread.
     * @return a future completed with the frame, or completed exceptionally with
     *   `CaptureStalledException` if the pipeline is torn down first. Never `null`; a pipeline
     *   with no pixel source completes it exceptionally straight away, because "this renderer
     *   cannot read pixels" is an answer and a silent `null` is not.
     */
    public fun capture(
        region: CaptureRegion? = null,
        afterTick: Long? = null,
    ): CompletableFuture<CaptureResult> {
        val slot = pipeline.capture
            ?: return CompletableFuture<CaptureResult>().apply {
                completeExceptionally(
                    IllegalStateException(
                        "this pipeline was built with no PixelSource, so it cannot be captured; " +
                            "that is a host wiring fault, not a property of the render mode",
                    ),
                )
            }
        return slot.submit(CaptureRequest(region = region, afterTick = afterTick?.let(::Tick)))
    }

    /**
     * Places the camera and stops following. Applied at the top of the next frame.
     *
     * @throws IllegalArgumentException if the position is not finite or [zoom] is not positive.
     *   A caller that takes these from an agent argument should say so in its own vocabulary
     *   first; this is the backstop, not the validation.
     */
    public fun lookAt(x: Float, y: Float, zoom: Float) {
        camera?.requestLookAt(x, y, zoom)
    }

    /** Follows [netId], or stops following when it is `null`. Applied at the next frame. */
    public fun follow(netId: NetId?) {
        camera?.requestFollow(netId)
    }

    /**
     * Turns debug drawing on, off, or over, and reports the state that results.
     *
     * Reports the state even when there is no [DebugDraw] wired — `false`, because nothing is
     * drawing debug information and saying `true` would send an agent looking for shapes that
     * were never going to appear.
     */
    public fun toggleDebugDraw(enabled: Boolean?): Boolean = debug?.set(enabled) ?: false

    /** Whether a camera is wired at all. A host with none cannot honour `set_camera`. */
    public val hasCamera: Boolean get() = camera != null

    override fun toString(): String =
        "PresentationControl(${framebufferWidth}x$framebufferHeight, camera=${camera != null}, " +
            "debug=${debug != null}, capturable=$capturable)"
}
