package dev.wildware.udea.render.gl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.Matrix4
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Panel
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.FrameTime
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.backend.Lwjgl3Backend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.ui.UiLayer
import dev.wildware.udea.render.ui.UiScreen
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A ComposeGL interface drawn by a real driver, into the framebuffer a capture reads: issue
 * #187's third acceptance criterion.
 *
 * ## Why `UiLayerTest` cannot stand in for this one
 *
 * `UiLayerTest` drives the same [UiLayer] against ComposeGL's `HeadlessBackend`, so it proves
 * the composition, the clamp, the lifecycle and the layout. What it cannot prove is that any of
 * it reaches a pixel, and #186 said so explicitly rather than pretending otherwise: drawing a
 * ComposeGL tree needs a `GdxCanvas` with a live `Batch`, a `GdxFonts` with a registered
 * typeface, and therefore the gdx-freetype **natives** that `composegl-gdx`'s POM does not
 * bring. All three are wired now, and this is the test that reads a pixel rather than a promise.
 * Two of the three unwirings were checked by mutation while this was written: removing the
 * freetype natives gives an `UnsatisfiedLinkError` out of `FreeType.initFreeType`, and replacing
 * the draw with a settle-only call gives a frame with nothing in it. Both are in `BRIEF-187.md`
 * as M7 and M2.
 *
 * ## What it asserts, and why not "the panel is #1E2836"
 *
 * Nothing here names a colour from ComposeGL's default skin. A test that did would be asserting
 * the skin's palette, which is not this repository's to pin and would go red on an upstream
 * restyle that broke nothing.
 *
 * Instead it captures the same frame twice -- once with the screen mounted, once with it
 * unmounted -- and compares:
 *
 * - **inside** the panel's own laid-out rectangle, thousands of pixels must differ;
 * - **outside** it, in a corner, not one pixel may differ. That is the control, and it is the
 *   half that matters: a layer that cleared the whole surface, drew the interface at the wrong
 *   scale, or wiped the world under it would pass the first assertion and fail this one;
 * - inside the **title's** rectangle the mounted frame must carry many distinct colours, which
 *   is what a rasterised glyph looks like and a flat fill does not.
 *
 * It also writes both frames out, because the honest check on a picture is looking at it.
 *
 * ## Everything that touches the tree runs on the render thread
 *
 * A `UiHost`, its routers and its node tree are all single-threaded by design -- ComposeGL says
 * so -- and the thread that owns them here is the one drawing frames. So mounting, reading a
 * node's box and sending a click all go through [Lwjgl3Backend.onRenderThread], and only the
 * captures and the pixel arithmetic happen on the test's own thread. A test that read
 * `boundsInRoot` directly would be reading a tree mid-layout, and would fail rarely and
 * inexplicably rather than never.
 */
class ComposeUiGlTest {

    @Test
    fun `a composed ComposeGL screen is drawn over the world into the captured frame`() {
        GlAvailability.require()
        withHost { backend, layer, _ ->
            val slot = checkNotNull(backend.pipeline?.capture) { "the pipeline has no capture slot" }

            settle(slot)
            val mounted = decode(slot.capture(CaptureRequest()).bytes)
            val panel = backend.onRenderThread { layer.host.root.find(ComposeUiScreen.PANEL).boundsInRoot }
            val title = backend.onRenderThread { layer.host.root.find(ComposeUiScreen.TITLE).boundsInRoot }

            // The same frame with the interface taken away, so the comparison is about the
            // interface and not about the driver, the clear colour or the world quad.
            backend.onRenderThread { layer.hide() }
            settle(slot)
            val bare = decode(slot.capture(CaptureRequest()).bytes)

            write("issue187-composegl-ui-gl-frame.png", mounted)
            write("issue187-composegl-ui-gl-frame-unmounted.png", bare)

            assertEquals(RENDER_WIDTH, mounted.width)
            assertEquals(RENDER_HEIGHT, mounted.height)
            assertTrue(
                panel.width > 0f && panel.height > 0f,
                "the panel was never laid out, so nothing could have been drawn: $panel",
            )

            val insideChanged = differingPixels(mounted, bare, panel.shrunk(2f))
            assertTrue(
                insideChanged > 1000,
                "only $insideChanged pixels inside the panel differ between the mounted and the " +
                    "unmounted frame; the interface did not draw",
            )

            // The control. A corner of the surface the interface does not cover: the world quad
            // is there in both frames and must be identical in both.
            val corner = Rect.of(0f, 0f, CORNER.toFloat(), CORNER.toFloat())
            val cornerChanged = differingPixels(mounted, bare, corner)
            assertEquals(
                0,
                cornerChanged,
                "$cornerChanged pixels changed in a ${CORNER}x$CORNER corner the interface does " +
                    "not cover, so the layer is clearing or rescaling the whole surface",
            )

            val shades = distinctColours(mounted, title)
            assertTrue(
                shades > 8,
                "the title's box holds only $shades distinct colours, which is a flat rectangle " +
                    "rather than rasterised text: FreeType produced no glyphs",
            )
        }
    }

