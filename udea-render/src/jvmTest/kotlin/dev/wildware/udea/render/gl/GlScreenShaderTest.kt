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
import dev.wildware.udea.render.capture.capture
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteTexture
import dev.wildware.udea.render.kool.GlScreenProgram
import dev.wildware.udea.render.kool.koolGl
import dev.wildware.udea.render.kool.koolGlslVersion
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelLight
import dev.wildware.udea.render.model.ModelMaterial
import dev.wildware.udea.render.model.ModelMesh
import dev.wildware.udea.render.model.ModelRenderSystem
import dev.wildware.udea.render.model.ModelRenderer
import dev.wildware.udea.render.shader.ScreenEffects
import dev.wildware.udea.render.shader.ScreenShaderException
import dev.wildware.udea.render.shader.UdeaShader
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The screen-effect chain (issues #259, #266) against a real Kool context: the control, the
 * palette, the outline, the order two shaders run in, and what a driver's refusal comes back as.
 *
 * ## The scene, and why it is this one
 *
 * A wide grey ground plane fills the frame with a box standing on it, and **only the box is in the
 * object mask**. That is #259's second criterion in one picture: an outline that finds the box and
 * leaves the ground alone cannot be doing it from brightness or from depth-against-the-background,
 * because the ground is the background and it is lit the same way the box is.
 *
 * ## The control, which is the half that is easy to leave out
 *
 * "The processed frame differs from the unprocessed one" is a measurement, and a measurement with
 * no control is a threshold that cannot fail: if two *unprocessed* frames already differed by the
 * amount being asserted, every assertion below would pass for a reason that has nothing to do with
 * a shader. So the first thing this does is capture the same scene twice with every effect off and
 * require the difference to be exactly zero, and every later claim is stated as a magnitude
 * against that zero.
 *
 * ## One `@Test` method
 *
 * Kool allows one `KoolContext` per JVM for the life of the JVM (`GlCaptureTest` and `KoolThread`
 * both write this out), and `forkEvery = 1` gives each test *class* a fresh JVM rather than each
 * method. Every scenario therefore shares the one context this fixture creates, in order.
 */
class GlScreenShaderTest {

    @Test
    fun `the chain - control, palette, outline, order, and a driver's refusal`() {
        GlAvailability.require()

        // Two colours far apart in every channel, so "snapped to the palette" is visible and so a
        // pixel that is neither cannot be mistaken for rounding.
        val palette = listOf(Rgba.of(0f, 0f, 0f), Rgba.of(1f, 1f, 1f))
        val paletteShader = ScreenEffects.palette(palette).apply { enabled = false }
        val outlineShader = ScreenEffects.outline(OUTLINE_COLOUR).apply { enabled = false }

        withScene(paletteShader, outlineShader) { backend, host ->
            val slot = backend.pipeline!!.capture!!
            // Paused, so nothing in the world moves between captures and a difference can only
            // have come from a shader. Frames keep being drawn, which is what a capture needs.
            host.loop.paused = true

            // 1. THE CONTROL. Two captures of the same scene, no effect running.
            val plain = decode(slot.capture(CaptureRequest()).bytes)
            val plainAgain = decode(slot.capture(CaptureRequest()).bytes)
            save(plain, "screen-plain.png")
            val control = difference(plain, plainAgain)
            assertEquals(
                0f,
                control,
                "two unprocessed captures of a paused scene must be identical, or every " +
                    "'the shader changed the frame' assertion below is measuring the renderer's " +
                    "own noise; they differed by $control levels per channel",
            )
            assertTrue(
                offPalette(plain, palette) > MOST_PIXELS,
                "the unprocessed frame must NOT already be made of palette colours, or the " +
                    "palette assertion could not fail",
            )

            // 2. PALETTE. Every pixel becomes one of the two colours, and the frame moves a long
            // way from where it was.
            paletteShader.enabled = true
            val paletted = decode(slot.capture(CaptureRequest()).bytes)
            save(paletted, "screen-palette.png")
            assertEquals(
                0f,
                offPalette(paletted, palette),
                "every pixel of a paletted frame must be one of the palette's colours",
            )
            val paletteMoved = difference(plain, paletted)
            assertTrue(
                paletteMoved > PALETTE_MAGNITUDE,
                "snapping a lit scene to black and white moves most pixels most of the way to " +
                    "one end; the frame moved $paletteMoved levels per channel, which is less " +
                    "than the $PALETTE_MAGNITUDE this effect has to be worth",
            )

            // 3. OUTLINE. On its own, over the unprocessed picture.
            paletteShader.enabled = false
            outlineShader.enabled = true
            val outlined = decode(slot.capture(CaptureRequest()).bytes)
            save(outlined, "screen-outline.png")
            val onBox = darkenedPixels(plain, outlined, BOX_LEFT, BOX_RIGHT, BOX_TOP, BOX_BOTTOM)
            val onGround = darkenedPixels(plain, outlined, GROUND_LEFT, GROUND_RIGHT, GROUND_TOP, GROUND_BOTTOM)
            assertTrue(
                onBox > 0,
                "no pixel around the box was darkened, so the outline drew nothing: either the " +
                    "mask is empty or the effect did not run",
            )
            assertEquals(
                0,
                onGround,
                "$onGround pixels of open ground were darkened. The ground is not in the mask, " +
                    "so an outline there is an outline found from something other than the mask",
            )

            // 4. ORDER. Both on: the outline is drawn over an already-paletted picture, so the
            // frame is neither of the two previous ones.
            paletteShader.enabled = true
            val both = decode(slot.capture(CaptureRequest()).bytes)
            save(both, "screen-palette-outline.png")
            assertTrue(
                difference(both, paletted) > 0f && difference(both, outlined) > 0f,
                "with both effects on the frame must differ from each one alone",
            )

            // 5. EVERYTHING OFF AGAIN. The chain is a per-frame decision, not a one-way door.
            paletteShader.enabled = false
            outlineShader.enabled = false
            val restored = decode(slot.capture(CaptureRequest()).bytes)
            assertEquals(
                0f,
                difference(plain, restored),
                "with every effect off the frame must be the one the control measured",
            )

            // 6. A DRIVER'S REFUSAL, reported at the author's line.
            //
            // Compiled straight on the render thread rather than through a second pipeline,
            // because this JVM has one context and the fixture above is using it.
            val broken = UdeaShader.fragment(
                path = "shaders/broken.frag",
                source = BROKEN_SOURCE,
            )
            val failure = assertFailsWith<ScreenShaderException> {
                backend.onRenderThread {
                    val version = koolGlslVersion()
                    val vertex = GlScreenProgram.compileVertex(koolGl(), version)
                    GlScreenProgram.link(koolGl(), version, vertex, broken)
                }
            }
            assertEquals(ScreenShaderException.COMPILE_FAILED, failure.ruleId)
            assertEquals("shaders/broken.frag", failure.path)
            assertEquals(
                BROKEN_LINE,
                failure.line,
                "the driver reports a line of the text it was handed, which has the engine's " +
                    "header in front of the body; the failure must name the line of the " +
                    "author's own file. Driver said: ${failure.message}",
            )

            // 7. A UNIFORM THE SOURCE NEVER DECLARES, with the did-you-mean the contract requires.
            val misspelled = UdeaShader.fragment(
                path = "shaders/misspelled.frag",
                source = MISSPELLED_SOURCE,
            ) {
                float("uLevls", 4f)
            }
            val notDeclared = assertFailsWith<ScreenShaderException> {
                backend.onRenderThread {
                    val version = koolGlslVersion()
                    val vertex = GlScreenProgram.compileVertex(koolGl(), version)
                    GlScreenProgram.link(koolGl(), version, vertex, misspelled)
                }
            }
            assertEquals(ScreenShaderException.UNIFORM_NOT_DECLARED, notDeclared.ruleId)
            assertEquals("shaders/misspelled.frag", notDeclared.path)
            assertTrue(
                "uLevels" in notDeclared.detail,
                "a uniform the source does not declare must be told what the source does " +
                    "declare, which is this engine's did-you-mean: ${notDeclared.message}",
            )
        }
    }

    // --- fixture -------------------------------------------------------------------------

    /**
     * A grey ground plane with a box standing on it, the box in the object mask and the ground
     * not, drawn through a real context with [shaders] installed in the order given.
     */
    private fun withScene(vararg shaders: UdeaShader, block: (KoolBackend, GameHost) -> Unit) {
        val registry = RenderRegistry()
        val camera = ModelCamera().apply { lookAt(0f, -7f, 3.5f, 0f, 0f, 0.5f) }
        val light = ModelLight(directionX = 0.4f, directionY = 0.8f, directionZ = -0.5f)
        registry.register(RenderPhase.World, { resources ->
            ModelRenderSystem(resources, camera, light)
        })
        for (shader in shaders) registry.screenPass(shader)

        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-screen-shader-test",
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
            val ground = ModelMaterial(flat(GROUND_GREY, "screen-ground"))
            val boxSkin = ModelMaterial(flat(BOX_SAND, "screen-box"))
            host.game.world.entity {
                it += Transform3D(x = 0f, y = 0f, z = 0f)
                // Not in the mask: it is the scenery an outline must not find.
                it += ModelRenderer(ModelMesh.plane(GROUND_SIZE, GROUND_SIZE), ground)
            }
            host.game.world.entity {
                it += Transform3D(x = 0f, y = 0f, z = BOX_SIZE / 2f)
                it += ModelRenderer(ModelMesh.box(BOX_SIZE, BOX_SIZE, BOX_SIZE), boxSkin)
                    .apply { mask = true }
            }
            backend.drive(host)
            block(backend, host)
        } finally {
            backend.close()
        }
    }

    /** A one-texel texture of one colour: the material's albedo, with no pattern to confuse an edge. */
    private fun flat(colour: Rgba, name: String): SpriteTexture = SpriteTexture.fromRgba(
        1,
        1,
        byteArrayOf(
            (colour.r * 255f).toInt().toByte(),
            (colour.g * 255f).toInt().toByte(),
            (colour.b * 255f).toInt().toByte(),
            -1,
        ),
        name,
    )

    // --- measurements --------------------------------------------------------------------

    /** Mean absolute difference per channel, in levels of 0..255. Zero means byte-identical. */
    private fun difference(a: BufferedImage, b: BufferedImage): Float {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        var total = 0L
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                val left = a.getRGB(x, y)
                val right = b.getRGB(x, y)
                total += abs(((left ushr 16) and 0xFF) - ((right ushr 16) and 0xFF))
                total += abs(((left ushr 8) and 0xFF) - ((right ushr 8) and 0xFF))
                total += abs((left and 0xFF) - (right and 0xFF))
            }
        }
        return total.toFloat() / (a.width * a.height * CHANNELS)
    }

    /** The share of pixels that are not one of [palette]'s colours, allowing one level of rounding. */
    private fun offPalette(image: BufferedImage, palette: List<Rgba>): Float {
        var off = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.getRGB(x, y)
                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF
                val matched = palette.any { entry ->
                    abs((entry.r * 255f).toInt() - r) <= ROUNDING &&
                        abs((entry.g * 255f).toInt() - g) <= ROUNDING &&
                        abs((entry.b * 255f).toInt() - b) <= ROUNDING
                }
                if (!matched) off++
            }
        }
        return off.toFloat() / (image.width * image.height)
    }

    /** How many pixels of the rectangle got noticeably darker between [before] and [after]. */
    private fun darkenedPixels(
        before: BufferedImage,
        after: BufferedImage,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
    ): Int {
        var darkened = 0
        for (y in top..bottom) {
            for (x in left..right) {
                val was = luminance(before.getRGB(x, y))
                val now = luminance(after.getRGB(x, y))
                if (was - now > DARKENED) darkened++
            }
        }
        return darkened
    }

    private fun luminance(pixel: Int): Float {
        val r = (pixel ushr 16) and 0xFF
        val g = (pixel ushr 8) and 0xFF
        val b = pixel and 0xFF
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
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
        const val WIDTH = 320
        const val HEIGHT = 240

        /** Three channels compared per pixel; alpha is stomped to opaque by the capture. */
        const val CHANNELS = 3

        /** One level of 0..255 either way, which is where an 8-bit round trip lands. */
        const val ROUNDING = 1

        /**
         * How far from the unprocessed frame the black-and-white palette has to move it.
         *
         * A lit grey-and-sand scene sits in the middle of the range, and snapping it to black or
         * white moves most pixels most of the way to one end - tens of levels per channel on
         * average. Set well under what the effect actually does and well over the zero the control
         * measured, so the assertion fails if the effect stops working rather than when a driver
         * rounds differently.
         */
        const val PALETTE_MAGNITUDE = 20f

        /** Nearly every pixel: what an unprocessed lit frame has that is not a palette colour. */
        const val MOST_PIXELS = 0.9f

        /** Levels of luminance a pixel must lose to count as outlined. */
        const val DARKENED = 20f

        val OUTLINE_COLOUR: Rgba = Rgba.of(0f, 0f, 0f, 1f)

        val GROUND_GREY: Rgba = Rgba.of(0.55f, 0.55f, 0.55f)
        val BOX_SAND: Rgba = Rgba.of(0.8f, 0.7f, 0.45f)

        const val GROUND_SIZE = 40f
        const val BOX_SIZE = 2f

        /**
         * The box's window: the middle of the frame, wide enough to hold the box and the ring of
         * ground immediately around it, which is where an outline is drawn.
         */
        const val BOX_LEFT = 110
        const val BOX_RIGHT = 210
        const val BOX_TOP = 60
        const val BOX_BOTTOM = 190

        /** Open ground: the left edge of the frame, nowhere near the box. */
        const val GROUND_LEFT = 2
        const val GROUND_RIGHT = 70
        const val GROUND_TOP = 150
        const val GROUND_BOTTOM = 230

        /**
         * A body whose third line does not compile. `vec3 x = 1.0;` is a type error every GLSL
         * compiler reports, at that line, in its own words.
         */
        val BROKEN_SOURCE: String = """
            vec4 udeaMain(vec2 uv) {
                vec3 colour = texture(uColor, uv).rgb;
                vec3 broken = 1.0;
                return vec4(colour + broken, 1.0);
            }
        """.trimIndent()

        /** Line 3 of [BROKEN_SOURCE], counting from 1, which is what the diagnostic must name. */
        const val BROKEN_LINE = 3

        /** Declares `uLevels`; the Kotlin side is going to ask for `uLevls`. */
        val MISSPELLED_SOURCE: String = """
            uniform float uLevels;

            vec4 udeaMain(vec2 uv) {
                return vec4(vec3(uLevels), 1.0);
            }
        """.trimIndent()
    }
}
