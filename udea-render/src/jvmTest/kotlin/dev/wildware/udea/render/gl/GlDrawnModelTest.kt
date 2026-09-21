package dev.wildware.udea.render.gl

import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.AssetRegistry
import dev.wildware.udea.assets.Model
import dev.wildware.udea.assets.ResPath
import dev.wildware.udea.assets.reference
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.spatial.Drawn
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.capture
import dev.wildware.udea.render.model.FileModelLibrary
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelLight
import dev.wildware.udea.render.model.ModelRenderSystem
import dev.wildware.udea.render.model.ModelRenderer
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #270's second acceptance criterion, end to end through a real Kool context: **a game
 * spawns an entity with an asset reference and it draws, with no game-written bridge to
 * `ModelRenderer`.**
 *
 * The entity is given exactly two components, both of them `udea-core`'s and both of them things
 * a blueprint or a level could have written:
 *
 * ```
 * it += Transform3D(...)
 * it += Drawn(reference<Model>("models/fox"), assets)
 * ```
 *
 * Nothing in this test names [ModelRenderer], loads a file, or runs a loop of its own. The
 * engine's [FileModelLibrary] reads the fox off disk and [ModelRenderSystem] attaches the
 * renderer on the render thread. What is then asserted is the picture: the fox's own orange coat,
 * in the frame an agent's `render.screenshot` reads.
 *
 * ## Why the colour and not just the pixel count
 *
 * A model drawn without its texture is a flat grey, and one whose texture failed is Kool's
 * magenta placeholder - so "some pixels are lit" would pass for a fox that arrived broken. The
 * orange share is what says the `.glb` the *slot* named was the file that was read.
 * `GlImportedModelRenderTest` makes the same argument at length for a hand-attached renderer;
 * this one is about the slot getting there at all.
 *
 * One `@Test` because Kool allows one context per JVM (see `GlCaptureTest`).
 */
class GlDrawnModelTest {

    @Test
    fun `an entity the simulation gave an asset reference draws itself`() {
        GlAvailability.require()

        // The packed graph a game would ship. One model, so its slot is 0 - which is *not* the
        // number `Drawn` would hold by accident: an unset `Drawn` is -1 and draws nothing.
        val fox = Model(AssetId("models/fox"), ResPath("models/fox/Fox.glb"))
        val assets = AssetRegistry(arrayOf(fox), byteArrayOf())

        withSystem(FileModelLibrary(exampleAssets(), assets)) { backend, host, system ->
            val slot = backend.pipeline!!.capture!!
            val entity = backend.onRenderThread {
                host.world.entity {
                    it += Transform3D(
                        rotationZ = (PI / 2).toFloat(),
                        scaleX = SCALE, scaleY = SCALE, scaleZ = SCALE,
                    )
                    // The whole point: an asset reference, written by simulation-side code.
                    it += Drawn(reference<Model>("models/fox"), assets)
                }
            }

            // Frames until the fox appears, for `GlImportedModelRenderTest`'s two reasons: the
            // texture decodes on Kool's loader threads, and a freshly compiled shader program's
            // first frame comes back empty.
            var frames = 0
            var image: BufferedImage
            var foxPixels: Int
            do {
                image = decode(slot.capture(CaptureRequest()).bytes)
                foxPixels = count(image) { r, g, b -> r > BACKGROUND || g > BACKGROUND || b > BACKGROUND }
                frames++
            } while (foxPixels < MIN_FOX_PIXELS && frames < FRAME_BUDGET)
            save(image, "drawn-fox.png")
            println("GlDrawnModelTest: the fox appeared after $frames captured frame(s), $foxPixels pixels")

            val renderer = backend.onRenderThread { with(host.world) { entity.getOrNull(ModelRenderer) } }
            assertNotNull(renderer, "the engine attached no ModelRenderer for the Drawn slot")
            assertEquals(1, backend.onRenderThread { system.drawnCount }, "one model was drawn")
            assertTrue(
                foxPixels >= MIN_FOX_PIXELS,
                "the entity did not draw: $foxPixels lit pixels after $frames frames",
            )

            val orange = count(image) { r, g, b -> r > b + 60 && g > b + 20 && r > g + 25 }
            val orangeShare = orange.toFloat() / foxPixels
            println("GlDrawnModelTest: fox=$foxPixels orange=$orangeShare")
            assertTrue(
                orangeShare > MIN_ORANGE_SHARE,
                "the slot did not reach the right file: $orangeShare of $foxPixels pixels are the fox's orange",
            )
        }
    }

    // --- fixture -------------------------------------------------------------------------

    private fun withSystem(
        library: FileModelLibrary,
        block: (KoolBackend, GameHost, ModelRenderSystem) -> Unit,
    ) {
        val registry = RenderRegistry()
        lateinit var system: ModelRenderSystem
        val camera = ModelCamera().apply { lookAt(0f, -5.5f, 1.6f, 0f, 0f, 0.8f) }
        val light = ModelLight(directionX = -0.4f, directionY = 0.7f, directionZ = -1f)
        registry.register(RenderPhase.World, { resources ->
            ModelRenderSystem(resources, camera, light, models = library).also { system = it }
        })
        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-drawn-model-test",
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

    /** Writes the frame the assertions read, where a person can look at it. */
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

        /** Measured textured: 0.84 orange. A grey or magenta fox has none of it. */
        const val MIN_ORANGE_SHARE = 0.25f
    }
}
