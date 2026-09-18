package dev.wildware.udea.render.overlay

import dev.wildware.udea.render.OverlayResources
import dev.wildware.udea.render.OverlaySystem
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.ScreenTarget
import dev.wildware.udea.render.draw.BitmapFont2D
import dev.wildware.udea.render.draw.Rgba
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Kool implementation of [OverlayCanvas], and the [OverlaySystem] that drives an
 * [OverlayContent] from it (issue #211; the agent activity overlay it was written for is spec
 * 3.7). Register it with [RenderRegistry.overlay]:
 *
 * ```
 * if (mode == RenderMode.Windowed) {
 *     registry.overlay { resources ->
 *         AgentOverlaySystem(resources, OverlayContent { canvas, dt, projector, locator ->
 *             view.render(canvas, dt, projector, locator)
 *         })
 *     }
 * }
 * ```
 *
 * ## The structural guarantee, still visible in the constructor
 *
 * It takes [OverlayResources]. That type carries the [ScreenTarget] and the shared batch, and
 * **no capturable target at all** - there is deliberately no expression anywhere in this file
 * that could reach an `OffscreenTarget`, because there is nothing to reach it from. That is
 * spec 3.7's guarantee at the level a refactor can actually break, and `OverlayResourcesTest`
 * asserts the type itself stays that shape. `GlOverlayIsolationTest` drives a synthetic overlay
 * against a real Kool context and asserts both halves: the window changes and every declared
 * capture route does not.
 *
 * ## Why this moved here from `udea-agent-host` (issue #211)
 *
 * The GL half of an overlay names batch and font types, and after the Kool port those types are
 * [dev.wildware.udea.render.draw.SpriteBatch2D] and [BitmapFont2D] - `udea-render`'s own, not
 * on any other module's public surface. `udea-agent-host` may still depend on `udea-render` (it
 * is exempt from `UDEA-MG-002` for the same reason `udea-render` is: it hosts the debug HTTP
 * server), but the arrow runs one way, so the adapter that draws with those types belongs on the
 * side that owns them. [OverlayContent] is the seam that lets it: the *content* -
 * `AgentOverlayView`, still in `udea-agent-host` because it reads `AgentBridge` - is agent state
 * this module must never depend on; the *pixels* are this module's alone.
 */
public class AgentOverlaySystem(
    private val resources: OverlayResources,
    private val content: OverlayContent,
    private val projector: WorldProjector = WorldProjector.NONE,
    private val locator: EntityLocator = EntityLocator.NONE,
) : OverlaySystem {

    private val font: BitmapFont2D = resources.own(BitmapFont2D.builtIn())

    /** Set on each [render] so the canvas methods know the window they are drawing into. */
    private var target: ScreenTarget? = null

    private val canvas = object : OverlayCanvas {

        override val width: Float get() = (target?.width ?: 0).toFloat()

        override val height: Float get() = (target?.height ?: 0).toFloat()

        override val lineHeight: Float get() = font.lineHeight

        override fun measure(text: CharSequence): Float = font.measure(text)

        override fun fill(x: Float, y: Float, w: Float, h: Float, rgba: Int) {
            resources.batch.fill(x, y, w, h, Rgba(rgba))
        }

        override fun text(x: Float, y: Float, text: CharSequence, rgba: Int) {
            font.draw(resources.batch, text, x, y, Rgba(rgba))
        }

        override fun ring(cx: Float, cy: Float, radius: Float, thickness: Float, rgba: Int) {
            // Drawn from filled squares rather than a shape renderer: a second renderer would be
            // a second GL resource with a second lifetime, which is the arrangement
            // `RenderTargets` exists to remove. Segments are enough for a marker at this size.
            val tint = Rgba(rgba)
            var index = 0
            while (index < RING_SEGMENTS) {
                val angle = index * TWO_PI / RING_SEGMENTS
                resources.batch.fill(
                    cx + radius * cos(angle) - thickness / 2f,
                    cy + radius * sin(angle) - thickness / 2f,
                    thickness,
                    thickness,
                    tint,
                )
                index++
            }
        }

        override fun cross(x: Float, y: Float, size: Float, thickness: Float, rgba: Int) {
            val tint = Rgba(rgba)
            resources.batch.fill(x - size / 2f, y - thickness / 2f, size, thickness, tint)
            resources.batch.fill(x - thickness / 2f, y - size / 2f, thickness, size, tint)
        }
    }

    override fun render(target: ScreenTarget, dtSeconds: Float) {
        this.target = target
        val batch = resources.batch
        batch.beginPixels()
        try {
            content.render(canvas, dtSeconds, projector, locator)
        } finally {
            batch.end()
            this.target = null
        }
    }

    override fun toString(): String = "AgentOverlaySystem($content)"

    private companion object {

        /** Enough dots to read as a circle at an 18-pixel radius. */
        const val RING_SEGMENTS: Int = 48

        const val TWO_PI: Float = (2.0 * kotlin.math.PI).toFloat()
    }
}
