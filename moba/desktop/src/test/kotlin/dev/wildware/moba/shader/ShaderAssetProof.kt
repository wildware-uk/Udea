package dev.wildware.moba.shader

import dev.wildware.moba.MobaAssets
import dev.wildware.moba.MobaScreenEffects
import dev.wildware.udea.assets.Shader
import dev.wildware.udea.assets.reference
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
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * `runShaderAssetProof`: GLSL a game declared as an asset reaches the GPU, and the picture proves it.
 *
 * ## What is different from `ShaderProof`
 *
 * `ShaderProof` registers the engine's two built-in effects, whose GLSL is a constant inside
 * `udea-render`. This one registers a shader whose GLSL is a **file the game wrote**,
 * `moba/game/assets/shaders/scanlines.frag`, which the asset build read, checked and packed into
 * `udea/assets.udeapak`. Nothing in this process opens that file: the text comes back out of
 * `MobaAssets.registry`, the same graph the sprites and the sounds come out of, through the same
 * `BundleReader`. That whole path is what the ticket asked for, and the only way to see it work
 * is to look at a frame it changed.
 *
 * ## Why the measurement is a *prediction* and not "the frame moved"
 *
 * The shader dims odd rows by `uStrength` and leaves even rows alone. So:
 *
 * - **the rows that changed are exactly the rows that could change**: all of one parity, and a row
 *   of that parity moved if and only if it had colour in it to dim. A shader that ran but did
 *   something else - or a capture that picked up a different frame - does not produce that. It is
 *   deliberately not "half the rows": see `requireDimmedRows` for the sky that taught me why;
 * - **the size of the change tracks `uStrength`**. Doubling it from 0.25 to 0.5 has to roughly
 *   double the mean difference, because the change per dimmed pixel is `uStrength x its
 *   brightness`. That is the check that a red of the wrong size cannot pass, and it is also the
 *   proof that the uniform declared in Kotlin reached the program compiled from the asset's text.
 *
 * The control is two unprocessed captures of the same paused scene, which must differ by exactly
 * zero; without it "the frame moved by N" could be the renderer's own noise.
 *
 * ## Why it lives in `:moba:desktop`
 *
 * For the reason `ShaderProof` gives: this project is not in `ModuleGraphRules.GL_ALLOWED_PROJECTS`,
 * so `UDEA-MG-002` refuses `de.fabmax.kool:*` on it. A game's-eye view is a build gate here rather
 * than a claim.
 *
 * Writes its pictures into `-Dudea.shaderassetproof.dir` and fails the task, loudly, on any figure
 * that is not what the effect has to be worth.
 */
object ShaderAssetProof {

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(property("udea.shaderassetproof.dir")).apply { mkdirs() }

        // --- the asset, before anything is drawn ------------------------------------------
        //
        // Read out of the packed bundle this process ships with. Asserted here as well as in the
        // pictures below, because "the GLSL travelled in the pack" is the acceptance criterion and
        // a screenshot alone cannot say where the text came from.
        val packed = MobaAssets.registry[reference<Shader>(SHADER_ID)]
        say("asset: `$SHADER_ID` is ${packed.source.length} characters of GLSL from `${packed.file}`")
        require(packed.file.value == SHADER_FILE) {
            "the packed shader names `${packed.file}` rather than `$SHADER_FILE`"
        }
        require(MARKER in packed.source) {
            "the packed GLSL does not contain the game's own uniform `$MARKER`, so it is not the " +
                "text of $SHADER_FILE: ${packed.source.take(200)}"
        }
        require("#version" !in packed.source) {
            "the packed GLSL states a #version; the build should have refused it with UDEA0041"
        }

