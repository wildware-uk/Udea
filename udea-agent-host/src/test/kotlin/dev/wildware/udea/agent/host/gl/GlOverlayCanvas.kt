package dev.wildware.udea.agent.host.gl

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.Matrix4
import dev.wildware.udea.agent.host.overlay.AgentOverlayView
import dev.wildware.udea.agent.host.overlay.EntityLocator
import dev.wildware.udea.agent.host.overlay.OverlayCanvas
import dev.wildware.udea.agent.host.overlay.WorldProjector
import dev.wildware.udea.render.OverlayResources
import dev.wildware.udea.render.OverlaySystem
import dev.wildware.udea.render.ScreenTarget

/**
 * A real GL implementation of [OverlayCanvas], and the `OverlaySystem` that drives
 * [AgentOverlayView] from it.
 *
 * ## Why this lives in a test source set, and what that admits
 *
 * `udea-agent-host` is in `ModuleGraphRules.HEADLESS_PROJECTS`: `udeaVerifyHeadless` fails the
 * build if any class compiled into its `src/main` names `com/badlogic/gdx/graphics/`, which is
 * where `Batch`, `BitmapFont` and `Texture` all live. That rule governs `main` only - the scan
 * walks `build/classes/<lang>/main` - so a **test** may see GL, and this one does.
 *
 * So this file is not the shipped adapter and does not pretend to be. **The shipped adapter
 * does not exist**: it belongs in `udea-render`, which this issue does not own, exactly as
 * `RenderControl`'s GL implementation does. What this file buys is not a substitute for it -
 * it is the proof that the port is sufficient for a real one and that the overlay code being
 * shipped cannot reach a capture. `OverlayCaptureIsolationTest` drives it against a real LWJGL3
 * context.
 *
 * ## The structural guarantee, visible in the constructor
 *
 * It takes [OverlayResources]. That type carries the [ScreenTarget] and the shared batch, and
 * **no capturable target at all** - there is deliberately no expression anywhere in this file
 * that could reach an `OffscreenTarget`, because there is nothing to reach it from. That is
 * spec 3.7's guarantee at the level a refactor can actually break, and `OverlayResourcesTest`
 * in `udea-render` asserts the type itself stays that shape.
 */
internal class AgentOverlaySystem(
    private val resources: OverlayResources,
    private val view: AgentOverlayView,
    private val projector: WorldProjector = AgentOverlayView.OFFSCREEN,
    private val locator: EntityLocator = EntityLocator.NONE,
) : OverlaySystem {

    private val font = resources.own(BitmapFont())

    private val pixel = TextureRegion(resources.own(whitePixel()))

    private val projection = Matrix4()

    private val layout = GlyphLayout()

    private val colour = Color()

    /** Set on each [render] so the canvas methods know the window they are drawing into. */
    private var target: ScreenTarget? = null

    private val canvas = object : OverlayCanvas {

        override val width: Float get() = (target?.width ?: 0).toFloat()

        override val height: Float get() = (target?.height ?: 0).toFloat()

        override val lineHeight: Float get() = font.lineHeight

        override fun measure(text: CharSequence): Float {
            layout.setText(font, text)
            return layout.width
        }

        override fun fill(x: Float, y: Float, w: Float, h: Float, rgba: Int) {
            tint(rgba)
            resources.batch.color = colour
            resources.batch.draw(pixel, x, y, w, h)
        }

        override fun text(x: Float, y: Float, text: CharSequence, rgba: Int) {
            tint(rgba)
            font.color = colour
            font.draw(resources.batch, text, x, y)
        }

        override fun ring(cx: Float, cy: Float, radius: Float, thickness: Float, rgba: Int) {
            // Drawn from quads rather than a ShapeRenderer: a second renderer would be a second
            // GL resource with a second lifetime, which is the arrangement `RenderTargets`
            // exists to remove. Segments are enough for a marker at this size.
            tint(rgba)
            resources.batch.color = colour
            var index = 0
            while (index < RING_SEGMENTS) {
                val angle = index * TWO_PI / RING_SEGMENTS
                resources.batch.draw(
                    pixel,
                    cx + radius * kotlin.math.cos(angle) - thickness / 2f,
                    cy + radius * kotlin.math.sin(angle) - thickness / 2f,
                    thickness,
                    thickness,
                )
                index++
            }
        }

        override fun cross(x: Float, y: Float, size: Float, thickness: Float, rgba: Int) {
            tint(rgba)
            resources.batch.color = colour
            resources.batch.draw(pixel, x - size / 2f, y - thickness / 2f, size, thickness)
            resources.batch.draw(pixel, x - thickness / 2f, y - size / 2f, thickness, size)
        }
    }

    override fun render(target: ScreenTarget, dtSeconds: Float) {
        this.target = target
        projection.setToOrtho2D(0f, 0f, target.width.toFloat(), target.height.toFloat())
        val batch = resources.batch
        batch.projectionMatrix = projection
        batch.begin()
        try {
            view.render(canvas, dtSeconds, projector, locator)
        } finally {
            batch.end()
            batch.color = Color.WHITE
            this.target = null
        }
    }

    /** Unpacks `0xRRGGBBAA` into the reused [colour]. */
    private fun tint(rgba: Int) {
        colour.set(
            ((rgba ushr 24) and 0xFF) / 255f,
            ((rgba ushr 16) and 0xFF) / 255f,
            ((rgba ushr 8) and 0xFF) / 255f,
            (rgba and 0xFF) / 255f,
        )
    }

    private companion object {

        /** Enough dots to read as a circle at an 18-pixel radius. */
        const val RING_SEGMENTS: Int = 48

        const val TWO_PI: Float = (2.0 * Math.PI).toFloat()

        /** A one-pixel white texture, tinted at the draw call. No asset pipeline needed. */
        fun whitePixel(): Texture = Texture(
            Pixmap(1, 1, Pixmap.Format.RGBA8888).apply {
                setColor(Color.WHITE)
                fill()
            },
        )
    }
}
