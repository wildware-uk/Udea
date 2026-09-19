package dev.wildware.udea.render.gl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.FrameCaptureSlot
import dev.wildware.udea.render.capture.capture
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.ui.CapturedUi
import dev.wildware.udea.render.ui.DesktopFonts
import dev.wildware.udea.render.ui.UiScreen
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #188's engine half, in pixels: **game UI drawn through [CapturedUi] is in the frame an agent
 * captures**, over everything the `RenderSystem`s drew, the right way up, and blended as the toolkit
 * means it to be.
 *
 * The mirror image of `GlUiLayerTest`. A menu shown through `UiLayer` must never reach a capture; a
 * heads-up display must always reach one, because an agent that asks for a screenshot to see whether
 * an ability is cooling needs the cooldown in the picture. Both are ComposeGL screens, and what
 * separates them is where they draw - the window, or the capturable pass - so each has a test that
 * reads the capture.
 *
 * ## What each assertion rules out
 *
 * - The opaque panel's own colour at its centre: the screen drew, and it drew into the pass.
 * - The world's colour beside it: the screen is over the world, not instead of it.
 * - The world's colour where the panel would be if the picture were upside down: a pass is stored
 *   bottom row first and a window top row first, and a screen drawn into one as if it were the other
 *   lands mirrored - a HUD whose health bar is at the top of every screenshot.
 * - The half-transparent panel as the blend of its colour over the world's: the toolkit writes
 *   premultiplied colour, and composited as straight alpha it comes out darker. That is the defect a
 *   texture-and-sprite route would have had; this route has the toolkit draw into the pass itself.
 * - A change of Compose state in the next capture, and `show(null)` leaving only the world: the
 *   screen is live, not a picture taken once.
 */
class GlCapturedUiTest {