        val effect = MobaScreenEffects.scanlines(MobaAssets.registry).also { it.shader.enabled = false }
        require(effect.shader.path == SHADER_FILE) {
            "a compile failure would name `${effect.shader.path}` rather than the author's file"
        }

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
        registry.screenPass(effect.shader)

        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "moba-shader-asset-proof",
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
            val ground = ModelMaterial(flat(Rgba.of(0.45f, 0.44f, 0.4f), "asset-proof-ground"), roughness = 0.9f)
            val crate = ModelMaterial(flat(Rgba.of(0.78f, 0.66f, 0.4f), "asset-proof-crate"), roughness = 0.6f)
            host.game.world.entity {
                it += Transform3D(x = 0f, y = 0f, z = 0f)
                it += ModelRenderer(ModelMesh.plane(GROUND, GROUND), ground)
            }
            for (at in BOX_POSITIONS) {
                host.game.world.entity {
                    it += Transform3D(x = at, y = 0f, z = BOX / 2f)
                    it += ModelRenderer(ModelMesh.box(BOX, BOX, BOX), crate)
                }
            }
            backend.drive(host)

            val slot = checkNotNull(backend.pipeline?.capture) { "this pipeline cannot be captured" }
            host.loop.paused = true

            // --- the control -------------------------------------------------------------
            settle(slot)
            val plain = decode(slot.capture(CaptureRequest()).bytes)
            val plainAgain = decode(slot.capture(CaptureRequest()).bytes)
            write(plain, out, "issue269-1-no-effect.png")
            val control = difference(plain, plainAgain)
            say("control: two unprocessed captures of the paused scene differ by $control levels/channel")
            require(control == 0f) {
                "the control failed: two unprocessed frames differ by $control levels per channel, " +
                    "so every figure below would be measuring the renderer rather than the shader"
            }

            // --- the shader, at its default strength -------------------------------------
            effect.shader.enabled = true
            settle(slot)
            val quarter = decode(slot.capture(CaptureRequest()).bytes)
            write(quarter, out, "issue269-2-scanlines-quarter.png")
            val quarterMoved = difference(plain, quarter)
            val changed = changedRows(plain, quarter)
            say(
                "scanlines at ${MobaScreenEffects.DEFAULT_STRENGTH}: frame moved $quarterMoved " +
                    "levels/channel, ${changed.size} of $HEIGHT rows changed",
            )
            require(quarterMoved > MIN_MOVE) {
                "the asset-declared shader moved the frame by $quarterMoved levels per channel, " +
                    "under the $MIN_MOVE it has to be worth against a control of 0"
            }
            requireDimmedRows(plain, changed)
            // The absolute magnitude, predicted from the .frag rather than from the picture.
            // `dim = 1.0 - uStrength`, and the frame is plain 8-bit, so a dimmed channel comes
            // back at exactly `(1 - uStrength)` of what it was.
            //
            // "Plain 8-bit" is the load-bearing half and it is not asserted here: `ShaderProof`
            // asserts it, by requiring a palette shader that writes `vec3(0.35, 0.33, 0.30)` to
            // come back within one level of `(0.35 * 255).toInt()`. In an sRGB-encoded frame
            // `after / before` would not be `dim` at all and the band below would be wrong for a
            // *working* shader. **If `ShaderProof.offPalette` ever moves, the band here moves
            // with it** - nothing in the code says so, which is why it is said here.
            val quarterFactor = dimFactor(plain, quarter, changed)
            say(
                "scanlines at ${MobaScreenEffects.DEFAULT_STRENGTH}: dimmed channels are " +
                    "${quarterFactor.mean}x what they were, over ${quarterFactor.samples} samples",
            )
            requireFactor(quarterFactor, MobaScreenEffects.DEFAULT_STRENGTH)
            // Printed so the floor can be judged rather than taken on trust: a run that clears
            // 100,000 by a hair means the floor is decoration, and one that clears it by an order
            // of magnitude means it is a guard. `ShaderAssetProofChecksTest` is the other side -
            // a measurement *below* the floor, failing with the floor's own words.
            say("headroom: ${quarterFactor.samples} samples against a floor of $MIN_SAMPLES")

