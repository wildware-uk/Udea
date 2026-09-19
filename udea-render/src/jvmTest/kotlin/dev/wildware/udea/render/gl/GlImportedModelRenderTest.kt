package dev.wildware.udea.render.gl

import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.Model
import dev.wildware.udea.assets.ResPath
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.capture
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelLight
import dev.wildware.udea.render.model.ModelRenderSystem
import dev.wildware.udea.render.model.ModelRenderer
import dev.wildware.udea.render.model.loadModel
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Khronos Fox, imported from its `.glb` and drawn by [ModelRenderSystem] through a real Kool
 * context (issue #240).
 *
 * ## What it reads
 *
 * The fox stands side-on to the camera, and every pixel of the capture that is not the black
 * background is the fox. The fox's texture is three flat colours: an orange coat, a white chest
 * and tail tip, and dark brown legs (the texture is inside the `.glb`, `example-assets/models/fox`).
 * Lighting scales each colour's brightness and keeps its hue, so:
 *
 * - **orange** - red well above blue, green in between - must cover a good share of the fox, and
 * - **near-white** - all three channels high and close together - must cover some of it.
 *
 * A fox drawn without its texture is one colour: Kool's glTF path gives a material with no texture
 * a flat grey, and a texture that failed to load becomes Kool's magenta placeholder. Neither is
 * orange, so either fails the first assertion. A fox not drawn at all fails the pixel count.
 *
 * One `@Test` because Kool allows one context per JVM (see `GlCaptureTest`).
 */
class GlImportedModelRenderTest {

    @Test
    fun `the imported fox is drawn with the colours of its own texture`() {
        GlAvailability.require()
        val fox = loadModel(exampleAssets(), Model(AssetId("models/fox"), ResPath("models/fox/Fox.glb")))

        withSystem { backend, host, system ->
            val slot = backend.pipeline!!.capture!!
            backend.onRenderThread {
                host.world.entity {
                    // The file's units: the fox is about 80 tall and 155 long, so 0.02 makes it 1.6
                    // tall. A quarter turn about Z so it walks across the frame rather than towards it.
                    it += Transform3D(rotationZ = (PI / 2).toFloat(), scaleX = SCALE, scaleY = SCALE, scaleZ = SCALE)
                    it += ModelRenderer(model = fox)
                }
            }

            // Frames are taken until the fox is drawn, within a budget, and how many it took is
            // printed. Two things take frames here, and neither is a sleep. The texture decodes on
            // Kool's loader threads, and `ModelRenderSystem` does not show the fox until it has.
            // And the first frame that uses a shader program Kool has not compiled before comes
            // back empty: seen in this test's probes, where a second fox added later - same
            // program, new node, new mesh - drew on its very first frame, while a first fox added
            // after a cube drew one frame late. The cause inside Kool is not pinned; see #240.
            var frames = 0
            var image: BufferedImage
            var foxPixels: Int
            do {
                image = decode(slot.capture(CaptureRequest()).bytes)
                foxPixels = count(image) { r, g, b -> r > BACKGROUND || g > BACKGROUND || b > BACKGROUND }
                frames++
            } while (foxPixels < MIN_FOX_PIXELS && frames < FRAME_BUDGET)
            save(image, "imported-fox.png")
            println("GlImportedModelRenderTest: the fox appeared after $frames captured frame(s)")

            assertEquals(1, backend.onRenderThread { system.drawnCount }, "one entity has a ModelRenderer")
            assertTrue(foxPixels >= MIN_FOX_PIXELS, "the fox is not drawn: $foxPixels lit pixels after $frames frames")

            val orange = count(image) { r, g, b -> r > b + 60 && g > b + 20 && r > g + 25 }
            val white = count(image) { r, g, b -> r > 170 && g > 170 && b > 150 && r - b < 70 }
            val orangeShare = orange.toFloat() / foxPixels
            val whiteShare = white.toFloat() / foxPixels
            println("GlImportedModelRenderTest: fox=$foxPixels orange=$orangeShare white=$whiteShare")
            assertTrue(
                orangeShare > MIN_ORANGE_SHARE,
                "the fox's coat is not its texture's orange ($orangeShare of $foxPixels pixels): the texture is not bound",
            )
            assertTrue(
                whiteShare > MIN_WHITE_SHARE,
                "the fox's white chest is missing ($whiteShare of $foxPixels pixels): the texture is not bound",
            )
        }
    }

    // --- fixture -------------------------------------------------------------------------

    private fun withSystem(block: (KoolBackend, GameHost, ModelRenderSystem) -> Unit) {
        val registry = RenderRegistry()
        lateinit var system: ModelRenderSystem
        // Side-on and a little above, framing a fox about 1.6 tall and 3.1 long.
        val camera = ModelCamera().apply { lookAt(0f, -5.5f, 1.6f, 0f, 0f, 0.8f) }
        val light = ModelLight(directionX = -0.4f, directionY = 0.7f, directionZ = -1f)
        registry.register(RenderPhase.World, { resources ->
            ModelRenderSystem(resources, camera, light).also { system = it }
        })
        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-imported-model-test",
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

    private fun exampleAssets(): Path = Path.of(
        System.getProperty("udea.render.exampleAssets") ?: error("-Dudea.render.exampleAssets is not set"),
    )

    private fun count(image: BufferedImage, test: (Int, Int, Int) -> Boolean): Int {
        var n = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.getRGB(x, y)
                if (test((pixel ushr 16) and 0xFF, (pixel ushr 8) and 0xFF, pixel and 0xFF)) n++
            }
        }
        return n
    }

    private fun decode(png: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(png))
        ?: error("the captured bytes are not a decodable image")

    /** Writes [image] where a person can look at it: the frame the assertions read. */
    private fun save(image: BufferedImage, name: String) {
        val dir = System.getProperty("udea.render.glReportDir") ?: return
        File(dir).mkdirs()
        ImageIO.write(image, "png", File(dir, name))
    }

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 320

        /** World units per unit of the file: a fox about 1.6 tall. */
        const val SCALE = 0.02f

        /** A channel above this is not the black the capture clears to. */
        const val BACKGROUND = 12

        /** Side-on at this size the fox covers about 8900 pixels; fewer than this is not a fox. */
        const val MIN_FOX_PIXELS = 5_000

        /** Frames allowed for the texture to decode and the fox to appear. */
        const val FRAME_BUDGET = 240

        /** Measured textured: 0.84 orange and 0.046 white. A grey or magenta fox has neither. */
        const val MIN_ORANGE_SHARE = 0.25f
        const val MIN_WHITE_SHARE = 0.03f
    }
}
