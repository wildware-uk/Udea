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
import dev.wildware.udea.render.kool.GlScreenProgram
import dev.wildware.udea.render.kool.koolGl
import dev.wildware.udea.render.kool.koolGlslVersion
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelLight
import dev.wildware.udea.render.model.ModelMaterial
import dev.wildware.udea.render.model.ModelMesh
import dev.wildware.udea.render.model.ModelRenderSystem
import dev.wildware.udea.render.model.ModelRenderer
import dev.wildware.udea.render.shader.ColorUniform
import dev.wildware.udea.render.shader.FloatUniform
import dev.wildware.udea.render.shader.IntUniform
import dev.wildware.udea.render.shader.ScreenEffects
import dev.wildware.udea.render.shader.ScreenShaderException
import dev.wildware.udea.render.shader.TextureUniform
import dev.wildware.udea.render.shader.UdeaShader
import dev.wildware.udea.render.shader.Vec2Uniform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

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

        // Three colours, none of them the pure black the frame is cleared to, so the sky counts
        // as off-palette too and "the frame is not already made of these" is a real statement.
        // They are far apart in every channel, so a pixel that is none of them cannot be
        // mistaken for rounding.
        val palette = listOf(
            Rgba.of(0.09f, 0.09f, 0.13f),
            Rgba.of(0.45f, 0.43f, 0.40f),
            Rgba.of(0.85f, 0.78f, 0.60f),
        )
        val paletteShader = ScreenEffects.palette(palette).apply { enabled = false }
        val outlineShader = ScreenEffects.outline(OUTLINE_COLOUR).apply { enabled = false }
        // Two shaders that draw nothing but an engine-supplied input, so that "the mask is empty"
        // and "the outline did not run" are different pictures rather than the same failure.
        val maskView = UdeaShader.fragment("shaders/mask-view.frag", MASK_VIEW).apply { enabled = false }
        val depthView = UdeaShader.fragment("shaders/depth-view.frag", DEPTH_VIEW).apply { enabled = false }

        // One shader declaring every kind of uniform the API offers, so that each kind is proved
        // to reach the GPU rather than merely to compile. Its body reads all five, so changing any
        // one of them has to change the picture; scenario 7 changes them one at a time.
        val rampA = SpriteTexture.fromRgba(RAMP_TEXELS, 1, RAMP_A, "screen-ramp-a")
        val rampB = SpriteTexture.fromRgba(RAMP_TEXELS, 1, RAMP_B, "screen-ramp-b")
        lateinit var amount: FloatUniform
        lateinit var steps: IntUniform
        lateinit var shift: Vec2Uniform
        lateinit var tint: ColorUniform
        lateinit var ramp: TextureUniform
        val everyUniform = UdeaShader.fragment("shaders/every-uniform.frag", EVERY_UNIFORM) {
            amount = float("uAmount", 0.5f)
            steps = int("uSteps", 4)
            shift = vec2("uShift", 0f, 0f)
            tint = color("uTint", Rgba.of(1f, 0.6f, 0.2f, 1f))
            ramp = texture("uRamp", rampA)
        }.apply { enabled = false }

        withScene(paletteShader, outlineShader, maskView, depthView, everyUniform) { backend, host ->
            val slot = backend.pipeline!!.capture!!
            // Paused, so nothing in the world moves between captures and a difference can only
            // have come from a shader. Frames keep being drawn, which is what a capture needs.
            host.loop.paused = true

            // 1. THE CONTROL. Two captures of the same scene, no effect running.
            //
            // After settling, which is a separate thing: the opening frames of a context are not
            // the scene (Kool has not drawn the models into the 3D pass yet, and the very first
            // capture came back 116 levels per channel away from the next one - the difference
            // between an empty frame and a lit one). `settle` gets past that; the control below
            // is the assertion that it is now steady, and every magnitude is measured against it.
            settle(slot)
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
            settle(slot)
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

            // 3. THE ENGINE'S INPUTS, drawn as pictures. #266's second criterion is that the
            // engine supplies depth and an object mask; these are what it supplied.
            paletteShader.enabled = false
            maskView.enabled = true
            settle(slot)
            val maskPicture = decode(slot.capture(CaptureRequest()).bytes)
            save(maskPicture, "screen-mask.png")
            maskView.enabled = false
            depthView.enabled = true
            settle(slot)
            val depthPicture = decode(slot.capture(CaptureRequest()).bytes)
            save(normalised(depthPicture), "screen-depth.png")
            depthView.enabled = false
            val masked = brightPixels(maskPicture)
            assertTrue(
                masked > 0f,
                "the object mask is empty: nothing the game marked reached uMask, so an outline " +
                    "has nothing to find. See screen-mask.png",
            )
            assertTrue(
                masked < HALF,
                "$masked of the frame is in the object mask; only the box was marked, so a mask " +
                    "covering that much is a mask of the whole scene. See screen-mask.png",
            )
            // Depth: the box stands nearer the camera than the open ground behind it, so the two
            // read differently. Which way round is the backend's, and the assertion does not say -
            // it says only that uDepth carries the scene rather than one flat number, which is
            // what "the engine supplies depth" means and all a portable shader may rely on.
            val boxDepth = meanDepth(depthPicture, BOX_LEFT, BOX_RIGHT, BOX_TOP, BOX_BOTTOM)
            val groundDepth = meanDepth(depthPicture, GROUND_LEFT, GROUND_RIGHT, GROUND_TOP, GROUND_BOTTOM)
            println("screen-shader: uDepth over the box $boxDepth, over open ground $groundDepth")
            assertTrue(
                abs(boxDepth - groundDepth) > DEPTH_SEPARATION,
                "the box reads $boxDepth and the open ground reads $groundDepth in uDepth, a " +
                    "difference of less than $DEPTH_SEPARATION. Two surfaces at different " +
                    "distances read the same, so uDepth is not carrying the scene's depth. See " +
                    "screen-depth.png",
            )

            // 4. OUTLINE. On its own, over the unprocessed picture.
            outlineShader.enabled = true
            settle(slot)
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

            // 5. ORDER. Both on: the outline is drawn over an already-paletted picture, so the
            // frame is neither of the two previous ones.
            paletteShader.enabled = true
            settle(slot)
            val both = decode(slot.capture(CaptureRequest()).bytes)
            save(both, "screen-palette-outline.png")
            assertTrue(
                difference(both, paletted) > 0f && difference(both, outlined) > 0f,
                "with both effects on the frame must differ from each one alone",
            )

            // 6. EVERYTHING OFF AGAIN. The chain is a per-frame decision, not a one-way door.
            paletteShader.enabled = false
            outlineShader.enabled = false
            settle(slot)
            val restored = decode(slot.capture(CaptureRequest()).bytes)
            assertEquals(
                0f,
                difference(plain, restored),
                "with every effect off the frame must be the one the control measured",
            )

            // 7. EVERY UNIFORM KIND, one at a time.
            //
            // The ticket asks for typed uniforms - float, int, vector, colour, texture handle -
            // and a shader that merely compiles proves none of them arrived. So each is changed on
            // its own and the frame has to change with it. A kind whose upload was wrong or
            // missing leaves the picture where the previous step left it, and the step naming that
            // kind is the one that goes red.
            everyUniform.enabled = true
            settle(slot)
            var previousStep = decode(slot.capture(CaptureRequest()).bytes)
            assertTrue(
                difference(plain, previousStep) > 0f,
                "the every-uniform shader changed nothing at all, so no later step means anything",
            )
            val steps7 = listOf<Pair<String, () -> Unit>>(
                "float uAmount" to { amount.value = 0.9f },
                "int uSteps" to { steps.value = 2 },
                "vec2 uShift" to { shift.x = SHIFT },
                "colour uTint" to { tint.value = Rgba.of(0.1f, 0.9f, 0.4f, 1f) },
                "texture uRamp" to { ramp.value = rampB },
            )
            for ((kind, change) in steps7) {
                change()
                settle(slot)
                val next = decode(slot.capture(CaptureRequest()).bytes)
                assertTrue(
                    difference(previousStep, next) > 0f,
                    "changing the $kind uniform left the frame exactly as it was, so that kind " +
                        "is not reaching the shader",
                )
                previousStep = next
            }
            everyUniform.enabled = false
            settle(slot)
            assertEquals(
                0f,
                difference(plain, decode(slot.capture(CaptureRequest()).bytes)),
                "the every-uniform shader left something behind when it was switched off",
            )

            // 8. A DRIVER'S REFUSAL, reported at the author's line.
            //
            // Compiled straight on the render thread rather than through a second pipeline,
            // because this JVM has one context and the fixture above is using it.
            val broken = UdeaShader.fragment(
                path = "shaders/broken.frag",
                source = BROKEN_SOURCE,
            )
            val failure = refusal {
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

            // 9. A UNIFORM THE SOURCE NEVER DECLARES, with the did-you-mean the contract requires.
            val misspelled = UdeaShader.fragment(
                path = "shaders/misspelled.frag",
                source = MISSPELLED_SOURCE,
            ) {
                float("uLevls", 4f)
            }
            val notDeclared = refusal {
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

    /**
     * The [ScreenShaderException] [block] raised, whatever the render thread wrapped it in.
     *
     * A shader is compiled on the render thread, and waiting for that work hands back whatever
     * the executor wrapped the failure in - an `ExecutionException` here - so asserting on the
     * type directly asserts on the wrapper rather than on the diagnostic. Unwrapping keeps the
     * assertion about the thing the contract is about: the rule id, the author's file and the
     * author's line. It fails rather than returning null when there is no such cause, so a
     * refusal that stopped happening is a red test and not a silent pass.
     */
    private fun refusal(block: () -> Unit): ScreenShaderException {
        val thrown = assertFailsWith<Throwable> { block() }
        var cause: Throwable? = thrown
        while (cause != null && cause !is ScreenShaderException) cause = cause.cause
        return cause as? ScreenShaderException
            ?: fail("expected a screen shader refusal somewhere in the causes; got $thrown")
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

    /** The share of pixels brighter than half: how much of a mask picture is "something here". */
    private fun brightPixels(image: BufferedImage): Float {
        var bright = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (luminance(image.getRGB(x, y)) > HALF_LEVEL) bright++
            }
        }
        return bright.toFloat() / (image.width * image.height)
    }

    /**
     * The mean depth over a rectangle of a [DEPTH_VIEW] capture, in depth-buffer units.
     *
     * Undoes the two-byte encoding the view shader wrote: red carries the whole, green the
     * remainder, so the pair is worth about 16 bits where the capture's own channel is worth 8.
     */
    private fun meanDepth(image: BufferedImage, left: Int, right: Int, top: Int, bottom: Int): Float {
        var total = 0.0
        var count = 0
        for (y in top until bottom) {
            for (x in left until right) {
                val pixel = image.getRGB(x, y)
                val high = (pixel ushr 16) and 0xFF
                val low = (pixel ushr 8) and 0xFF
                total += (high + low / BYTE_LEVELS) / BYTE_LEVELS
                count++
            }
        }
        return (total / count).toFloat()
    }

    /**
     * A [DEPTH_VIEW] capture as a grey picture a person can read: the decoded depth, stretched so
     * the frame's own nearest and furthest are white and black.
     *
     * Stretched from the frame rather than by a chosen number, so there is no constant here that
     * could be picked to make a picture look right. A frame of one flat depth comes out uniformly
     * black, which is what an empty depth input should look like.
     */
    private fun normalised(image: BufferedImage): BufferedImage {
        val depths = FloatArray(image.width * image.height)
        var low = Float.MAX_VALUE
        var high = -Float.MAX_VALUE
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val depth = meanDepth(image, x, x + 1, y, y + 1)
                depths[y * image.width + x] = depth
                if (depth < low) low = depth
                if (depth > high) high = depth
            }
        }
        println("screen-shader: uDepth ranges from $low to $high across the frame")
        val span = high - low
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val scaled = if (span <= 0f) 0f else (depths[y * image.width + x] - low) / span
                val level = (scaled * FULL_LEVEL).toInt().coerceIn(0, FULL_LEVEL.toInt())
                out.setRGB(x, y, (level shl 16) or (level shl 8) or level)
            }
        }
        return out
    }

    /** The mean luminance of a rectangle: what one region of a diagnostic picture reads. */
    private fun meanLuminance(image: BufferedImage, left: Int, right: Int, top: Int, bottom: Int): Float {
        var total = 0f
        var count = 0
        for (y in top until bottom) {
            for (x in left until right) {
                total += luminance(image.getRGB(x, y))
                count++
            }
        }
        return total / count
    }

    private fun luminance(pixel: Int): Float {
        val r = (pixel ushr 16) and 0xFF
        val g = (pixel ushr 8) and 0xFF
        val b = pixel and 0xFF
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /**
     * Captures until two in a row are identical, so what follows is measuring the scene.
     *
     * Called once at the start, to get past the frames a context draws before it has the world in
     * it, and again after every change to a shader's `enabled`, because that flag is read on the
     * render thread and a frame that had already begun finishes with the old value. Without it,
     * the capture after a toggle is sometimes the frame *before* the toggle - which is how this
     * test passed once and failed the next run with the production code unchanged.
     *
     * Not the control: this only establishes that the picture has stopped moving. The control is
     * the pair captured *after* the first call, whose difference is asserted to be zero - so "the
     * scene is steady" is still a claim something can fail on. Nor does it hide a broken effect:
     * a shader that draws nothing settles to the unprocessed frame, and every assertion here is
     * that the frame *changed*.
     */
    private fun settle(slot: FrameCaptureSlot) {
        var previous = decode(slot.capture(CaptureRequest()).bytes)
        repeat(SETTLE_TRIES) {
            val next = decode(slot.capture(CaptureRequest()).bytes)
            if (difference(previous, next) == 0f) return
            previous = next
        }
        error("the scene never settled over $SETTLE_TRIES captures; it is not a still picture")
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

        /** Captures allowed for the picture to stop changing. A still scene needs very few. */
        const val SETTLE_TRIES = 12

        /** Three channels compared per pixel; alpha is stomped to opaque by the capture. */
        const val CHANNELS = 3

        /** One level of 0..255 either way, which is where an 8-bit round trip lands. */
        const val ROUNDING = 1

        /**
         * How far from the unprocessed frame the black-and-white palette has to move it.
         *
         * A lit grey-and-sand scene is shaded across its whole range, and snapping it to three
         * fixed colours moves every pixel to the nearest of them - tens of levels per channel on
         * average. Set well under what the effect actually does and well over the zero the control
         * measured, so the assertion fails if the effect stops working rather than when a driver
         * rounds differently.
         */
        const val PALETTE_MAGNITUDE = 10f

        /** Nearly every pixel: what an unprocessed lit frame has that is not a palette colour. */
        const val MOST_PIXELS = 0.9f

        /** Levels of luminance a pixel must lose to count as outlined. */
        const val DARKENED = 20f

        /** Half of 0..255: "this pixel is on" in a one-or-nothing picture. */
        const val HALF_LEVEL = 128f

        /** One byte, as a divisor: what [meanDepth] undoes and [normalised] scales into. */
        const val BYTE_LEVELS = 255.0

        /** The top of an 8-bit channel. */
        const val FULL_LEVEL = 255f

        /** Half the frame. The box is far smaller than that; the whole scene is far more. */
        const val HALF = 0.5f

        /**
         * In depth-buffer units, between the box's depth and the ground's.
         *
         * Small because the numbers themselves are: reversed depth puts a scene seven units deep
         * at about `0.014`, and the two regions measured here are about `0.002` apart. The claim
         * is that two distances are distinguishable, not that they are far apart. A depth input
         * that was one flat number - a stand-in, an unwritten buffer - reads exactly `0` apart,
         * which is the case this has to catch.
         */
        const val DEPTH_SEPARATION = 0.0005f

        /** Draws the object mask itself: white where a marked entity was drawn, black elsewhere. */
        val MASK_VIEW: String = """
            vec4 udeaMain(vec2 uv) {
                return vec4(vec3(udeaMasked(uv)), 1.0);
            }
        """.trimIndent()

        /**
         * Writes `uDepth`'s red channel out as two bytes - the whole in red, the remainder in
         * green - so the test reads the depth back at roughly 16 bits rather than 8.
         *
         * Straight out it is unreadable, and that is the depth buffer's shape rather than a
         * fault: this backend draws reversed, so the far plane is `0` and a surface seven units
         * away is about `0.014`. The whole scene lands in the bottom 2% of an 8-bit capture, where
         * two distances a metre apart differ by half a level and rounding decides the answer.
         * [meanDepth] undoes the encoding and [normalised] makes the picture a person looks at.
         */
        val DEPTH_VIEW: String = """
            vec4 udeaMain(vec2 uv) {
                float d = texture(uDepth, uv).r;
                return vec4(floor(d * 255.0) / 255.0, fract(d * 255.0), 0.0, 1.0);
            }
        """.trimIndent()

        /**
         * Reads every uniform kind the API offers, so that changing any one of them changes the
         * picture. Deliberately not a nice-looking effect: it is a probe, not a style.
         */
        val EVERY_UNIFORM: String = """
            uniform float uAmount;
            uniform int uSteps;
            uniform vec2 uShift;
            uniform vec4 uTint;
            uniform sampler2D uRamp;

            vec4 udeaMain(vec2 uv) {
                vec3 base = texture(uColor, uv + uShift).rgb;
                vec3 stepped = floor(base * float(uSteps)) / float(uSteps);
                vec3 band = texture(uRamp, vec2(uv.x, 0.5)).rgb;
                return vec4(mix(stepped, uTint.rgb * band, uAmount), 1.0);
            }
        """.trimIndent()

        /** Texels across each ramp the every-uniform shader samples. */
        const val RAMP_TEXELS = 2

        /** Two texels, RGBA: blue then white. */
        val RAMP_A: ByteArray = byteArrayOf(0, 0, -1, -1, -1, -1, -1, -1)

        /** Two texels, RGBA: green then red. A different picture from [RAMP_A] at every uv. */
        val RAMP_B: ByteArray = byteArrayOf(0, -1, 0, -1, -1, 0, 0, -1)

        /** A sixteenth of the frame, in uv: far enough that a lit gradient reads differently. */
        const val SHIFT = 0.0625f

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