            // --- the magnitude: double the strength, double the change --------------------
            //
            // The uniform is written from this process, into the program compiled from the
            // asset's text. If the handle were bound to nothing - the failure `UDEA0040` exists
            // for - the frame would not move at all between these two captures.
            effect.strength.value = DOUBLE_STRENGTH
            settle(slot)
            val half = decode(slot.capture(CaptureRequest()).bytes)
            write(half, out, "issue269-3-scanlines-half.png")
            val halfMoved = difference(plain, half)
            val ratio = halfMoved / quarterMoved
            say("scanlines at $DOUBLE_STRENGTH: frame moved $halfMoved levels/channel, ratio $ratio")
            require(ratio in RATIO_LOW..RATIO_HIGH) {
                "doubling uStrength moved the frame ${ratio}x, not about 2x. Either the uniform " +
                    "is not reaching the program or the effect is not the one in the .frag."
            }
            require(changedRows(plain, half) == changed) {
                "the rows the shader touches changed with its strength, which no version of " +
                    "this .frag does"
            }
            val halfFactor = dimFactor(plain, half, changed)
            say(
                "scanlines at $DOUBLE_STRENGTH: dimmed channels are ${halfFactor.mean}x what " +
                    "they were, over ${halfFactor.samples} samples",
            )
            requireFactor(halfFactor, DOUBLE_STRENGTH)

            // --- off again ----------------------------------------------------------------
            effect.shader.enabled = false
            settle(slot)
            val restored = decode(slot.capture(CaptureRequest()).bytes)
            write(restored, out, "issue269-4-off-again.png")
            val back = difference(plain, restored)
            say("off again: the frame differs from the first unprocessed capture by $back levels/channel")
            require(back == 0f) { "turning the effect off did not restore the unprocessed frame" }

