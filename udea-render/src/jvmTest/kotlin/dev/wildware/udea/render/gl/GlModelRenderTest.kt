package dev.wildware.udea.render.gl

import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.capture
import dev.wildware.udea.render.draw.SpriteTexture
import dev.wildware.udea.render.interp.PoseSource
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A textured, lit model, drawn by [ModelRenderSystem] through a real Kool context and read back
 * from the capturable pass.
 *
 * ## The picture it reads
 *
 * The camera looks along +Y at a cube turned 45 degrees about Z, so exactly two of its side faces
 * face the camera, symmetric about the centre column of the frame: one to the left of the centre
 * line and one to the right. The light travels towards +X, so it strikes the left face and misses
 * the right one, which is lit by the ambient light alone. The cube's texture is a checker of two
 * colours that differ in *hue* (warm orange, cool teal), so lighting, which scales brightness,
 * cannot turn one into the other.
 *
 * - **Texture.** Each face's window must hold both hues. An unbound texture draws a face in one
 *   colour, whatever the light does.
 * - **Lighting.** The lit face must be clearly brighter than the unlit one. A material with no
 *   lighting, or a light that never reaches the shader, draws both faces alike: they carry the
 *   same checker.
 * - **Selection.** A `Transform3D` alone draws nothing; an entity whose `ModelRenderer` is removed
 *   stops being drawn on the next frame.
 * - **The 2D lift.** The same cube placed by a 2D pose at `(0, 0)` with a 45 degree heading draws
 *   the same picture as the `Transform3D` did.
 *
 * One `@Test` for every scenario because Kool allows one context per JVM (see `GlCaptureTest`).
 */
class GlModelRenderTest {

    @Test
    fun `a textured, lit model draws its texture and its lighting, and only while it has a model`() {
        GlAvailability.require()
        withModels { backend, host, system ->
            val slot = backend.pipeline!!.capture!!
            val world = host.world

            lateinit var cube: com.github.quillraven.fleks.Entity
            lateinit var smallCube: com.github.quillraven.fleks.Entity
            backend.onRenderThread {
                val material = ModelMaterial(checker(), roughness = 0.6f)
                cube = world.entity {
                    it += Transform3D(rotationZ = QUARTER_TURN / 2f)
                    it += ModelRenderer(ModelMesh.box(2f, 2f, 2f), material)
                }
                smallCube = world.entity {
                    it += Transform3D(x = SMALL_CUBE_X, scaleX = 0.5f, scaleY = 0.5f, scaleZ = 0.5f)
                    it += ModelRenderer(ModelMesh.box(2f, 2f, 2f), material)
                }
                // Where a model would be, with no model: nothing may be drawn here.
                world.entity { it += Transform3D(x = -SMALL_CUBE_X) }
            }

            // One frame goes by first. Seen on this suite's first run: the capture of the first
            // frame after the models were added came back all black, and the four after it were
            // byte-identical to each other with the cubes in. Kool's GL pass skips a draw whose
            // `DrawInfo` is not yet valid, which fits, but that is a reading of Kool rather than
            // something this test pins.
            slot.capture(CaptureRequest())

            // 1. Texture, lighting and selection, from one frame.
            val first = decode(slot.capture(CaptureRequest()).bytes)
            save(first, "model-textured-lit.png")
            assertEquals(2, system.drawnCount, "two entities have a ModelRenderer")

            val lit = window(first, first.width / 2 - FACE_OFFSET)
            val shaded = window(first, first.width / 2 + FACE_OFFSET)
            for ((name, face) in listOf("lit" to lit, "shaded" to shaded)) {
                assertTrue(
                    face.warm > MIN_HUE_SHARE && face.cool > MIN_HUE_SHARE,
                    "the $name face must show both checker colours, so the texture is bound: $face",
                )
            }
            assertTrue(
                lit.luminance > shaded.luminance * MIN_LIGHT_RATIO,
                "the face turned to the light must be brighter than the face turned away: " +
                    "lit=$lit shaded=$shaded",
            )
            val smallCubeWindow = window(first, first.width / 2 + SMALL_CUBE_PIXEL_OFFSET)
            assertTrue(smallCubeWindow.background < 0.5f, "the small cube is drawn: $smallCubeWindow")
            val bare = window(first, first.width / 2 - SMALL_CUBE_PIXEL_OFFSET)
            assertEquals(1f, bare.background, "a Transform3D with no ModelRenderer drew something: $bare")

            // 2. Taking the model away takes the drawing away, on the next frame.
            backend.onRenderThread { with(world) { smallCube.configure { it -= ModelRenderer } } }
            val second = decode(slot.capture(CaptureRequest()).bytes)
            assertEquals(1, system.drawnCount, "one entity still has a ModelRenderer")
            val gone = window(second, second.width / 2 + SMALL_CUBE_PIXEL_OFFSET)
            assertEquals(1f, gone.background, "a removed ModelRenderer is still drawn: $gone")

            // 3. The 2D lift: the same cube, placed by a 2D pose instead of a Transform3D.
            backend.onRenderThread {
                with(world) {
                    cube.configure {
                        it -= Transform3D
                        it += PhysicsBody(angle = QUARTER_TURN / 2f)
                    }
                }
            }
            val lifted = decode(slot.capture(CaptureRequest()).bytes)
            save(lifted, "model-lifted-from-2d.png")
            assertEquals(1, system.drawnCount, "the lifted cube is drawn")
            val liftedLit = window(lifted, lifted.width / 2 - FACE_OFFSET)
            val liftedShaded = window(lifted, lifted.width / 2 + FACE_OFFSET)
            assertTrue(
                abs(liftedLit.luminance - lit.luminance) < LIFT_TOLERANCE &&
                    abs(liftedShaded.luminance - shaded.luminance) < LIFT_TOLERANCE,
                "a 2D pose at (0, 0) heading 45 degrees must draw what Transform3D did: " +
                    "lit $liftedLit vs $lit, shaded $liftedShaded vs $shaded",
            )
        }
    }

