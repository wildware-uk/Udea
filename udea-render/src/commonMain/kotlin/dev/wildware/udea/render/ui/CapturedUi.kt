package dev.wildware.udea.render.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.fabmax.kool.pipeline.RenderPass
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.udea.render.FrameClock
import dev.wildware.udea.render.RenderResource
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.kool.ScenePasses
import dev.wildware.composegl.kool.KoolBackend as ComposeGlBackend

/**
 * A ComposeGL screen drawn **into the frame an agent captures**: the game's heads-up display.
 *
 * ```kotlin
 * class HudSystem(resources: RenderResources, fonts: UiFonts) : RenderSystem {
 *     private val ui = resources.capturedUi(fonts)
 *     init { ui.show(HudScreen(state)) }
 *     ...
 * }
 * ```
 *
 * ## Why this exists beside [UiLayer]
 *
 * They answer opposite questions. [UiLayer] is a menu, an editor panel, anything that is *about* the
 * game rather than *in* it, and it draws into the window after the presented frame so that no
 * capture can ever see it. A HUD is the other kind: an agent that takes a screenshot to see whether
 * an ability is cooling needs the cooldown in the picture, exactly as a player needs it on the
 * screen. Issue #188 moved the HUD to ComposeGL, and a HUD in a [UiLayer] would have vanished from
 * every capture. So this draws into the capturable pass, and `GlCapturedUiTest` reads it back out of
 * a capture the way `GlUiLayerTest` reads [UiLayer]'s absence.
 *
 * ## Where it draws
 *
 * Into a second view of the capturable `OffscreenPass2d`, added by `ScenePasses.addOnTop`. Kool
 * renders a pass's views in order into one framebuffer, so by the time this view is reached every
 * `RenderSystem` has already drawn into it, and the toolkit's frame - which draws into "whatever the
 * host has bound" - lands on top of the whole frame. The toolkit composites its own premultiplied
 * colour, which is why this is not a texture drawn back through the sprite batch: that batch blends
 * straight alpha, and every translucent panel would come out darker than the toolkit meant.
 *
 * It is above every `RenderSystem`, in every phase, by construction: the view is drawn after the
 * batch, not at a point in the batch. What is drawn over it is nothing that reaches a capture - the
 * agent overlay and a [UiLayer] both draw into the window.
 *
 * ## It reads no input
 *
 * Nothing here listens to a pointer or a key. A HUD shows state; input reaches the simulation through
 * `IntentState`, and a screen that wants to be clicked is a [UiLayer]'s. That is also why it needs no
 * render-thread input hand-off of its own.
 *
 * ## Lifetime
 *
 * Made by [RenderResources.capturedUi] on the render thread, owned by the pipeline from then on, and
 * released with it. The [UiFonts] are the caller's, and must outlive it - register them with
 * [RenderResources.own] *before* asking for this, so reverse-order release closes them after.
 */
public class CapturedUi internal constructor(
    private val passes: ScenePasses,
    fonts: UiFonts,
    width: Int,
    height: Int,
) : RenderResource {

    /** The toolkit's frame time. Presentation only: nothing here animates, and nothing simulates. */
    private val clock: FrameClock = FrameClock.Wall

    /** What is shown, as Compose state, so [show] is a plain setter safe from any thread. */
    private var screen: UiScreen? by mutableStateOf(null)

    private val backend = ComposeGlBackend(fonts.atlas)

    private val host = UiHost()

    private val renderer = UiRenderer(host, backend.canvas)

    /**
     * The capture's own pixels as the design size, one to one: a HUD is laid out in the pixels an
     * agent's screenshot has, whatever the window was dragged to.
     */
    private val viewport = Viewport(
        design = Size(width.toFloat(), height.toFloat()),
        physical = Size(width.toFloat(), height.toFloat()),
    )

    private val view: RenderPass.View

    init {
        host.setContent { ProvideFonts(backend.fonts) { screen?.content() } }
        view = passes.addOnTop(VIEW_NAME, ::draw)
    }

    /** Shows [screen] in every frame from the next one, or nothing when it is `null`. */
    public fun show(screen: UiScreen?) {
        this.screen = screen
    }

    /** One frame, with the capturable pass bound. Render thread only: Kool calls it. */
    private fun draw() {
        renderer.render(viewport, clock.nanoTime())
    }

    /** Takes the view off the pass, then lets go of the composition and the canvas. Render thread. */
    override fun release() {
        passes.removeOnTop(view)
        host.dispose()
        backend.close()
    }

    override fun toString(): String = "CapturedUi(${viewport.design.width}x${viewport.design.height})"

    private companion object {
        const val VIEW_NAME: String = "udea-captured-ui"
    }
}
