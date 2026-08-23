package dev.wildware.udea.agent.host.render

import dev.wildware.udea.agent.host.CameraOutcome
import dev.wildware.udea.agent.host.CaptureFrame
import dev.wildware.udea.agent.host.PixelRegion
import dev.wildware.udea.agent.host.RenderControl
import dev.wildware.udea.agent.host.RenderToolset
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.render.camera.CameraOutcome as RenderCameraOutcome
import dev.wildware.udea.render.capture.CaptureRegion
import dev.wildware.udea.render.control.PresentationControl
import java.util.concurrent.Future

/**
 * The adapter that joins [RenderControl] - the port [RenderToolset] is declared against - to
 * `udea-render`'s [PresentationControl]. This class is the whole of the wiring, and it is
 * deliberately dull.
 *
 * ## Why it is here now, when it used to be in test sources
 *
 * It was written for the Phase 1 demo, proven against a real LWJGL3 context, and left in
 * `src/test` because `udea-agent-host` was in `ModuleGraphRules.HEADLESS_PROJECTS` and so could
 * not name a `udea.render` type from `src/main`. The consequence was not a headless agent host:
 * it was that **no shipped path could construct a `RenderControl` at all**, so every `render.*`
 * tool answered `no_render_context` on a real run, and each game that wanted pixels copied this
 * file into its own sources. `moba` had done exactly that.
 *
 * The ruling that moved it removed the contradiction rather than the duplication: spec 4 gives
 * this module the render toolset, and a module that owns a toolset owns its port's
 * implementation. `udea-render` still may not name `udea-agent-host` - `UDEA-REL-002` fails a
 * release build whose runtime classpath resolves the agent host, and every game depends on
 * `udea-render` - so the arrow points this way and only this way.
 *
 * ## Offscreen, in both GL modes
 *
 * The name is the *surface*, not the mode. A capture always reads the pipeline's
 * `OffscreenTarget`, in `RenderMode.Windowed` as much as in `RenderMode.Offscreen`; that is what
 * keeps the agent overlay out of a screenshot (spec 3.7). So one adapter serves both modes, and
 * `RenderMode.Headless` - which has no pipeline - passes `null` instead of one of these.
 */
public class OffscreenRenderControl(
    private val presentation: PresentationControl,
) : RenderControl {

    override val framebufferWidth: Int get() = presentation.framebufferWidth

    override val framebufferHeight: Int get() = presentation.framebufferHeight

    /**
     * Queues the capture and maps the renderer's result onto the port's.
     *
     * `thenApply` runs on whichever thread completes the future, which is the render thread at
     * the capture point. That is deliberate and it is why the mapping is four field reads: the
     * render thread must not be handed work here.
     */
    override fun capture(region: PixelRegion?): Future<CaptureFrame> =
        presentation
            .capture(region?.let { CaptureRegion(it.x, it.y, it.w, it.h) })
            .thenApply { frame ->
                CaptureFrame(frame.width, frame.height, frame.tick.value, frame.bytes)
            }

    override fun setCamera(x: Float, y: Float, zoom: Float): CameraOutcome =
        mapped(presentation.lookAt(x, y, zoom))

    override fun followEntity(netId: NetId?): CameraOutcome = mapped(presentation.follow(netId))

    /**
     * The one place the renderer's vocabulary becomes the port's.
     *
     * A `when` with no `else`, so a value added to either enum fails this file at compile time
     * rather than being folded into whichever branch was nearest. That matters more than usual
     * here: every value on both sides is a *reason a camera did not move*, and mapping a new one
     * onto `APPLIED` by accident would put the silent-success back exactly where it was.
     */
    private fun mapped(outcome: RenderCameraOutcome): CameraOutcome = when (outcome) {
        RenderCameraOutcome.APPLIED -> CameraOutcome.APPLIED
        RenderCameraOutcome.NO_CAMERA -> CameraOutcome.NO_CAMERA_BOUND
        RenderCameraOutcome.CAMERA_UNBOUND -> CameraOutcome.CAMERA_NOT_BOUND
        RenderCameraOutcome.UNKNOWN_ENTITY -> CameraOutcome.NO_SUCH_ENTITY
        RenderCameraOutcome.UNFOLLOWABLE -> CameraOutcome.NOT_FOLLOWABLE
    }

    override fun toggleDebugDraw(enabled: Boolean?): Boolean = presentation.toggleDebugDraw(enabled)

    override fun toString(): String = "OffscreenRenderControl($presentation)"
}