            say("all checks passed; pictures in $out")
        } finally {
            backend.close()
        }
    }

    // --- helpers -------------------------------------------------------------------------

    private fun property(name: String): String =
        checkNotNull(System.getProperty(name)) { "-D$name was not set" }

    private fun say(line: String) {
        println("shader-asset-proof: $line")
    }

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

    /** Captures until two in a row are identical; see `ShaderProof.settle` for why. */
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

    private fun write(image: BufferedImage, dir: File, name: String) {
        ImageIO.write(image, "png", File(dir, name))
    }

    /** Mean absolute difference per colour channel, in levels of 0..255. Zero is byte-identical. */
    private fun difference(a: BufferedImage, b: BufferedImage): Float {
        var total = 0L
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                total += channelDifference(a.getRGB(x, y), b.getRGB(x, y))
            }
        }
        return total.toFloat() / (a.width * a.height * CHANNELS)
    }

    /**
     * The rows the shader touched are **exactly** the rows it could have touched.
     *
     * ## Why this is not "exactly half the rows"
     *
     * It was, and the first real run said `166 rows changed; a shader dimming every other row of
     * a 360-row frame changes exactly 180`. The shader was working. The prediction was wrong: the
     * top 28 rows of this scene are sky, pure `(0, 0, 0)`, and `0 * 0.75` is `0`, so 14 rows of
     * the dimmed parity are dimmed to no visible effect. 180 - 14 = 166, and the arithmetic of the
     * explanation matches the size of the discrepancy exactly, which is what makes it an
     * explanation rather than a story.
     *
     * A count is a claim about the scene as much as about the shader, and this scene has a sky in
     * it. So the count is gone and the property is asserted instead:
     *
     * 1. **every** changed row has the same parity - a shader that dimmed both parities, or the
     *    wrong one, or a band, fails here;
     * 2. a row of that parity changed **if and only if** it had any colour in it to dim. That is
     *    the biconditional, so it fails both ways: a bright row that did not change is a hole in
     *    the effect, and a black row that did change is the shader writing something other than
     *    `colour * dim`.
     *
     * The ambiguous middle is refused rather than tolerated. A row whose brightest channel is 1
     * or 2 may or may not move a level under `* 0.75` depending on how the hardware rounds, so a
     * scene containing one would make step 2 a coin toss; such a row fails the run outright and
     * says so. In this scene every row is either `0` or above 80, so nothing is near it.
     */
    private fun requireDimmedRows(plain: BufferedImage, changed: List<Int>) {
        require(changed.isNotEmpty()) { "no row changed at all, so the shader did not run" }
        val parity = changed.first() % 2
        require(changed.all { it % 2 == parity }) {
            "the changed rows are not all the same parity, so whatever ran was not a scanline " +
                "shader: ${changed.take(8)} of ${changed.size}"
        }
        val expected = mutableListOf<Int>()
        for (y in 0 until plain.height) {
            if (y % 2 != parity) continue
            val peak = rowPeak(plain, y)
            require(peak == 0 || peak >= MIN_CHANNEL) {
                "row $y's brightest channel is $peak, between black and $MIN_CHANNEL. Whether " +
                    "`* ${1f - MobaScreenEffects.DEFAULT_STRENGTH}` moves it by a whole level is " +
                    "a rounding question, so this scene cannot answer 'did this row change' and " +
                    "the check below would be a coin toss. Brighten the scene or move the camera."
            }
            if (peak > 0) expected += y
        }
        val black = (0 until plain.height).count { it % 2 == parity && rowPeak(plain, it) == 0 }
        say(
            "rows: ${changed.size} changed, of ${expected.size + black} at parity $parity, " +
                "of which $black are pure black and cannot be dimmed",
        )
        require(changed == expected) {
            "the rows that changed are not the rows that could change. Changed but black, or " +
                "bright but unchanged: ${(changed - expected.toSet()) + (expected - changed.toSet())}"
        }
    }

    /** The brightest of a row's red, green and blue. Alpha is not colour and is not dimmed. */
    private fun rowPeak(image: BufferedImage, y: Int): Int {
        var peak = 0
        for (x in 0 until image.width) {
            val pixel = image.getRGB(x, y)
            for (shift in CHANNEL_SHIFTS) {
                val value = (pixel ushr shift) and 0xFF
                if (value > peak) peak = value
            }
        }
        return peak
    }

    /**
     * How much of its brightness a dimmed channel kept, averaged, and over how many samples.
     *
     * This is the measurement the proof turns on, so it is worth saying what it is *not*. It is
     * not a difference and it is not a ratio between two measurements. It is the shader's own
     * arithmetic read back out of the frame - the body writes `colour * dim`, so `after / before`
     * **is** `dim` - and `dim` is `1.0 - uStrength`, a number that appears in the `.frag` and in
     * `MobaScreenEffects` and in no capture anywhere.
     *
     * That is what makes it survive the mutation a ratio cannot. A `.frag` that dimmed by
     * `uStrength * 0.5` would still move the frame, would still touch every other row, and would
     * still double when the uniform doubled - and it would land here at 0.875 where 0.75 is
     * required.
     *
     * Only channels at [MIN_CHANNEL] or above are sampled. A channel of 3 coming back as 2 is a
     * factor of 0.67 or of 1.0 depending on which way the hardware rounded, and averaging those in
     * would widen the tolerance until it stopped meaning anything.
     */
    internal fun dimFactor(before: BufferedImage, after: BufferedImage, rows: List<Int>): DimFactor {
        var total = 0.0
        var samples = 0
        for (y in rows) {
            for (x in 0 until before.width) {
                val left = before.getRGB(x, y)
                val right = after.getRGB(x, y)
                for (shift in CHANNEL_SHIFTS) {
                    val was = (left ushr shift) and 0xFF
                    if (was < MIN_CHANNEL) continue
                    total += ((right ushr shift) and 0xFF).toDouble() / was
                    samples++
                }
            }
        }
        return DimFactor(if (samples == 0) Double.NaN else total / samples, samples)
    }

    /** What [dimFactor] measured. [samples] is carried so an empty measurement cannot read as a pass. */
    internal class DimFactor(val mean: Double, val samples: Int)

    /** Fails unless [measured] is `1 - strength`, which is what the `.frag` multiplies by. */
    internal fun requireFactor(measured: DimFactor, strength: Float) {
        require(measured.samples >= MIN_SAMPLES) {
            "only ${measured.samples} channels were bright enough to take a dim factor from, " +
                "under the $MIN_SAMPLES this needs. The scene is too dark for the check to mean " +
                "anything, which is a failure and not a pass."
        }
        val predicted = 1.0 - strength
        require(abs(measured.mean - predicted) <= FACTOR_TOLERANCE) {
            "a dimmed channel came back at ${measured.mean}x what it was. The .frag multiplies by " +
                "1.0 - uStrength, so at a strength of $strength that has to be $predicted " +
                "(+-$FACTOR_TOLERANCE). Either the uniform is not the one the shader reads, or the " +
                "shader is not the one in moba/game/assets/shaders/scanlines.frag."
        }
    }

    /** The rows of [b] that differ from [a] at all, in order. */
    private fun changedRows(a: BufferedImage, b: BufferedImage): List<Int> =
        (0 until a.height).filter { y ->
            (0 until a.width).any { x -> channelDifference(a.getRGB(x, y), b.getRGB(x, y)) > 0 }
        }

    private fun channelDifference(left: Int, right: Int): Int =
        abs(((left ushr 16) and 0xFF) - ((right ushr 16) and 0xFF)) +
            abs(((left ushr 8) and 0xFF) - ((right ushr 8) and 0xFF)) +
            abs((left and 0xFF) - (right and 0xFF))

    /** The asset the game declared, and the file behind it. Spelled here to be checked. */
    private const val SHADER_ID = "shaders/scanlines"
    private const val SHADER_FILE = "shaders/scanlines.frag"

    /** A token that is in the game's `.frag` and in no engine shader. */
    private const val MARKER = "uStrength"

    private const val WIDTH = 640
    private const val HEIGHT = 360
    private const val CHANNELS = 3
    private const val SETTLE_TRIES = 12

    /**
     * How far dimming half the rows by a quarter has to move the frame.
     *
     * Predicted rather than picked: the change is `0.25 x brightness` on half the pixels, so over
     * the whole frame it is about `0.125 x mean channel`. This scene's mean channel is well over
     * 40 levels, which puts the figure above 5; the bar is set at 2 so that a driver rounding
     * differently does not fail it while a shader that stopped running (0) does.
     */
    private const val MIN_MOVE = 2f

    private const val DOUBLE_STRENGTH = MobaScreenEffects.DEFAULT_STRENGTH * 2f

    /**
     * The window the doubled strength has to land in.
     *
     * Exactly 2x in real arithmetic. The band allows for the one level of rounding each dimmed
     * pixel can pick up on the way to an 8-bit frame, and is narrow enough that "the frame moved
     * for some other reason" does not fit inside it.
     */
    private const val RATIO_LOW = 1.85f
    private const val RATIO_HIGH = 2.15f

    /** Red, green and blue, as bit offsets into a packed ARGB pixel. */
    private val CHANNEL_SHIFTS = intArrayOf(16, 8, 0)

    /**
     * The dimmest channel worth taking a factor from.
     *
     * At 32 levels the one level of rounding an 8-bit write can pick up is about 3% of the value,
     * which sits inside [FACTOR_TOLERANCE]. Below it the quantisation *is* the measurement.
     */
    internal const val MIN_CHANNEL = 32

    /**
     * How far the measured factor may sit from `1 - uStrength`.
     *
     * Rounding alone is worth under 0.02 at [MIN_CHANNEL] and far less above it. Narrow on
     * purpose: a `.frag` dimming by `uStrength * 0.5` lands at 0.875 against a required 0.75,
     * which is six times this away.
     */
    internal const val FACTOR_TOLERANCE = 0.02

    /**
     * The fewest channel samples the factor may be averaged over.
     *
     * A mean over nothing is NaN, and every comparison against NaN is false, so an empty
     * measurement would fail anyway - but it would fail saying the wrong thing. This says the
     * right thing: the scene was too dark to measure, which is a defect in the proof rather than
     * in the shader. 180 rows of 640 pixels is 345,600 channels at full coverage; this asks for
     * under a third of that.
     */
    internal const val MIN_SAMPLES = 100_000

    private const val GROUND = 60f
    private const val BOX = 2.2f
    private val BOX_POSITIONS = floatArrayOf(-2.6f, 2.6f)
}
