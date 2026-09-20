package dev.wildware.moba.shader

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
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelLight
import dev.wildware.udea.render.model.ModelMaterial
import dev.wildware.udea.render.model.ModelMesh
import dev.wildware.udea.render.model.ModelRenderSystem
import dev.wildware.udea.render.model.ModelRenderer
import dev.wildware.udea.render.shader.ScreenEffects
import dev.wildware.udea.render.shader.UdeaShader
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * `runShaderProof`: a game registers screen effects of its own, and the frames prove they ran.
 *
 * ## Why this lives in `moba:desktop` and not in `udea-render`
 *
 * Issue #266's first acceptance criterion is *"a game outside this repository registers a
 * post-process pass, **with no Kool type in its source**, and its output differs from the
 * unprocessed frame"*. `udea-render`'s own test source set has Kool on its classpath, so a test
 * there could only ever *grep* for the absence of a Kool import. `:moba:desktop` is not one of
 * `ModuleGraphRules.GL_ALLOWED_PROJECTS`, so `UDEA-MG-002` refuses `de.fabmax.kool:*` on this
 * project at all - which makes "no Kool type in the game's source" a build gate rather than a
 * claim. This file names `UdeaShader`, `Rgba`, `SpriteTexture`, `ModelMesh` and nothing else; it
 * *could not compile* if it named a Kool type.
 *
 * ## What it measures, and the control
 *
 * "The output differs from the unprocessed frame" is a measurement, so it is reported as a number
 * with a control beside it: two **unprocessed** captures of the same paused scene, whose
 * difference must be exactly zero. Without that, "the palette moved the frame by N" could be the
 * renderer's own frame-to-frame noise, and the assertion could not fail.
 *
 * Every figure below is the mean absolute difference per colour channel, in levels of 0..255.
 *
 * ## The scene
 *
 * A wide lit ground plane with two boxes standing on it. **Only the boxes are in the object mask**
 * (`ModelRenderer.mask`), so an outline that finds them and leaves the ground alone is finding
 * them from the mask and not from brightness or from the edge of the world.
 *
 * Writes five pictures into `-Dudea.shaderproof.dir` and fails the task, loudly, if any figure is
 * not what the effects have to be worth.
 */
object ShaderProof {

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(property("udea.shaderproof.dir")).apply { mkdirs() }

        // A game's palette. Four colours, so "every pixel is one of these" is a real constraint
        // rather than a coincidence of a two-entry ramp.
        val palette = listOf(
            Rgba.of(0.09f, 0.09f, 0.13f),
            Rgba.of(0.35f, 0.33f, 0.30f),
            Rgba.of(0.66f, 0.58f, 0.42f),
            Rgba.of(0.93f, 0.89f, 0.76f),
        )
        val paletteEffect = ScreenEffects.palette(palette).apply { enabled = false }
        val outlineEffect = ScreenEffects.outline(Rgba.of(0f, 0f, 0f, 1f)).apply { enabled = false }

        val camera = ModelCamera().apply { lookAt(0f, -8f, 4.5f, 0f, 0f, 0.6f) }
        val light = ModelLight(
            directionX = 0.5f,
            directionY = 0.8f,
            directionZ = -0.6f,
            intensity = 3.0f,
            ambient = Rgba.of(0.16f, 0.16f, 0.2f),
        )

        val registry = RenderRegistry()
        registry.register(RenderPhase.World, { resources -> ModelRenderSystem(resources, camera, light) })
        // The ordered pass list a game configures. Palette first, then the outline over it.
        registry.screenPass(paletteEffect)
        registry.screenPass(outlineEffect)

        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "moba-shader-proof",
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
            val ground = ModelMaterial(flat(Rgba.of(0.45f, 0.44f, 0.4f), "proof-ground"), roughness = 0.9f)
            val crate = ModelMaterial(flat(Rgba.of(0.78f, 0.66f, 0.4f), "proof-crate"), roughness = 0.6f)
            host.game.world.entity {
                it += Transform3D(x = 0f, y = 0f, z = 0f)
                it += ModelRenderer(ModelMesh.plane(GROUND, GROUND), ground)
            }
            for (at in BOX_POSITIONS) {
                host.game.world.entity {
                    it += Transform3D(x = at, y = 0f, z = BOX / 2f)
                    it += ModelRenderer(ModelMesh.box(BOX, BOX, BOX), crate).apply { mask = true }
                }
            }
            backend.drive(host)

