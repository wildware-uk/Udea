package dev.wildware.udea.render.gl

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.loop.BarrierAction
import dev.wildware.udea.core.loop.barrier
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderModule
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.capture
import dev.wildware.udea.render.draw.SpriteTexture
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelLight
import dev.wildware.udea.render.model.ModelMaterial
import dev.wildware.udea.render.model.ModelMesh
import dev.wildware.udea.render.model.ModelRenderSystem
import dev.wildware.udea.render.model.ModelRenderer
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A model whose `Transform3D` jumped between two ticks is drawn *between* them at a fractional
 * render alpha, through a real Kool context (issue #246).
 *
 * The jump is made the way a client receives one: a `SimBarrier` action writes the new position
 * and heading, drained at the top of the next tick - which is what a snapshot apply is. The world
 * runs `RenderModule`, so the pose recorder is the one a game gets. The loop is paused and ticks
 * only when this test says, and the frame's alpha is pinned by [PinnedAlpha], so every picture is
 * a known tick at a known alpha.
 *
 * What is read back is the column the cube's pixels centre on. Drawn between the two ticks it
 * walks steadily from the left pose to the right one as alpha rises; drawn where the transform
 * stands, which is what `ModelRenderSystem` did before this issue, every frame shows it on the
 * right. Writes `model-interp-alpha-<nnn>.png` beside the other GL frames.
 */
class GlModelInterpolationTest {

    @Test
    fun `a model is drawn between the last two ticks at the render alpha`() {
        GlAvailability.require()
        val pinned = arrayOfNulls<PinnedAlpha>(1)
        val registry = RenderRegistry()
        val camera = ModelCamera().apply { lookAt(0f, -9f, 2.5f, 0f, 0f, 0.6f) }
        val light = ModelLight(directionX = 0.6f, directionY = 0.8f, directionZ = -0.6f)
        registry.register(RenderPhase.World, { resources ->
            PinnedAlpha(ModelRenderSystem(resources, camera, light)).also { pinned[0] = it }
        })
        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-model-interp-test",
                windowWidth = WIDTH,
                windowHeight = HEIGHT,
                renderWidth = WIDTH,
                renderHeight = HEIGHT,
            ),
            registry,
        )
        try {
            val host = GameHost(
                RenderMode.Offscreen,
                UdeaGameDef(registry = CoreUdeaRegistry, modules = listOf(RenderModule())),
                backend,
            )
            host.loop.paused = true
            backend.drive(host)
            val slot = checkNotNull(backend.pipeline?.capture) { "the pipeline has no capture slot" }
            val world = host.world

            lateinit var cube: Entity
            backend.onRenderThread {
                // Ground, for a person looking at the frames. Blue-grey, so no pixel of it reads as
                // the cube's orange.
                world.entity {
                    it += Transform3D()
                    it += ModelRenderer(ModelMesh.plane(12f, 6f), ModelMaterial(flat(70, 82, 104), roughness = 0.9f))
                }
                cube = world.entity {
                    it += Transform3D(x = FROM_X, z = 0.6f)
                    it += ModelRenderer(ModelMesh.box(1.2f, 1.2f, 1.2f), ModelMaterial(checker(), roughness = 0.6f))
                }
                // The tick the cube is first recorded on: both ends of its history are here.
                host.loop.stepTicks(1)
            }
            // A snapshot arrives: the server has it on the right, turned.
            host.ctx.barrier.submit(MoveTo(cube, TO_X, TO_HEADING))
            backend.onRenderThread { host.loop.stepTicks(1) }
            // One frame for the new mesh to be ready (see GlModelRenderTest).
            slot.capture(CaptureRequest())

            val centres = ALPHAS.map { alpha ->
                backend.onRenderThread { checkNotNull(pinned[0]).alpha = alpha }
                slot.capture(CaptureRequest())
                val frame = decode(slot.capture(CaptureRequest()).bytes)
                save(frame, "model-interp-alpha-%03d.png".format((alpha * 100).toInt()))
                centreColumn(frame)
            }

            val span = centres.last() - centres.first()
            assertTrue(span > MIN_TRAVEL_PX, "the cube barely moved between the two ticks: $centres")
            for (i in 1 until centres.size) {
                assertTrue(
                    centres[i] > centres[i - 1] + span / 8f,
                    "a higher alpha must draw the cube further along, every step: alphas $ALPHAS drew at $centres",
                )
            }
            val middle = centres[ALPHAS.indexOf(0.5f)]
            val halfway = (centres.first() + centres.last()) / 2f
            assertTrue(
                abs(middle - halfway) < span * 0.1f,
                "at alpha 0.5 the cube must be drawn about halfway, $halfway, but was at $middle: $centres",
            )
        } finally {
            backend.close()
        }
    }

    /**
     * [ModelRenderSystem], drawn at [alpha] rather than the loop's, so a picture is taken at a
     * chosen fraction of a tick. The loop's own alpha is whatever wall time left in its accumulator.
     */
    private class PinnedAlpha(private val inner: ModelRenderSystem) : RenderSystem {
        var alpha: Float = 0f

        override fun onBind(world: World, ctx: GameContext) = inner.onBind(world, ctx)

        override fun render(target: OffscreenTarget, alpha: Float) = inner.render(target, this.alpha)
    }

    /** What a client's snapshot apply does to one entity. */
    private class MoveTo(private val entity: Entity, private val x: Float, private val heading: Float) : BarrierAction {
        override val label: String = "apply a snapshot"

        override fun apply(world: World, ctx: GameContext) {
            with(world) {
                val t = entity[Transform3D]
                t.x = x
                t.rotationZ = heading
            }
        }
    }

    /** The mean column of every pixel of the cube's orange: warm, and nothing else in the frame is. */
    private fun centreColumn(image: BufferedImage): Float {
        var sum = 0.0
        var count = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.getRGB(x, y)
                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF
                if (r > b + WARM_MARGIN && r > g + WARM_MARGIN / 2) {
                    sum += x
                    count++
                }
            }
        }
        check(count > MIN_CUBE_PIXELS) { "the cube is not in the frame: $count lit pixels" }
        return (sum / count).toFloat()
    }

    /** A 4 x 4 checker of warm orange and pale cream, so the cube's turn is visible. The cream is not counted. */
    private fun checker(): SpriteTexture {
        val size = 64
        val cell = size / 4
        val rgba = ByteArray(size * size * 4)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val warm = ((x / cell) + (y / cell)) % 2 == 0
                val at = (y * size + x) * 4
                rgba[at] = (if (warm) 235 else 245).toByte()
                rgba[at + 1] = (if (warm) 120 else 235).toByte()
                rgba[at + 2] = (if (warm) 40 else 200).toByte()
                rgba[at + 3] = -1
            }
        }
        return SpriteTexture.fromRgba(size, size, rgba, "model-interp-checker")
    }

    private fun flat(r: Int, g: Int, b: Int): SpriteTexture {
        val rgba = ByteArray(4 * 4 * 4)
        for (at in rgba.indices step 4) {
            rgba[at] = r.toByte()
            rgba[at + 1] = g.toByte()
            rgba[at + 2] = b.toByte()
            rgba[at + 3] = -1
        }
        return SpriteTexture.fromRgba(4, 4, rgba, "model-interp-ground")
    }

    private fun decode(png: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(png))
        ?: error("the captured bytes are not a decodable image")

    private fun save(image: BufferedImage, name: String) {
        val dir = System.getProperty("udea.render.glReportDir") ?: return
        File(dir).mkdirs()
        ImageIO.write(image, "png", File(dir, name))
    }

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 270
        const val FROM_X = -2.5f
        const val TO_X = 2.5f
        const val TO_HEADING = 0.8f
        val ALPHAS = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

        /** How much redder than blue a pixel must be to be the cube's orange. */
        const val WARM_MARGIN = 80
        const val MIN_CUBE_PIXELS = 100
        const val MIN_TRAVEL_PX = 120f
    }
}