    // --- fixture -------------------------------------------------------------------------

    private fun withModels(block: (KoolBackend, GameHost, ModelRenderSystem) -> Unit) {
        val registry = RenderRegistry()
        lateinit var system: ModelRenderSystem
        val camera = ModelCamera().apply { lookAt(0f, -6f, 0f, 0f, 0f, 0f) }
        // Travelling towards +X (and a little down and away): it strikes the face whose normal
        // points to -X, which the camera sees on the left.
        val light = ModelLight(directionX = 1f, directionY = 0.3f, directionZ = -0.2f)
        // The lift under test reads PhysicsBody as it stands; Interpolator's own blending is
        // covered elsewhere and is not what this asserts.
        val lift = PoseSource { world, entity, _, into ->
            val body = with(world) { entity.getOrNull(PhysicsBody) } ?: return@PoseSource false
            into.x = body.x
            into.y = body.y
            into.angle = body.angle
            true
        }
        registry.register(RenderPhase.World, { resources ->
            ModelRenderSystem(resources, camera, light, lift).also { system = it }
        })
        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-model-test",
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
                UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()),
                backend,
            )
            backend.drive(host)
            block(backend, host, system)
        } finally {
            backend.close()
        }
    }

    /** An 8 x 8 checker of warm orange and cool teal, 64 texels square. */
    private fun checker(): SpriteTexture {
        val size = 64
        val cell = size / 8
        val rgba = ByteArray(size * size * 4)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val warm = ((x / cell) + (y / cell)) % 2 == 0
                val at = (y * size + x) * 4
                rgba[at] = (if (warm) 230 else 30).toByte()
                rgba[at + 1] = (if (warm) 110 else 130).toByte()
                rgba[at + 2] = (if (warm) 30 else 210).toByte()
                rgba[at + 3] = -1
            }
        }
        return SpriteTexture.fromRgba(size, size, rgba, "model-test-checker")
    }

    /** What a square window of pixels, centred on column [centreX] and the middle row, holds. */
    private fun window(image: BufferedImage, centreX: Int): Window {
        val centreY = image.height / 2
        var warm = 0
        var cool = 0
        var background = 0
        var luminance = 0.0
        var count = 0
        for (y in centreY - HALF_WINDOW..centreY + HALF_WINDOW) {
            for (x in centreX - HALF_WINDOW..centreX + HALF_WINDOW) {
                val pixel = image.getRGB(x, y)
                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF
                if (r > b + HUE_MARGIN) warm++
                if (b > r + HUE_MARGIN) cool++
                if (r < BACKGROUND_LEVEL && g < BACKGROUND_LEVEL && b < BACKGROUND_LEVEL) background++
                luminance += 0.2126 * r + 0.7152 * g + 0.0722 * b
                count++
            }
        }
        return Window(
            warm = warm.toFloat() / count,
            cool = cool.toFloat() / count,
            background = background.toFloat() / count,
            luminance = (luminance / count).toFloat(),
        )
    }

    private data class Window(val warm: Float, val cool: Float, val background: Float, val luminance: Float)

    private fun decode(png: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(png))
        ?: error("the captured bytes are not a decodable image")

    /** Writes [image] where a person can look at it: the frame the assertions read. */
    private fun save(image: BufferedImage, name: String) {
        val dir = System.getProperty("udea.render.glReportDir") ?: return
        File(dir).mkdirs()
        ImageIO.write(image, "png", File(dir, name))
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 240

        const val QUARTER_TURN = (PI / 2).toFloat()

        /** Where the small cube stands, in world units, and where it lands on screen, in pixels. */
        const val SMALL_CUBE_X = 2.6f
        const val SMALL_CUBE_PIXEL_OFFSET = 125

        /** How far either side of the centre column each face's window is centred. */
        const val FACE_OFFSET = 34
        const val HALF_WINDOW = 12

        const val HUE_MARGIN = 20
        const val MIN_HUE_SHARE = 0.15f
        const val BACKGROUND_LEVEL = 8
        const val MIN_LIGHT_RATIO = 1.5f
        const val LIFT_TOLERANCE = 3f
    }
}