    @Test
    fun `a click on the composed button recomposes the frame a capture reads`() {
        GlAvailability.require()
        // The pixel half of what `UiInputOrderTest` asserts on the node tree: a press through
        // the layer's own `InputProcessor`, with a real context up, changing what comes back out
        // of `glReadPixels`. The label under the button counts the clicks, so the two frames
        // cannot be identical unless the click reached `onClick` *and* the recomposition drew.
        withHost { backend, layer, screen ->
            val slot = checkNotNull(backend.pipeline?.capture) { "the pipeline has no capture slot" }

            settle(slot)
            val before = decode(slot.capture(CaptureRequest()).bytes)
            val count = backend.onRenderThread { layer.host.root.find(ComposeUiScreen.COUNT).boundsInRoot }

            backend.onRenderThread {
                val button = layer.host.root.find(ComposeUiScreen.BUTTON).boundsInRoot
                // Framebuffer pixels, which are window units here: this fixture's render size and
                // window size are the same, so the layer's default hdpi scale of 1 is right.
                layer.input.touchDown(button.centre.x.toInt(), button.centre.y.toInt(), 0, Input.Buttons.LEFT)
                layer.input.touchUp(button.centre.x.toInt(), button.centre.y.toInt(), 0, Input.Buttons.LEFT)
            }
            settle(slot)
            val after = decode(slot.capture(CaptureRequest()).bytes)

            write("issue187-composegl-ui-gl-clicked.png", after)

            assertEquals(1, screen.clicks, "the click did not reach the button's onClick")
            assertTrue(
                differingPixels(before, after, count) > 20,
                "the label counting the clicks reads the same in both frames, so the " +
                    "recomposition never reached a pixel",
            )
        }
    }

    // --- fixture -------------------------------------------------------------------------