    @Test
    fun `a captured screen is in the capture, over the world, upright and blended`() {
        GlAvailability.require()

        val screen = PanelScreen()
        val fonts = DesktopFonts()
        fonts.register(
            FONT,
            checkNotNull(javaClass.getResourceAsStream("/fonts/DejaVuSans.ttf")) {
                "the test font is missing from udea-render's jvmTest resources"
            }.readBytes(),
            listOf(FONT_SIZE),
        )
        val registry = RenderRegistry()
        registry.register(RenderPhase.World, { resources -> WorldSystem(resources) })
        registry.register(RenderPhase.UI, { resources -> HudSystem(resources, fonts, screen) })

        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-captured-ui",
                windowWidth = WIDTH * 2,
                windowHeight = HEIGHT * 2,
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
            val slot = backend.pipeline!!.capture!!

            // Two frames' grace: the first capture can be served before the toolkit has composed.
            val shown = captureSettled(slot, "shown") { it.rgb(OPAQUE_X, OPAQUE_Y) == OPAQUE }
            assertEquals(OPAQUE, shown.rgb(OPAQUE_X, OPAQUE_Y), "the opaque panel is not in the capture")
            assertEquals(WORLD, shown.rgb(WIDTH / 2, HEIGHT / 2), "the world is not visible beside the panel")
            assertEquals(
                WORLD,
                shown.rgb(OPAQUE_X, HEIGHT - 1 - OPAQUE_Y),
                "the panel is where it would be if the capture were upside down",
            )
            assertBlend(shown.rgb(GLASS_X, GLASS_Y))

            screen.colour = OPAQUE_AFTER
            val changed = captureSettled(slot, "changed") { it.rgb(OPAQUE_X, OPAQUE_Y) == OPAQUE_AFTER }
            assertEquals(OPAQUE_AFTER, changed.rgb(OPAQUE_X, OPAQUE_Y), "a Compose state change never reached the capture")

            screen.hidden = true
            val hidden = captureSettled(slot, "hidden") { it.rgb(OPAQUE_X, OPAQUE_Y) == WORLD }
            assertEquals(WORLD, hidden.rgb(OPAQUE_X, OPAQUE_Y), "an empty screen still left a panel in the capture")
            assertEquals(WORLD, hidden.rgb(GLASS_X, GLASS_Y), "an empty screen still left glass in the capture")
        } finally {
            backend.close()
            fonts.close()
        }
    }

    // --- fixture -------------------------------------------------------------------------

    /** Captures until [settled] holds or [ATTEMPTS] captures have been taken, and returns the last. */
    private fun captureSettled(slot: FrameCaptureSlot, name: String, settled: (BufferedImage) -> Boolean): BufferedImage {
        var image: BufferedImage? = null
        repeat(ATTEMPTS) {
            val result = slot.capture(CaptureRequest())
            writeReport("captured-ui-$name.png", result.bytes)
            val decoded = ImageIO.read(ByteArrayInputStream(result.bytes))
                ?: error("the captured bytes are not a decodable image")
            image = decoded
            if (settled(decoded)) return decoded
        }
        return checkNotNull(image)
    }

    private fun BufferedImage.rgb(x: Int, y: Int): Int = getRGB(x, y) and 0xFFFFFF

    /** [GLASS] at [GLASS_ALPHA] over [WORLD], per channel, with a rounding step's tolerance. */
    private fun assertBlend(actual: Int) {
        for (shift in intArrayOf(16, 8, 0)) {
            val glass = (GLASS shr shift) and 0xFF
            val world = (WORLD shr shift) and 0xFF
            val expected = glass * GLASS_ALPHA + world * (1f - GLASS_ALPHA)
            val got = (actual shr shift) and 0xFF
            assertTrue(
                abs(got - expected) <= BLEND_TOLERANCE,
                "the half-transparent panel came out ${hex(actual)}; source-over of ${hex(GLASS)} at " +
                    "$GLASS_ALPHA onto ${hex(WORLD)} is channel $shift = $expected",
            )
        }
    }

    private fun hex(rgb: Int): String = "#%06X".format(rgb)

    private fun writeReport(name: String, png: ByteArray) {
        val dir = System.getProperty("udea.render.glReportDir") ?: return
        File(dir).mkdirs()
        File(dir, name).writeBytes(png)
    }

    /** Fills the capture with the world's colour, so every pixel the screen did not draw is known. */
    private class WorldSystem(private val resources: RenderResources) : RenderSystem {

        override fun render(target: OffscreenTarget, alpha: Float) {
            val batch = resources.batch
            batch.beginPixels()
            batch.fill(0f, 0f, target.width.toFloat(), target.height.toFloat(), Rgba.of(0f, 0f, 1f, 1f))
            batch.end()
        }
    }

    /** Shows [screen] in the capture, the way a game's HUD system does. */
    private class HudSystem(resources: RenderResources, fonts: DesktopFonts, screen: UiScreen) : RenderSystem {

        init {
            resources.capturedUi(fonts).show(screen)
        }

        override fun render(target: OffscreenTarget, alpha: Float) = Unit
    }

    /**
     * An opaque panel near the top left, a half-transparent one near the bottom right, and a label,
     * all in design units that are the capture's pixels, counted down from the top.
     */
    private class PanelScreen : UiScreen {

        var colour: Int by mutableStateOf(OPAQUE)

        var hidden: Boolean by mutableStateOf(false)

        @Composable
        override fun content() {
            if (hidden) return
            Box(Modifier.size(WIDTH.toFloat(), HEIGHT.toFloat())) {
                Box(
                    Modifier.offset(OPAQUE_X - 30f, OPAQUE_Y - 20f)
                        .size(60f, 40f)
                        .background(Colour.rgb(colour.toLong())),
                )
                Box(
                    Modifier.offset(GLASS_X - 30f, GLASS_Y - 20f)
                        .size(60f, 40f)
                        .background(Colour.argb(GLASS_ARGB)),
                )
                // Clear of every pixel asserted on: it is here so the glyph path runs, not to be read.
                Text(
                    "HUD",
                    Modifier.offset(140f, 10f),
                    textStyle = TextStyle(family = FONT, size = FONT_SIZE.toFloat()),
                    colour = Colour.White,
                )
            }
        }
    }

    private companion object {

        const val WIDTH = 320
        const val HEIGHT = 180

        const val OPAQUE_X = 50
        const val OPAQUE_Y = 40

        const val GLASS_X = 260
        const val GLASS_Y = 140

        const val FONT = "udea-test"
        const val FONT_SIZE = 16

        const val ATTEMPTS = 10

        const val WORLD = 0x0000FF
        const val OPAQUE = 0xC62828
        const val OPAQUE_AFTER = 0x2E7D32
        const val GLASS = 0xFFFF00
        const val GLASS_ALPHA = 128f / 255f
        const val GLASS_ARGB = 0x80FFFF00L

        /** One step of 8-bit rounding either side, and one for the driver's blend. */
        const val BLEND_TOLERANCE = 3f
    }
}
