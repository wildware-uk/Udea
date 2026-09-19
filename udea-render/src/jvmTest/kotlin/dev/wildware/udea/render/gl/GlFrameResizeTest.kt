package dev.wildware.udea.render.gl

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.size
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
import dev.wildware.udea.render.capture.CaptureResult
import dev.wildware.udea.render.capture.capture
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.ui.DesktopFonts
import dev.wildware.udea.render.ui.UiScreen
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #234, reopened: **an editor's Game tab has no fixed shape.** Asked for the size of the
 * rectangle it is shown in, the Game view takes the capturable frame with it, so the game is drawn at
 * that size - its world to the frame's edges and its HUD laid out in the new frame - and
 * `render.screenshot` reads the frame at that size too.
 *
 * The HUD is a [dev.wildware.udea.render.ui.CapturedUi] with one square pinned to its bottom-right
 * corner. A HUD still laid out at the old size would put the square off the right of the new, narrower
 * frame; a frame resized with nothing following it would leave a bar where the old picture ended.
 */
class GlFrameResizeTest {

    @Test
    fun `a Game view's size becomes the frame's, with the world and the HUD fitted to it`() {
        GlAvailability.require()

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
        registry.register(RenderPhase.UI, { resources -> HudSystem(resources, fonts) })

        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(title = "udea-frame-resize", windowWidth = WIDTH, windowHeight = HEIGHT, renderWidth = WIDTH, renderHeight = HEIGHT),
            registry,
        )
        try {
            val host = GameHost(RenderMode.Offscreen, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()), backend)
            backend.drive(host)
            val slot = backend.pipeline!!.capture!!

            // A frame of grace: the first capture can be served before the toolkit has composed.
            slot.capture(CaptureRequest())
            val before = decode("frame-resize-before.png", slot.capture(CaptureRequest()))
            assertEquals(WIDTH, before.width)
            assertEquals(HUD, before.rgb(WIDTH - 1 - INSET, HEIGHT - 1 - INSET), "the HUD's corner square is not in the frame to begin with")

            val game = backend.openGameView()
            backend.onRenderThread { game.resizeTo(TALL_WIDTH, TALL_HEIGHT) }
            // Two frames' grace for the toolkit to lay the HUD out again at the new size.
            slot.capture(CaptureRequest())
            val (captured, shown) = backend.onRenderThread { slot.submit(CaptureRequest()) to game.capture() }
            val after = decode("frame-resize-after.png", await(captured))
            val tab = decode("frame-resize-game-tab.png", await(shown))

            assertEquals(TALL_WIDTH, after.width, "render.screenshot is not the Game view's width")
            assertEquals(TALL_HEIGHT, after.height, "render.screenshot is not the Game view's height")
            assertEquals(TALL_WIDTH, tab.width, "the Game tab's picture is not its own width")
            assertEquals(TALL_HEIGHT, tab.height, "the Game tab's picture is not its own height")
            for (image in listOf(after, tab)) {
                val right = image.width - 1 - INSET
                val bottom = image.height - 1 - INSET
                assertEquals(HUD, image.rgb(right, bottom), "the HUD's corner square is not in the new frame's corner")
                assertEquals(WORLD, image.rgb(right, INSET), "the world does not reach the new frame's top right")
                assertEquals(WORLD, image.rgb(INSET, bottom), "the world does not reach the new frame's bottom left")
                assertEquals(WORLD, image.rgb(INSET, INSET), "the world does not reach the new frame's top left")
            }
        } finally {
            backend.close()
            fonts.close()
        }
    }

    // --- fixture -------------------------------------------------------------------------

    private fun await(result: Deferred<CaptureResult>): CaptureResult =
        runBlocking { withTimeout(CAPTURE_TIMEOUT_MILLIS) { result.await() } }

    private fun decode(name: String, result: CaptureResult): BufferedImage {
        System.getProperty("udea.render.glReportDir")?.let { dir ->
            File(dir).mkdirs()
            File(dir, name).writeBytes(result.bytes)
        }
        return checkNotNull(ImageIO.read(ByteArrayInputStream(result.bytes))) { "the captured bytes are not a decodable image" }
    }

    private fun BufferedImage.rgb(x: Int, y: Int): Int = getRGB(x, y) and 0xFFFFFF

    /** The world: its colour over the whole target it is handed, whatever size that is. */
    private class WorldSystem(private val resources: RenderResources) : RenderSystem {
        override fun render(target: OffscreenTarget, alpha: Float) {
            val batch = resources.batch
            batch.beginPixels()
            batch.fill(0f, 0f, target.width.toFloat(), target.height.toFloat(), Rgba.of(0f, 0f, 1f, 1f))
            batch.end()
        }
    }

    /** The HUD: a square pinned to the bottom-right corner of the frame it is laid out in. */
    private class HudSystem(resources: RenderResources, fonts: DesktopFonts) : RenderSystem {

        init {
            resources.capturedUi(fonts).show(Corner)
        }

        override fun render(target: OffscreenTarget, alpha: Float) = Unit
    }

    private object Corner : UiScreen {
        @Composable
        override fun content() {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                Box(Modifier.size(SQUARE).background(Colour.rgb(HUD.toLong())))
            }
        }
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 360

        /** A tab narrower than the frame's shape: a gap between two side panels. */
        const val TALL_WIDTH = 300
        const val TALL_HEIGHT = 360

        const val SQUARE = 40f

        /** Pixels in from an edge a colour is read at: clear of any blended edge pixel. */
        const val INSET = 4

        const val WORLD = 0x0000FF
        const val HUD = 0x00C853

        const val FONT = "udea-test"
        const val FONT_SIZE = 24

        const val CAPTURE_TIMEOUT_MILLIS = 10_000L
    }
}