    /**
     * A real `Offscreen` backend with a world quad under a [UiLayer], driven by a [GameHost].
     *
     * The world quad is not decoration: it is what makes "the interface drew over the world and
     * did not wipe it" a question the corner assertion can answer. Registered in
     * [RenderPhase.World], which sorts before [RenderPhase.UI].
     */
    private fun withHost(block: (Lwjgl3Backend, UiLayer, ComposeUiScreen) -> Unit) {
        val screen = ComposeUiScreen()
        var layer: UiLayer? = null
        val registry = RenderRegistry()
        registry.register(RenderPhase.World, ::BlueQuadSystem)
        registry.register(RenderPhase.UI, { resources ->
            UiLayer(
                resources,
                FIXED_FRAME_TIME,
                // From this module's own test resources. ComposeGL's gdx backend registers a
                // typeface through FreeType, and `composegl-gdx`'s POM brings the Java binding
                // without the natives -- `udea-render` selects those, so this line is what fails
                // if that selection is dropped.
                Gdx.files.classpath("fonts/DejaVuSans.ttf"),
            ).also {
                it.show(screen)
                layer = it
            }
        })

        val backend = Lwjgl3Backend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-composegl-ui-test",
                windowWidth = RENDER_WIDTH,
                windowHeight = RENDER_HEIGHT,
                renderWidth = RENDER_WIDTH,
                renderHeight = RENDER_HEIGHT,
            ),
            registry,
        )
        try {
            val host = GameHost(RenderMode.Offscreen, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()), backend)
            backend.drive(host)
            block(backend, checkNotNull(layer) { "the UI layer was never built" }, screen)
        } finally {
            backend.close()
        }
    }

    /**
     * Draws and throws away [FRAMES] frames, so the next capture is of a settled surface.
     *
     * Not caution. ComposeGL uploads a glyph atlas page the first time text is drawn on it, and
     * the toolkit's own first frames are where a layout and a focus refresh land -- so the first
     * captured frame after a mount genuinely differs from the second, by thousands of pixels
     * inside the panel. Without this, "the mounted frame differs from the unmounted one inside
     * the panel" was satisfied by *two consecutive frames of the same screen*: the mutation that
     * stops `hide` unmounting anything left the assertion green, which is how this was found.
     */
    private fun settle(slot: dev.wildware.udea.render.capture.FrameCaptureSlot) {
        repeat(FRAMES) { slot.capture(CaptureRequest()) }
    }

    private fun decode(png: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(png))
        ?: error("the captured bytes are not a decodable image")

    /** Pixels in [area] whose RGB differs between the two frames. */
    private fun differingPixels(a: BufferedImage, b: BufferedImage, area: Rect): Int {
        var differing = 0
        forEachPixel(a, area) { x, y -> if (a.getRGB(x, y) != b.getRGB(x, y)) differing++ }
        return differing
    }

    /** How many distinct RGB values [area] holds. Antialiased glyphs make many. */
    private fun distinctColours(image: BufferedImage, area: Rect): Int {
        val seen = HashSet<Int>()
        forEachPixel(image, area) { x, y -> seen += image.getRGB(x, y) }
        return seen.size
    }

    /**
     * Walks the part of [area] that is actually on [image].
     *
     * Clipped rather than trusted, because these rectangles come out of a layout: a box that
     * reached an edge would otherwise be an `ArrayIndexOutOfBounds` inside an assertion helper,
     * which reads as a broken test rather than as the layout it is about.
     */
    private inline fun forEachPixel(image: BufferedImage, area: Rect, block: (Int, Int) -> Unit) {
        for (y in area.top.toInt().coerceAtLeast(0) until area.bottom.toInt().coerceAtMost(image.height)) {
            for (x in area.left.toInt().coerceAtLeast(0) until area.right.toInt().coerceAtMost(image.width)) {
                block(x, y)
            }
        }
    }

    /** [Rect] inset by [by] on every side, so an assertion skips a border's own antialiasing. */
    private fun Rect.shrunk(by: Float): Rect =
        Rect.of(left + by, top + by, (width - 2 * by).coerceAtLeast(0f), (height - 2 * by).coerceAtLeast(0f))

    private fun write(name: String, image: BufferedImage) {
        val into = File(REPORT_DIR).also { it.mkdirs() }.resolve(name)
        ImageIO.write(image, "png", into)
        println("wrote ${into.absolutePath}")
    }

    /**
     * The screen under test: a centred panel with two labels and a button.
     *
     * Deliberately ComposeGL's default skin and no colours of its own, so what the picture shows
     * is the toolkit drawing rather than this test drawing rectangles through it.
     */
    private class ComposeUiScreen : UiScreen {

        /**
         * `mutableStateOf` rather than a plain field, because the label reads it: a plain field
         * would change without telling the composition and the count would sit at zero on screen
         * while being right in the object -- which is the trap ComposeGL's own documentation
         * names, and it is what the second test would catch.
         */
        var clicks: Int by mutableStateOf(0)

        @Composable
        override fun content() {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Centre) {
                // The padding is on the column and not on the panel on purpose. `Panel` appends
                // its own `styled(...)` to whatever modifier it is given, so a `padding` handed to
                // the panel is *outside* the background it paints: the picture came out with the
                // longest label touching both drawn edges. Inside the panel it is what a reader
                // expects, and it is also the layout rule the frame then demonstrates.
                Panel(
                    Modifier.width(360f).testTag(PANEL),
                    contentAlignment = Alignment.Centre,
                ) {
                    Column(
                        Modifier.padding(all = 20f),
                        verticalArrangement = Arrangement.spacedBy(12f),
                        horizontalAlignment = HorizontalAlignment.Centre,
                    ) {
                        Text("UDEA ON COMPOSEGL", Modifier.testTag(TITLE))
                        Text("issue 187 - clicked $clicks times", Modifier.testTag(COUNT))
                        Button("CLICK ME", { clicks++ }, Modifier.testTag(BUTTON))
                    }
                }
            }
        }

        companion object {
            const val PANEL: String = "udea-gl-panel"
            const val TITLE: String = "udea-gl-title"
            const val COUNT: String = "udea-gl-count"
            const val BUTTON: String = "udea-gl-button"
        }
    }

    /**
     * Fills the offscreen target with an opaque blue, so there is a world under the interface.
     *
     * The same one-pixel-texture trick `GlCaptureTest`'s red quad uses, and for the same reason:
     * `udea-assets` is not what this test is about.
     */
    private class BlueQuadSystem(private val resources: RenderResources) : RenderSystem {

        private val projection = Matrix4()

        private val pixel: TextureRegion = resources.own(
            Texture(
                Pixmap(1, 1, Pixmap.Format.RGBA8888).apply {
                    setColor(Color(0.10f, 0.22f, 0.40f, 1f))
                    fill()
                },
            ),
        ).let(::TextureRegion)

        override fun render(target: OffscreenTarget, alpha: Float) {
            projection.setToOrtho2D(0f, 0f, target.width.toFloat(), target.height.toFloat())
            val batch = resources.batch
            batch.projectionMatrix = projection
            batch.color = Color.WHITE
            batch.begin()
            batch.draw(pixel, 0f, 0f, target.width.toFloat(), target.height.toFloat())
            batch.end()
        }
    }

    private companion object {
        const val RENDER_WIDTH = 640
        const val RENDER_HEIGHT = 360

        /** How big a corner of untouched surface the control assertion reads. */
        const val CORNER = 64

        /**
         * Frames drawn and discarded before a capture that is going to be compared.
         *
         * Three rather than one: a mount takes a frame to recompose, a frame to lay out and a
         * frame to upload whatever glyph pages the new text needs.
         */
        const val FRAMES = 3

        const val REPORT_DIR = "build/reports/udea/compose-ui"

        /**
         * A sixtieth of a second, every frame. A real clock here would make the picture depend on
         * how long the driver took, and `UiLayerTest` is where the clamp is asserted.
         */
        val FIXED_FRAME_TIME: FrameTime = object : FrameTime {
            override val frameSeconds: Float = 1f / 60f
        }
    }
}
