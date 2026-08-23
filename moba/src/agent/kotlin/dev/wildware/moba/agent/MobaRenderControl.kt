package dev.wildware.moba.agent

import dev.wildware.udea.agent.host.CaptureFrame
import dev.wildware.udea.agent.host.PixelRegion
import dev.wildware.udea.agent.host.RenderControl
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.render.capture.CaptureRegion
import dev.wildware.udea.render.control.PresentationControl
import java.util.concurrent.Future

/**
 * Joins `udea-agent-host`'s [RenderControl] port to `udea-render`'s [PresentationControl].
 *
 * ## Why the adapter lives in a game and not in either engine module
 *
 * Neither side may name the other, and both rules are gates rather than conventions:
 *
 * - `udea-render` cannot depend on `udea-agent-host`, because `ReleaseRules.CLASSPATH_RULE`
 *   (`UDEA-REL-002`) fails any release build whose `runtimeClasspath` resolves the agent host,
 *   and every game depends on `udea-render`.
 * - `udea-agent-host` cannot depend on `udea-render`, because it is in
 *   `ModuleGraphRules.HEADLESS_PROJECTS` (`UDEA-MG-002`) and `RenderModuleGraphTest` fails if a
 *   headless module's bytecode so much as names a `udea.render` type.
 *
 * A composition root that sees both is therefore the only legal home, and a game is what a
 * composition root is. `src/agent` is that place in `moba`: it compiles against `main` and its
 * dependencies - which is where `udea-render` comes from - and against `udea-agent-host`, which
 * `UdeaAgentPlugin` adds to this source set and to nothing else. `runtimeClasspath` never
 * resolves the agent host, so `udeaVerifyRelease` still passes, and `jar` still packages `main`
 * alone, so none of this ships.
 *
 * ## Its relationship to the demo copy
 *
 * `udea-agent-host`'s test sources hold `OffscreenRenderControl`, which is the same mapping. That
 * one is a **fixture**: it exists so that module's own GL tests can drive the render toolset
 * without a game, and `OverlayCaptureIsolationTest` uses it to assert spec 3.7. This one is the
 * shipped path, reached by `:moba:run`. They are duplicated on purpose and the duplication is
 * real cost - stated here rather than hidden, because the alternative is a published test-fixture
 * artifact joining two modules the module graph deliberately keeps apart.
 */
public class MobaRenderControl(
    private val presentation: PresentationControl,
) : RenderControl {

    override val framebufferWidth: Int get() = presentation.framebufferWidth

    override val framebufferHeight: Int get() = presentation.framebufferHeight

    /**
     * Queues the capture and maps the renderer's result onto the port's.
     *
     * `thenApply` runs on whichever thread completes the future, which is the render thread at
     * the capture point. That is deliberate, and it is why the mapping is four field reads: the
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

    override fun toString(): String = "MobaRenderControl($presentation)"
}
