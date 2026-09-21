package dev.wildware.udea.render.gl

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
import dev.wildware.udea.render.capture.FrameCaptureSlot
import dev.wildware.udea.render.capture.capture
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteTexture
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelLight
import dev.wildware.udea.render.model.ModelMaterial
import dev.wildware.udea.render.model.ModelMesh
import dev.wildware.udea.render.model.ModelRenderSystem
import dev.wildware.udea.render.model.ModelRenderer
import dev.wildware.udea.render.sky.SkyBackground
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sky in a real capture (issue #267): a box floating in a 3D frame, with nothing else in it, so
 * every pixel that is not the box is the sky - or, with no sky, the frame's clear colour.
 *
 * Every colour is read out of the PNG a capture returned, one channel at a time, **red, green and
 * blue and never alpha**. The capture forces alpha to 255 (`KoolPixelSource`'s stomp), so a reading
 * that took the alpha byte would call a black frame white and pass every "the sky is bright" check.
 *
 * - **No sky.** The corners are black. That is the frame every game had before a sky existed.
 * - **Solid.** The corners are the sky's colour, to the level, and the box is still drawn over it.
 * - **Gradient.** The top row is the top colour and the bottom row is the bottom colour.
 * - **Changed while running, and taken away.** Setting [SkyBackground.None] again gives back a frame
 *   byte-for-byte identical to the one before any sky was set.
 *
 * One `@Test` for every scenario because Kool allows one context per JVM (see `GlCaptureTest`).
 */
class GlSkyTest {

    @Test
    fun `a sky shows wherever nothing is drawn, changes while running, and None is the black it always was`() {
        GlAvailability.require()
        val registry = RenderRegistry()
        val camera = ModelCamera().apply { lookAt(0f, -8f, 0f, 0f, 0f, 0f) }
        registry.register(RenderPhase.World, { resources -> ModelRenderSystem(resources, camera, ModelLight()) })
        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-sky-test",
                windowWidth = WIDTH,
                windowHeight = HEIGHT,
                renderWidth = WIDTH,
                renderHeight = HEIGHT,
            ),
            registry,
        )
        try {
            val host = GameHost(RenderMode.Offscreen, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()), backend)
            backend.drive(host)
            host.loop.paused = true
            val slot = checkNotNull(backend.pipeline?.capture) { "this pipeline cannot be captured" }
            backend.onRenderThread {
                host.world.entity {
                    it += Transform3D()
                    it += ModelRenderer(ModelMesh.box(2f, 2f, 2f), ModelMaterial(flat(BOX), roughness = 0.8f))
                }
            }

            // 1. No sky: black wherever the box is not.
            val none = settle(slot)
            save(none, "sky-1-none.png")
            for ((name, pixel) in corners(none)) assertColour(Rgba.BLACK, pixel, "with no sky, the $name corner")
            val boxCentre = none.getRGB(WIDTH / 2, HEIGHT / 2)
            assertTrue(channelsOf(boxCentre).sum() > BOX_VISIBLE, "the box is not in the frame, so nothing here is over it")

            // 2. Solid: the sky's colour wherever the box is not, and the box still over it.
            registry.sky.background = SkyBackground.Solid(DAY)
            val solid = settle(slot)
            save(solid, "sky-2-solid.png")
            for ((name, pixel) in corners(solid)) assertColour(DAY, pixel, "with a solid sky, the $name corner")
            assertTrue(
                !matches(DAY, solid.getRGB(WIDTH / 2, HEIGHT / 2)),
                "the centre of the frame is sky colour: the sky was drawn over the box",
            )

            // 3. Gradient: the top colour along the top, the bottom colour along the bottom.
            registry.sky.background = SkyBackground.Gradient(top = NIGHT, bottom = DUSK)
            val gradient = settle(slot)
            save(gradient, "sky-3-gradient.png")
            for (x in listOf(0, WIDTH - 1)) {
                assertColour(NIGHT, gradient.getRGB(x, 0), "the top row of a gradient sky at x=$x")
                assertColour(DUSK, gradient.getRGB(x, HEIGHT - 1), "the bottom row of a gradient sky at x=$x")
            }

            // 4. Taken away again: the frame before any sky, to the byte.
            registry.sky.background = SkyBackground.None
            val again = settle(slot)
            save(again, "sky-4-none-again.png")
            assertEquals(0, differingPixels(none, again), "setting no sky again did not give back the frame with none")
        } finally {
            backend.close()
        }
    }

    // --- fixture -------------------------------------------------------------------------

    /**
     * Captures until two in a row are identical. The sky is read on the render thread at the top
     * of a frame, so a frame already in flight when it is set finishes with the old one.
     */
    private fun settle(slot: FrameCaptureSlot): BufferedImage {
        var previous = decode(slot.capture(CaptureRequest()).bytes)
        repeat(SETTLE_TRIES) {
            val next = decode(slot.capture(CaptureRequest()).bytes)
            if (differingPixels(previous, next) == 0) return next
            previous = next
        }
        error("the frame never settled over $SETTLE_TRIES captures")
    }

    private fun corners(image: BufferedImage): List<Pair<String, Int>> = listOf(
        "top-left" to image.getRGB(0, 0),
        "top-right" to image.getRGB(image.width - 1, 0),
        "bottom-left" to image.getRGB(0, image.height - 1),
        "bottom-right" to image.getRGB(image.width - 1, image.height - 1),
    )

    /** Red, green and blue in `0..255`, from a `BufferedImage` ARGB pixel. Never the alpha byte. */
    private fun channelsOf(argb: Int): List<Int> = listOf((argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF)

    private fun expectedChannels(colour: Rgba): List<Int> =
        listOf((colour.packed ushr 24) and 0xFF, (colour.packed ushr 16) and 0xFF, (colour.packed ushr 8) and 0xFF)

    private fun matches(colour: Rgba, argb: Int): Boolean =
        expectedChannels(colour).zip(channelsOf(argb)).all { (want, got) -> abs(want - got) <= ONE_LEVEL }

    private fun assertColour(colour: Rgba, argb: Int, what: String) {
        assertTrue(
            matches(colour, argb),
            "$what is ${channelsOf(argb)}, and the sky is ${expectedChannels(colour)} (red, green, blue)",
        )
    }

    private fun differingPixels(a: BufferedImage, b: BufferedImage): Int {
        var count = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getRGB(x, y) != b.getRGB(x, y)) count++
        return count
    }

    private fun flat(colour: Rgba): SpriteTexture = SpriteTexture.fromRgba(
        1,
        1,
        byteArrayOf((colour.packed ushr 24).toByte(), (colour.packed ushr 16).toByte(), (colour.packed ushr 8).toByte(), -1),
        "sky-test-box",
    )

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
        const val SETTLE_TRIES = 12

        /**
         * How far a channel may be from the colour asked for: the one level a float colour can
         * round by on its way to a byte. The sky is drawn unlit and untouched, so it is exact or
         * one level off, and any real fault - a black frame, the wrong colour, alpha read as a
         * channel - is tens of levels away.
         */
        const val ONE_LEVEL = 1

        /** Sum of the box's channels that proves something lit is there. Black is 0. */
        const val BOX_VISIBLE = 60

        val BOX = Rgba.of(0.85f, 0.75f, 0.5f)
        val DAY = Rgba.of(0.4f, 0.65f, 0.95f)
        val NIGHT = Rgba.of(0.02f, 0.03f, 0.12f)
        val DUSK = Rgba.of(0.95f, 0.55f, 0.3f)
    }
}
