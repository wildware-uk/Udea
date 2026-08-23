package dev.wildware.udea.agent.host.overlay

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.Matrix4
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.render.OverlayResources
import dev.wildware.udea.render.OverlaySystem
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.ScreenTarget

/**
 * The GL implementation of [OverlayCanvas], and the [OverlaySystem] that drives
 * [AgentOverlayView] from it. Register it with [RenderRegistry.overlay]:
 *
 * ```
 * if (mode == RenderMode.Windowed) {
 *     registry.overlay { resources -> AgentOverlaySystem(resources, view) }
 * }
 * ```
 *
 * ## Why it is here now, when it used to be in test sources
 *
 * `AgentOverlayView` is the layout, the ordering, the verbosity gate and the per-frame budget,
 * and it is deliberately GL-free. Something has to turn it into an `OverlaySystem`, and until
 * the ruling that moved this file there was nowhere legal to put that something:
 * `udea-agent-host` could not name a `udea.render` type, and `udea-render` may not name
 * `udea-agent-host` (`UDEA-REL-002`). So this adapter lived in **test** sources, which meant the
 * shipped overlay was drawn by nothing but its own tests - the panel spec 3.7 describes existed
 * and no human could see it.
 *
 * ## The structural guarantee, still visible in the constructor
 *
 * It takes [OverlayResources]. That type carries the [ScreenTarget] and the shared batch, and
 * **no capturable target at all** - there is deliberately no expression anywhere in this file
 * that could reach an `OffscreenTarget`, because there is nothing to reach it from. That is
 * spec 3.7's guarantee at the level a refactor can actually break, and `OverlayResourcesTest`
 * in `udea-render` asserts the type itself stays that shape. `OverlayCaptureIsolationTest`
 * drives this class against a real LWJGL3 context and asserts both halves: the window changes
 * and every declared capture route does not.
 *
 * ## Windowed only, and it is not this class that enforces it
 *
 * [AgentOverlayView.isEnabled] is false outside [RenderMode.Windowed] and its `render` returns
 * immediately, so an instance registered in the wrong mode draws nothing. A composition root
 * should still not register one outside [RenderMode.Windowed] - a begun-and-ended batch and a
 * `BitmapFont` per process are not free - and `MobaEntry.runWithGl` refuses to.
 */
public class AgentOverlaySystem(
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

/**
 * The real overlay hotkey: the physical key, read through LWJGL3's keyboard.
 *
 * [OverlayVerbosity]'s KDoc says this implementation "is `Gdx.input.isKeyPressed` in
 * `udea-render`". It could not be written anywhere until this module was allowed to name a GL
 * type, so [HardwareKeyState.NEVER] was the only value any composition root could pass and the
 * verbosity control was a switch nothing could move. This is the one hop it describes, with
 * nothing agent-writable on it: `Gdx.input` is the LWJGL3 device state, not the game's input
 * mapping, so no `input.*` tool and no injected intent source can reach it (issue #161).
 *
 * ## The key, and the fact that nothing specifies it
 *
 * Spec 3.7 describes the overlay and its verbosity levels and never names a key. [KEY] is
 * therefore a **choice made here**, not a contract being honoured: `F9`, because it is outside
 * every key a game is likely to bind and outside the `F1`-`F4` range window managers claim.
 * Change it freely; nothing depends on the value.
 *
 * ## What is not covered by an automated test
 *
 * Nothing can press a key. `OverlayHotkeyIsHardwareTest` drives [OverlayVerbosityControl]
 * through a fake [HardwareKeyState] and proves the edge detection and the injected/hardware
 * separation; what this class adds is the two-line binding to `Gdx.input`, and its only
 * coverage is that a Windowed instance constructs it and does not crash. Said plainly because
 * "the hotkey works" is not something the suite establishes.
 */
public class GdxOverlayKey(
    /** The key sampled, as a `com.badlogic.gdx.Input.Keys` code. */
    private val key: Int = KEY,
) : HardwareKeyState {

    /**
     * `false` when there is no input device rather than throwing.
     *
     * `Gdx.input` is null until a backend has started and is null again after it stops, and an
     * overlay polling on a frame either side of that would take down the render thread over a
     * key nobody pressed.
     */
    override fun isOverlayKeyDown(): Boolean =
        com.badlogic.gdx.Gdx.input?.isKeyPressed(key) ?: false

    override fun toString(): String = "GdxOverlayKey($key)"

    public companion object {

        /** `F9`. See the class KDoc: chosen here, specified nowhere. */
        public const val KEY: Int = com.badlogic.gdx.Input.Keys.F9
    }
}
