package dev.wildware.udea.render.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.animation.animateFloatAsState
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.render.FrameTime
import dev.wildware.udea.render.OverlaySystem
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderPipeline
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.Resizable
import dev.wildware.udea.render.ScreenTarget
import dev.wildware.udea.render.support.HeadlessGl
import dev.wildware.udea.render.support.RecordingBatch
import dev.wildware.udea.render.support.testTargets
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ComposeGL layer: it composes once per frame, clamps the time it advances the toolkit by,
 * and owns its screen's lifecycle.
 *
 * Driven with no GL context and no driver, because the layer is handed ComposeGL's own
 * [HeadlessBackend] -- a [dev.wildware.composegl.ui.graphics.RecordingCanvas] and a monospace
 * font provider. That is the whole reason [UiLayer] takes a `UiBackend` rather than building one:
 * the shipped path puts a `GdxBackend` there and draws real pixels
 * ([dev.wildware.udea.render.gl.ComposeUiGlTest] is that half), and everything about
 * composition, clamping, mounting and input order is the same object either way.
 *
 * [HeadlessGl] is still installed, because the pipeline around the layer applies a gdx viewport
 * and that reaches `Gdx.gl` through `HdpiUtils`.
 */
class UiLayerTest {

    private var gl: HeadlessGl? = null

    private val batch = RecordingBatch()

    private val frameTime = MutableFrameTime()

    private val targets = testTargets(batch = batch.batch, width = 800, height = 600)

    @BeforeEach
    fun installGl() {
        gl = HeadlessGl.installed(width = 800, height = 600)
    }

    @AfterEach
    fun removeGl() {
        gl?.uninstall()
        gl = null
    }

    @Test
    fun `sixty frames compose the tree exactly sixty times`() {
        val (layer, pipeline) = pipelineWithLayer()
        frameTime.frameSeconds = 1f / 60f

        repeat(60) { pipeline.render(0f) }

        assertEquals(60L, layer.frameCount)
    }

    @Test
    fun `a stalled frame advances the toolkit's clock by the clamp and no further`() {
        val (layer, pipeline) = pipelineWithLayer()

        frameTime.frameSeconds = 1f / 60f
        pipeline.render(0f)
        val afterFirst = layer.clockNanos
        // A breakpoint, a shader compile, a GC pause. The pipeline itself clamps at 0.25s;
        // the toolkit is clamped tighter still, because an interface animation is measured in
        // tenths of a second and a quarter of one would be most of a fade.
        frameTime.frameSeconds = 40f
        pipeline.render(0f)

        assertEquals(
            (UiLayer.MAX_UI_SECONDS * 1_000_000_000f).toLong(),
            layer.clockNanos - afterFirst,
            "a 40-second frame must advance the toolkit by the clamp, not by 40 seconds",
        )
    }

    @Test
    fun `a stalled frame is clamped so an animation does not jump to its end state`() {
        // The consequence the number above exists for, asserted on a real Compose animation
        // rather than on the constant: a linear one-second tween, stalled by forty seconds.
        // Unclamped the toolkit is handed 40s of a 1s tween and the value is exactly 1.
        val (layer, pipeline) = pipelineWithLayer()
        val screen = AnimatingScreen()
        layer.show(screen)

        frameTime.frameSeconds = 1f / 60f
        repeat(4) { pipeline.render(0f) }
        screen.target = 1f
        repeat(4) { pipeline.render(0f) }
        val beforeStall = screen.observed
        frameTime.frameSeconds = 40f
        pipeline.render(0f)

        assertTrue(
            beforeStall < 0.2f,
            "the tween should barely have started after four frames, not be at $beforeStall",
        )
        assertTrue(
            screen.observed < 0.25f,
            "a 40-second stall ran ${(screen.observed * 1000).toInt()}ms of a 1000ms tween to " +
                "${screen.observed}; unclamped it would read 1.0",
        )
    }

    @Test
    fun `showing a screen composes its content and hiding removes and disposes it`() {
        val layer = standaloneLayer()
        val screen = TaggedScreen()

        layer.show(screen)

        assertNotNull(layer.host.root.findOrNull(TaggedScreen.TAG), "the screen never composed")

        layer.hide()

        assertNull(
            layer.host.root.findOrNull(TaggedScreen.TAG),
            "the screen's nodes are still in the tree",
        )
        assertTrue(screen.disposed, "the screen was not disposed")
    }

    @Test
    fun `showing a second screen replaces the first rather than stacking on it`() {
        // The failure this prevents: a loading screen's buttons left behind a menu, still
        // taking clicks from a player who cannot see them.
        val layer = standaloneLayer()
        val first = TaggedScreen(tag = "first")
        val second = TaggedScreen(tag = "second")

        layer.show(first)
        layer.show(second)

        assertTrue(first.disposed, "the first screen was not disposed")
        assertNull(layer.host.root.findOrNull("first"), "the first screen is still composed")
        assertNotNull(layer.host.root.findOrNull("second"), "the second screen never composed")
    }

    @Test
    fun `the composed tree is drawn into the backend's canvas every frame`() {
        // Composing is not drawing. `UiHost.frame` can return false for ever on a screen nobody
        // touched, and a layer that only composed would leave an empty frame with the tree in
        // perfect order -- which is what a unit test on the node tree alone would call a pass.
        val backend = HeadlessBackend(Rect.of(0f, 0f, 800f, 600f))
        val (layer, pipeline) = pipelineWithLayer(backend)
        layer.show(TaggedScreen())
        frameTime.frameSeconds = 1f / 60f

        repeat(3) { pipeline.render(0f) }

        assertEquals(
            3,
            backend.canvas.frames,
            "three pipeline frames must be three canvas frames opened and closed cleanly",
        )
        assertEquals(
            listOf("mounted", "mounted", "mounted"),
            backend.canvas.texts(),
            "the screen's own text should have been drawn once per frame",
        )
    }

