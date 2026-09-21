package dev.wildware.udea.render.sky

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.render.FrameSurface
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderPipeline
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.RenderTargets
import dev.wildware.udea.render.ScreenTarget
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteBatch2D
import dev.wildware.udea.render.draw.SpriteRecord
import dev.wildware.udea.render.draw.SpriteTexture
import dev.wildware.udea.render.support.ManualFrameClock
import dev.wildware.udea.render.support.testTargets
import dev.wildware.udea.render.view.EditorCamera
import dev.wildware.udea.render.view.WorldViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The sky at the pipeline, with no render context (issue #267): what the capturable record holds
 * after a frame with each kind of sky, and with none.
 *
 * A record is exactly what the capturable pass draws - one Kool node reads one record, in order - so
 * "the sky is the first draw and covers the frame" here is "the sky is under everything and fills
 * the picture" there. That Kool then shows those colours in a capture is `GlSkyTest`'s half, and
 * `runSkyProof`'s.
 */
class SkyTest {

    private val ctx: GameContext = testGameContext(seed = 267L)
    private val world: World = configureWorld { injectables { gameContext(ctx) } }
    private val white = SpriteTexture.whitePixel("sky-test-white")
    private val capturable = SpriteBatch2D(white)

    /** Clears the capturable batch at the top of each frame, as `KoolSurface.begin` does. */
    private val targets: RenderTargets = testTargets(
        batch = capturable,
        surface = object : FrameSurface {
            override fun begin() = capturable.clear()
            override fun endAndPresent(screen: ScreenTarget) = Unit
            override fun resize(width: Int, height: Int) = Unit
        },
        width = WIDTH,
        height = HEIGHT,
    )
    private val registry = RenderRegistry(ManualFrameClock())
    private val pipeline: RenderPipeline = registry.run {
        register(RenderPhase.PreRender, { resources -> Marker(resources) })
        build(world, ctx, targets)
    }

    @Test
    fun `with no sky set a frame records the game's draws and nothing else`() {
        assertEquals(SkyBackground.None, registry.sky.background, "a game that sets no sky has none")

        pipeline.render(0f)

        // The whole of criterion 2 at this level: not a black quad over a black clear, which would
        // look the same and would still be a change, but no draw at all.
        assertEquals(listOf(MARKER.packed), draws(capturable.home).map { it.tint })
        assertEquals(1, capturable.runCount)
    }

    @Test
    fun `a solid sky is the first draw of the frame, covers all of it, and is its colour`() {
        registry.sky.background = SkyBackground.Solid(DAY)

        pipeline.render(0f)

        val frame = draws(capturable.home)
        assertEquals(listOf(DAY.packed, MARKER.packed), frame.map { it.tint }, "the sky is not under the game's draws")
        frame.first().assertCovers(WIDTH, HEIGHT)
        assertSame(white, capturable.runTexture(0), "a solid sky is the batch's white texel, tinted")
    }

    @Test
    fun `a gradient sky covers the frame with a texture running from its top colour to its bottom one`() {
        registry.sky.background = SkyBackground.Gradient(top = NIGHT, bottom = DUSK)

        pipeline.render(0f)

        val frame = draws(capturable.home)
        assertEquals(listOf(Rgba.WHITE.packed, MARKER.packed), frame.map { it.tint })
        frame.first().assertCovers(WIDTH, HEIGHT)
        // Sampled top row first, as every SpriteTexture is: v runs 0 at the top to 1 at the bottom.
        assertEquals(0f, frame.first().v0)
        assertEquals(1f, frame.first().dv)

        val rows = rowsOf(capturable.runTexture(0))
        assertEquals(NIGHT, rows.first(), "the top row is not the top colour")
        assertEquals(DUSK, rows.last(), "the bottom row is not the bottom colour")
        // Half-way down is half-way between, to within the one level a byte can round by.
        val middle = rows[(rows.size - 1) / 2]
        assertNear(NIGHT.r + (DUSK.r - NIGHT.r) / 2f, middle.r)
        assertNear(NIGHT.g + (DUSK.g - NIGHT.g) / 2f, middle.g)
        assertNear(NIGHT.b + (DUSK.b - NIGHT.b) / 2f, middle.b)
        // And every channel only ever moves one way, towards the bottom colour: the texture is a
        // blend, not a band or a wrap-around.
        for (row in 1 until rows.size) {
            for ((name, channel) in listOf<Pair<String, (Rgba) -> Float>>("red" to { it.r }, "green" to { it.g }, "blue" to { it.b })) {
                val step = channel(rows[row]) - channel(rows[row - 1])
                val towards = channel(DUSK) - channel(NIGHT)
                assertTrue(step * towards >= 0f, "row $row's $name moves away from the bottom colour")
            }
        }
    }

    @Test
    fun `a sky changed between frames shows on the next frame, and None takes it away again`() {
        registry.sky.background = SkyBackground.Solid(DAY)
        pipeline.render(0f)
        registry.sky.background = SkyBackground.Solid(NIGHT)
        pipeline.render(0f)
        assertEquals(listOf(NIGHT.packed, MARKER.packed), draws(capturable.home).map { it.tint })

        registry.sky.background = SkyBackground.None
        pipeline.render(0f)
        assertEquals(listOf(MARKER.packed), draws(capturable.home).map { it.tint })
    }

    @Test
    fun `a gradient's texture is made once, and replaced and released only when the gradient changes`() {
        registry.sky.background = SkyBackground.Gradient(top = NIGHT, bottom = DUSK)
        pipeline.render(0f)
        val first = capturable.runTexture(0)
        pipeline.render(0f)
        assertSame(first, capturable.runTexture(0), "the same gradient was made again on the next frame")
        // An equal value set again is the same sky, not a new one.
        registry.sky.background = SkyBackground.Gradient(top = NIGHT, bottom = DUSK)
        pipeline.render(0f)
        assertSame(first, capturable.runTexture(0), "an equal gradient was made again")
        assertNotNull(first.peekRgba(), "the gradient in use was released")

        registry.sky.background = SkyBackground.Gradient(top = DAY, bottom = DUSK)
        pipeline.render(0f)
        val second = capturable.runTexture(0)
        assertNotSame(first, second)
        assertEquals(DAY, rowsOf(second).first())
        assertNull(first.peekRgba(), "the gradient no longer in use was not released")

        pipeline.dispose()
        assertNull(second.peekRgba(), "disposing the pipeline did not release the sky's texture")
    }

    @Test
    fun `an editor's Scene view draws the sky under the world, and its gizmos over both`() {
        registry.sky.background = SkyBackground.Solid(DUSK)
        val record = SpriteRecord()
        val scene = WorldViewport(
            camera = EditorCamera(),
            target = OffscreenTarget(VIEW_WIDTH, VIEW_HEIGHT),
            record = record,
            batch = SpriteBatch2D(SpriteTexture.whitePixel("sky-test-view-white"), home = record),
            frame = null,
            captures = null,
            kool = null,
        )
        pipeline.open(scene)

        pipeline.render(0f)

        val drawn = draws(scene.record)
        assertEquals(listOf(DUSK.packed, MARKER.packed), drawn.map { it.tint })
        // The view's own size, not the capturable frame's: the tab is not the frame.
        drawn.first().assertCovers(VIEW_WIDTH, VIEW_HEIGHT)
    }

    // --- fixture -------------------------------------------------------------------------

    /** One draw as the record holds it. */
    private class Draw(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val v0: Float,
        val dv: Float,
        val tint: Int,
    ) {
        fun assertCovers(frameWidth: Int, frameHeight: Int) {
            assertEquals(0f, x, "the sky does not start at the left edge")
            assertEquals(0f, y, "the sky does not start at the bottom edge")
            assertEquals(frameWidth.toFloat(), width, "the sky is not the frame's width")
            assertEquals(frameHeight.toFloat(), height, "the sky is not the frame's height")
        }
    }

    private fun draws(record: SpriteRecord): List<Draw> = (0 until record.instanceCount).map { index ->
        val at = index * SpriteBatch2D.FLOATS_PER_INSTANCE
        val f = record.floats
        Draw(
            f[at + SpriteBatch2D.X],
            f[at + SpriteBatch2D.Y],
            f[at + SpriteBatch2D.WIDTH],
            f[at + SpriteBatch2D.HEIGHT],
            f[at + SpriteBatch2D.V0],
            f[at + SpriteBatch2D.DV],
            record.tints[index],
        )
    }

    /** The texture's rows, top first, as colours: it is one texel wide. */
    private fun rowsOf(texture: SpriteTexture): List<Rgba> {
        val rgba = checkNotNull(texture.peekRgba()) { "$texture has no pixels to read" }
        assertEquals(1, texture.width, "a vertical gradient needs one column")
        return (0 until texture.height).map { row ->
            val at = row * 4
            Rgba(
                ((rgba[at].toInt() and 0xFF) shl 24) or
                    ((rgba[at + 1].toInt() and 0xFF) shl 16) or
                    ((rgba[at + 2].toInt() and 0xFF) shl 8) or
                    (rgba[at + 3].toInt() and 0xFF),
            )
        }
    }

    private fun assertNear(expected: Float, actual: Float) {
        assertTrue(kotlin.math.abs(expected - actual) <= ONE_LEVEL, "expected $expected, was $actual")
    }

    /** A small square in the frame's pixels: the game drawing something. */
    private class Marker(private val resources: RenderResources) : RenderSystem {
        override fun render(target: OffscreenTarget, alpha: Float) {
            val batch = resources.batch
            batch.beginPixels()
            batch.fill(10f, 10f, 4f, 4f, MARKER)
            batch.end()
        }
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 180
        const val VIEW_WIDTH = 200
        const val VIEW_HEIGHT = 150
        const val ONE_LEVEL = 1f / 255f

        val MARKER = Rgba.of(1f, 0f, 0f)
        val DAY = Rgba.of(0.4f, 0.65f, 0.95f)
        val NIGHT = Rgba.of(0.02f, 0.03f, 0.12f)
        val DUSK = Rgba.of(0.95f, 0.55f, 0.3f)
    }
}