            val slot = checkNotNull(backend.pipeline?.capture) { "this pipeline cannot be captured" }
            host.loop.paused = true

            // --- the control ---------------------------------------------------------------
            val plain = decode(slot.capture(CaptureRequest()).bytes)
            val plainAgain = decode(slot.capture(CaptureRequest()).bytes)
            write(plain, out, "issue266-1-unprocessed.png")
            val control = difference(plain, plainAgain)
            say("control: two unprocessed captures of the paused scene differ by $control levels/channel")
            require(control == 0f) {
                "the control failed: two unprocessed frames differ by $control levels per channel, " +
                    "so every figure below would be measuring the renderer rather than a shader"
            }
            val plainOffPalette = offPalette(plain, palette)
            say("control: ${percent(plainOffPalette)} of the unprocessed frame is NOT a palette colour")
            require(plainOffPalette > MOST) {
                "the unprocessed frame is already made of palette colours, so the palette check " +
                    "below could not fail"
            }

            // --- the palette ----------------------------------------------------------------
            paletteEffect.enabled = true
            val paletted = decode(slot.capture(CaptureRequest()).bytes)
            write(paletted, out, "issue266-2-palette.png")
            val paletteOff = offPalette(paletted, palette)
            val paletteMoved = difference(plain, paletted)
            say("palette: ${percent(paletteOff)} off-palette, frame moved $paletteMoved levels/channel")
            require(paletteOff == 0f) {
                "${percent(paletteOff)} of the paletted frame is not one of the ${palette.size} " +
                    "palette colours"
            }
            require(paletteMoved > PALETTE_MAGNITUDE) {
                "the palette moved the frame by $paletteMoved levels per channel, which is under " +
                    "the $PALETTE_MAGNITUDE this effect has to be worth against a control of 0"
            }

            // --- the outline ----------------------------------------------------------------
            paletteEffect.enabled = false
            outlineEffect.enabled = true
            val outlined = decode(slot.capture(CaptureRequest()).bytes)
            write(outlined, out, "issue266-3-outline.png")
            val onBoxes = darkened(plain, outlined, BOXES_LEFT, BOXES_RIGHT, BOXES_TOP, BOXES_BOTTOM)
            val onGround = darkened(plain, outlined, GROUND_LEFT, GROUND_RIGHT, GROUND_TOP, GROUND_BOTTOM)
            say("outline: $onBoxes pixels darkened around the boxes, $onGround on open ground")
            require(onBoxes > 0) {
                "the outline darkened nothing around the boxes: the mask is empty or the effect " +
                    "did not run"
            }
            require(onGround == 0) {
                "$onGround pixels of open ground were darkened, and the ground is not in the " +
                    "mask, so the outline is finding edges from something other than the mask"
            }

            // --- both, in the order the game registered them ---------------------------------
            paletteEffect.enabled = true
            val both = decode(slot.capture(CaptureRequest()).bytes)
            write(both, out, "issue266-4-palette-and-outline.png")
            say(
                "both: differs from the palette alone by ${difference(both, paletted)} and from " +
                    "the outline alone by ${difference(both, outlined)} levels/channel",
            )
            require(difference(both, paletted) > 0f && difference(both, outlined) > 0f) {
                "with both effects on, the frame is identical to one of them alone"
            }

            // --- off again --------------------------------------------------------------------
            paletteEffect.enabled = false
            outlineEffect.enabled = false
            val restored = decode(slot.capture(CaptureRequest()).bytes)
            write(restored, out, "issue266-5-effects-off-again.png")
            val back = difference(plain, restored)
            say("off again: the frame differs from the first unprocessed capture by $back levels/channel")
            require(back == 0f) { "turning the effects off did not restore the unprocessed frame" }

