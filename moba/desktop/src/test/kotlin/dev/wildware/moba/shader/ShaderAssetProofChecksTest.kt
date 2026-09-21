package dev.wildware.moba.shader

import dev.wildware.moba.MobaScreenEffects
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The arithmetic inside `ShaderAssetProof`, exercised on both sides of every line it draws.
 *
 * ## Why this exists
 *
 * `ShaderAssetProof` needs a GL driver, so it runs as `:moba:desktop:runShaderAssetProof` and not
 * on `check`. Its verdict rests on two numbers with thresholds either side of them - the dim
 * factor a channel came back at, and how many channels that factor was averaged over - and a
 * threshold nobody has watched fail is a threshold nobody knows the direction of.
 *
 * The sample floor is the one that matters. `requireFactor` refuses a measurement taken over
 * fewer than [ShaderAssetProof.MIN_SAMPLES] channels, and every real run clears it by a wide
 * margin, so the *upper* side is demonstrated on every run and the lower side is demonstrated
 * nowhere. That is the shape of a check that passes by construction. So the lower side is here,
 * as a direct call with a deliberately tiny sample - no frame, no driver, no GL - and what it
 * asserts is not only that it fails but **which message it fails with**: a proof that cannot
 * measure has to say so, rather than say the shader is broken.
 *
 * ## What it does not cover
 *
 * Nothing here draws anything. It says what the measurement does with numbers handed to it; the
 * proof itself is what says those numbers came off a GPU running the game's own `.frag`.
 */
class ShaderAssetProofChecksTest {

    @Test
    fun `dimFactor reads the multiplier back out of a before and after frame`() {
        val before = flat(BRIGHT)
        val after = flat(BRIGHT).also { dimRow(it, y = 1, to = THREE_QUARTERS) }

        val measured = ShaderAssetProof.dimFactor(before, after, listOf(1))

        assertEquals(0.75, measured.mean, "150 / 200 is the multiplier the row was dimmed by")
        assertEquals(WIDTH * 3, measured.samples, "one row of $WIDTH pixels, three channels each")
    }

    @Test
    fun `dimFactor ignores channels too dark to divide`() {
        val before = flat(DARK)
        val after = flat(DARK).also { dimRow(it, y = 1, to = DARK - 4) }

        val measured = ShaderAssetProof.dimFactor(before, after, listOf(1))

        assertEquals(0, measured.samples, "every channel is under ${ShaderAssetProof.MIN_CHANNEL}")
        assertTrue(measured.mean.isNaN(), "a mean over nothing is NaN, not zero")
    }

    @Test
    fun `a measurement below the sample floor fails saying the scene was too dark`() {
        val tiny = ShaderAssetProof.DimFactor(mean = 0.75, samples = 12)

        val failure = assertFailsWith<IllegalArgumentException> {
            ShaderAssetProof.requireFactor(tiny, MobaScreenEffects.DEFAULT_STRENGTH)
        }

        // The floor's own words. The factor itself is *correct* here - 0.75 is exactly what a
        // strength of 0.25 predicts - so if the floor were absent this would read as a pass.
        assertContains(failure.message.orEmpty(), "too dark for the check to mean anything")
        assertContains(failure.message.orEmpty(), "only 12 channels")
        assertContains(failure.message.orEmpty(), "${ShaderAssetProof.MIN_SAMPLES}")
        // And it must not blame the shader, which is the whole reason the floor is a separate
        // check rather than left to NaN to trip.
        assertTrue(
            "1.0 - uStrength" !in failure.message.orEmpty(),
            "a proof that could not measure must not report the shader as wrong: ${failure.message}",
        )
    }

    @Test
    fun `an empty measurement reports the floor rather than a NaN comparison`() {
        val nothing = ShaderAssetProof.DimFactor(mean = Double.NaN, samples = 0)

        val failure = assertFailsWith<IllegalArgumentException> {
            ShaderAssetProof.requireFactor(nothing, MobaScreenEffects.DEFAULT_STRENGTH)
        }

        assertContains(failure.message.orEmpty(), "only 0 channels")
    }