    @Test
    fun `a window resize reaches the screen target and every Resizable`() {
        val overlay = ResizableOverlay()
        val registry = RenderRegistry()
        registry.overlay({ overlay })
        val pipeline = registry.build(world(), ctx, targets)

        pipeline.resize(1024, 768)

        assertEquals(1024, targets.screen.width)
        assertEquals(768, targets.screen.height)
        assertEquals(listOf("1024x768"), overlay.resizes)
    }

    @Test
    fun `a resize leaves the offscreen target alone so two captures stay comparable`() {
        val pipeline = RenderRegistry().build(world(), ctx, targets)
        val before = targets.offscreen.width to targets.offscreen.height

        pipeline.resize(1920, 1080)

        assertEquals(before, targets.offscreen.width to targets.offscreen.height)
    }

    @Test
    fun `a minimised window reports zero and is ignored rather than dividing by it`() {
        val overlay = ResizableOverlay()
        val registry = RenderRegistry()
        registry.overlay({ overlay })
        val pipeline = registry.build(world(), ctx, targets)

        pipeline.resize(0, 0)

        assertEquals(800, targets.screen.width)
        assertEquals(emptyList(), overlay.resizes)
    }

    @Test
    fun `the layer lays the tree out against the surface it is drawing into`() {
        // `ScreenViewport` was the scene2d answer and `Viewport.oneToOne` is this one: one design
        // unit per pixel of the *offscreen* target, which is the surface a capture reads and not
        // the window. A layer that read `Gdx.graphics` instead would lay a HUD out for 800x600
        // while drawing into a 64x32 capture, and every screenshot an agent diffs would be of a
        // differently-shaped interface from the one the player sees.
        val (layer, pipeline) = pipelineWithLayer()
        layer.show(FillingScreen())
        frameTime.frameSeconds = 1f / 60f

        pipeline.render(0f)

        val filled = layer.host.root.find(FillingScreen.TAG)
        assertEquals(
            targets.offscreen.width.toFloat() to targets.offscreen.height.toFloat(),
            filled.width to filled.height,
        )
    }

    @Test
    fun `the layer is disposed by the pipeline rather than by whoever remembered`() {
        val (layer, pipeline) = pipelineWithLayer()
        val screen = TaggedScreen()
        layer.show(screen)

        pipeline.dispose()

        assertTrue(screen.disposed, "the pipeline did not dispose the UI layer")
    }

    // --- fixture -------------------------------------------------------------------------

    /**
     * A layer outside a pipeline, for the mount/unmount tests that never draw.
     *
     * It still goes through a [RenderResources], because that is how a `UiLayer` gets both the
     * frame's batch -- which the shipped `GdxBackend` hands to `UiCanvas.raw` -- and its place in
     * the disposal list.
     */
    private fun standaloneLayer(): UiLayer =
        UiLayer(RenderResources(batch.batch, targets.offscreen), frameTime, HeadlessBackend())

    /**
     * A layer built the way a game builds one: by the registry, from the pipeline's own
     * resources, so the disposal registration in `init` is the shipped one rather than the
     * test's.
     */
    private fun pipelineWithLayer(
        backend: HeadlessBackend = HeadlessBackend(),
    ): Pair<UiLayer, RenderPipeline> {
        var built: UiLayer? = null
        val registry = RenderRegistry()
        registry.register(
            RenderPhase.UI,
            { resources -> UiLayer(resources, frameTime, backend).also { built = it } },
        )
        val pipeline = registry.build(world(), ctx, targets)
        return checkNotNull(built) to pipeline
    }

    private class MutableFrameTime(override var frameSeconds: Float = 0f) : FrameTime

    /** A [UiScreen] over one tagged node, so a test can ask whether it is in the tree. */
    private class TaggedScreen(private val tag: String = TAG) : UiScreen {

        var disposed: Boolean = false
            private set

        @Composable
        override fun content() {
            Text("mounted", Modifier.testTag(tag))
        }

        override fun dispose() {
            disposed = true
        }

        companion object {
            const val TAG: String = "udea-test-screen"
        }
    }

    /** A screen whose one node asks for the whole surface, so its box is the viewport's. */
    private class FillingScreen : UiScreen {

        @Composable
        override fun content() {
            Box(Modifier.fillMaxSize().testTag(TAG)) {}
        }

        companion object {
            const val TAG: String = "udea-test-filling"
        }
    }

    /**
     * A screen holding one linear tween, and the last value it was composed with.
     *
     * The field is written from inside the composition on purpose: what the clamp test has to
     * know is the number the animation gave the tree on the frame after the stall, and reading it
     * off the drawn text would mean asserting through the font provider as well.
     */
    private class AnimatingScreen : UiScreen {

        var target: Float by mutableStateOf(0f)

        var observed: Float = Float.NaN
            private set

        @Composable
        override fun content() {
            val value by animateFloatAsState(
                target,
                Tween(durationMillis = 1000, easing = Easings.Linear),
            )
            observed = value
            Text("$value", Modifier.testTag(TAG))
        }

        companion object {
            const val TAG: String = "udea-test-animating"
        }
    }

    private class ResizableOverlay : OverlaySystem, Resizable {

        val resizes = ArrayList<String>()

        override fun render(target: ScreenTarget, dtSeconds: Float): Unit = Unit

        override fun resize(width: Int, height: Int) {
            resizes += "${width}x$height"
        }
    }

    private val ctx: GameContext = testGameContext(seed = 17L)

    private fun world(): World = configureWorld { injectables { gameContext(ctx) } }
}