            // --- the engine owns the #version line ---------------------------------------------
            //
            // A game that writes its own works on the desktop and fails on Android with a driver
            // message its author never sees, so the surface refuses it here, at the call, with the
            // reason. A *driver's* refusal - `ScreenShaderException` with `UDEA0019` and the
            // author's line - is `GlScreenShaderTest`'s to prove: compiling a second shader needs
            // the engine-internal compiler, and this JVM's one graphics context is in use above.
            val versioned = runCatching {
                UdeaShader.fragment(
                    "shaders/versioned.frag",
                    "#version 330\nvec4 udeaMain(vec2 uv) { return vec4(uv, 0.0, 1.0); }",
                )
            }.exceptionOrNull()
            require(versioned is IllegalArgumentException) {
                "a shader that states its own #version must be refused; got $versioned"
            }
            require("OpenGL ES" in versioned.message.orEmpty()) {
                "the refusal must say why the engine owns that line: ${versioned.message}"
            }
            say("refusal: a game-written #version is refused - ${versioned.message?.lines()?.first()}")

            say("all checks passed; pictures in $out")
        } finally {
            backend.close()
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun property(name: String): String =
        checkNotNull(System.getProperty(name)) { "-D$name was not set" }

    private fun say(line: String) {
        println("shader-proof: $line")
    }

    private fun percent(share: Float): String = "${(share * 100f).toInt()}%"

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

    private fun decode(png: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(png))
        ?: error("the captured bytes are not a decodable image")

    private fun write(image: BufferedImage, dir: File, name: String) {
        ImageIO.write(image, "png", File(dir, name))
    }

    /** Mean absolute difference per colour channel, in levels of 0..255. Zero is byte-identical. */
    private fun difference(a: BufferedImage, b: BufferedImage): Float {
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

    /** The share of pixels that are none of [palette]'s colours, allowing one level of rounding. */
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

    /** How many pixels of the rectangle lost noticeable brightness between the two frames. */
    private fun darkened(
        before: BufferedImage,
        after: BufferedImage,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
    ): Int {
        var count = 0
        for (y in top..bottom) {
            for (x in left..right) {
                if (luminance(before.getRGB(x, y)) - luminance(after.getRGB(x, y)) > DARKENED) count++
            }
        }
        return count
    }

    private fun luminance(pixel: Int): Float =
        0.2126f * ((pixel ushr 16) and 0xFF) + 0.7152f * ((pixel ushr 8) and 0xFF) + 0.0722f * (pixel and 0xFF)

    private const val WIDTH = 640
    private const val HEIGHT = 360

    private const val CHANNELS = 3
    private const val ROUNDING = 1

    /** Nearly every pixel of a lit scene is off a four-colour palette. */
    private const val MOST = 0.9f

    /**
     * How far the four-colour palette has to move the frame, in levels per channel.
     *
     * A lit scene snapped to four widely spaced colours moves a long way; this is set well under
     * what the effect does and far above the control's zero, so it fails when the effect stops
     * working rather than when a driver rounds differently.
     */
    private const val PALETTE_MAGNITUDE = 8f

    /** Levels of luminance a pixel must lose to count as outlined. */
    private const val DARKENED = 20f

    private const val GROUND = 60f
    private const val BOX = 2.2f
    private val BOX_POSITIONS = floatArrayOf(-2.6f, 2.6f)

    /** The middle band of the frame, holding both boxes and the ground immediately around them. */
    private const val BOXES_LEFT = 150
    private const val BOXES_RIGHT = 490
    private const val BOXES_TOP = 90
    private const val BOXES_BOTTOM = 300

    /** Open ground: the bottom-left corner, nowhere near a box. */
    private const val GROUND_LEFT = 4
    private const val GROUND_RIGHT = 100
    private const val GROUND_TOP = 260
    private const val GROUND_BOTTOM = 350

}
