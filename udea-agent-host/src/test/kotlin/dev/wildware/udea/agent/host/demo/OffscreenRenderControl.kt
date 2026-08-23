package dev.wildware.udea.agent.host.demo

import dev.wildware.udea.agent.host.CaptureFrame
import dev.wildware.udea.agent.host.PixelRegion
import dev.wildware.udea.agent.host.RenderControl
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.render.capture.CaptureRegion
import dev.wildware.udea.render.control.PresentationControl
import java.util.concurrent.Future

/**
 * The adapter that joins `udea-agent-host`'s [RenderControl] port to `udea-render`'s
 * [PresentationControl]. This class is the whole of the wiring, and it is deliberately dull.
 *
 * ## Why it lives here rather than in either module
 *
 * Neither side may name the other. `udea-render` cannot depend on `udea-agent-host` because
 * `ReleaseRules.CLASSPATH_RULE` (`UDEA-REL-002`) fails any release build whose runtime classpath
 * resolves the agent host, and `moba` depends on `udea-render`; `udea-agent-host` cannot depend on
 * `udea-render` because it is a headless module and `RenderModuleGraphTest` fails if a headless
 * module's bytecode so much as names a `udea.render` type. A composition root that sees both is
 * therefore the only legal place, and a game or a launcher is what a composition root is.
 *
 * **What that means today, stated plainly:** the composition root is this demo, in test sources.
 * `UdeaAgentPlugin` has no plugin id and `moba` has no run task, so no *shipped* main-source class
 * assembles an offscreen agent host yet. The adapter is real, executed and covered by
 * `OffscreenRenderToolsetTest`; where it is instantiated from is the gap, and it is somebody
 * else's issue this wave.
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
    override fun capture(region: PixelRegion?, afterTick: Long?): Future<CaptureFrame> =
        presentation
            .capture(region?.let { CaptureRegion(it.x, it.y, it.w, it.h) }, afterTick)
            .thenApply { frame ->
                CaptureFrame(frame.width, frame.height, frame.tick.value, frame.bytes)
            }

    override fun setCamera(x: Float, y: Float, zoom: Float) {
        presentation.lookAt(x, y, zoom)
    }

    override fun followEntity(netId: NetId?) {
        presentation.follow(netId)
    }

    override fun toggleDebugDraw(enabled: Boolean?): Boolean = presentation.toggleDebugDraw(enabled)

    override fun toString(): String = "OffscreenRenderControl($presentation)"
}