    @Test
    fun `the sample floor is exactly where it says it is`() {
        val strength = MobaScreenEffects.DEFAULT_STRENGTH
        val justUnder = ShaderAssetProof.DimFactor(0.75, ShaderAssetProof.MIN_SAMPLES - 1)
        val exactly = ShaderAssetProof.DimFactor(0.75, ShaderAssetProof.MIN_SAMPLES)

        assertFailsWith<IllegalArgumentException> { ShaderAssetProof.requireFactor(justUnder, strength) }
        ShaderAssetProof.requireFactor(exactly, strength)
    }

    @Test
    fun `a factor that is wrong by M7's amount fails naming the shader, not the scene`() {
        // The mutation the absolute check exists to catch: a `.frag` dimming by `uStrength * 0.5`
        // still moves the frame, still touches every other row, and still doubles when the uniform
        // doubles. It lands here, at 0.875 where 0.75 is required.
        val halved = ShaderAssetProof.DimFactor(0.875, ShaderAssetProof.MIN_SAMPLES)

        val failure = assertFailsWith<IllegalArgumentException> {
            ShaderAssetProof.requireFactor(halved, MobaScreenEffects.DEFAULT_STRENGTH)
        }

        assertContains(failure.message.orEmpty(), "moba/game/assets/shaders/scanlines.frag")
        assertTrue(
            "too dark" !in failure.message.orEmpty(),
            "there were plenty of samples; the factor is what is wrong: ${failure.message}",
        )
    }

    @Test
    fun `the tolerance band has a failing case on each side of it`() {
        val strength = MobaScreenEffects.DEFAULT_STRENGTH
        val predicted = 1.0 - strength
        val tolerance = ShaderAssetProof.FACTOR_TOLERANCE
        val samples = ShaderAssetProof.MIN_SAMPLES

        // Inside, close to both edges. Deliberately a hair short of the edge itself: `0.75 + 0.02`
        // is 0.7700000000000000178 in binary, so an "exactly at the edge" case would be asserting
        // something about IEEE-754 rounding rather than about the band.
        val inside = tolerance * 0.9
        ShaderAssetProof.requireFactor(ShaderAssetProof.DimFactor(predicted + inside, samples), strength)
        ShaderAssetProof.requireFactor(ShaderAssetProof.DimFactor(predicted - inside, samples), strength)

        // Outside, on both sides. A band asserted only from above is a floor wearing a disguise.
        val over = tolerance * 2
        assertFailsWith<IllegalArgumentException> {
            ShaderAssetProof.requireFactor(ShaderAssetProof.DimFactor(predicted + over, samples), strength)
        }
        assertFailsWith<IllegalArgumentException> {
            ShaderAssetProof.requireFactor(ShaderAssetProof.DimFactor(predicted - over, samples), strength)
        }
    }

    @Test
    fun `the predicted factor follows the strength, so the check is not pinned to one number`() {
        val samples = ShaderAssetProof.MIN_SAMPLES

        // 0.75 is right at a strength of 0.25 and wrong at 0.5, which is what makes the prediction
        // a function of the uniform rather than a constant somebody wrote down once.
        ShaderAssetProof.requireFactor(ShaderAssetProof.DimFactor(0.75, samples), 0.25f)
        assertFailsWith<IllegalArgumentException> {
            ShaderAssetProof.requireFactor(ShaderAssetProof.DimFactor(0.75, samples), 0.5f)
        }
        ShaderAssetProof.requireFactor(ShaderAssetProof.DimFactor(0.50, samples), 0.5f)
    }

    private fun flat(level: Int): BufferedImage {
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        val grey = (level shl 16) or (level shl 8) or level
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) image.setRGB(x, y, grey)
        return image
    }

    private fun dimRow(image: BufferedImage, y: Int, to: Int) {
        val grey = (to shl 16) or (to shl 8) or to
        for (x in 0 until WIDTH) image.setRGB(x, y, grey)
    }

    private companion object {
        const val WIDTH = 4
        const val HEIGHT = 2

        /** Well over [ShaderAssetProof.MIN_CHANNEL], so every channel is sampled. */
        const val BRIGHT = 200

        /** Three quarters of [BRIGHT], exactly, so the expected mean carries no rounding. */
        const val THREE_QUARTERS = 150

        /** Under [ShaderAssetProof.MIN_CHANNEL], so no channel is sampled. */
        const val DARK = 16
    }
}
