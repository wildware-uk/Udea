package dev.wildware.udea.render.ui

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Stage
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.render.FrameTime
import dev.wildware.udea.render.OverlaySystem
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderPipeline
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.Resizable
import dev.wildware.udea.render.ScreenTarget
import dev.wildware.udea.render.support.HeadlessGl
import dev.wildware.udea.render.support.RecordingBatch
import dev.wildware.udea.render.support.testTargets
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The scene2d layer: it acts once per frame, clamps its delta, and owns its screen's lifecycle.
 *
 * Driven with no GL context, because the `Stage` is handed a recording `Batch` and the
 * viewport arithmetic runs against [HeadlessGl]. That is the whole reason `UiLayer` takes a
 * stage factory: the old `UIScreen` built its own `Stage` — and therefore its own
 * `SpriteBatch` — so nothing about it could be checked without a window.
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
    fun `sixty frames act the stage exactly sixty times`() {
        val layer = layer()
        val pipeline = pipelineWith(layer)
        frameTime.frameSeconds = 1f / 60f

        repeat(60) { pipeline.render(0f) }

        assertEquals(60L, layer.actCount)
    }

    @Test
    fun `a stalled frame is clamped so an action does not jump to its end state`() {
        val layer = layer()
        val recorder = RecordingAction()
        layer.show(ScreenOf(Group().apply { addAction(recorder) }))
        val pipeline = pipelineWith(layer)

        frameTime.frameSeconds = 1f / 60f
        pipeline.render(0f)
        // A breakpoint, a shader compile, a GC pause. The pipeline itself clamps at 0.25s;
        // scene2d is clamped tighter still, because a UI action is measured in tenths.
        frameTime.frameSeconds = 40f
        pipeline.render(0f)

        assertEquals(2, recorder.deltas.size)
        assertEquals(1f / 60f, recorder.deltas[0])
        assertEquals(UiLayer.MAX_ACT_SECONDS, recorder.deltas[1])
    }

    @Test
    fun `showing a screen mounts its root and hiding removes and disposes it`() {
        val layer = layer()
        val screen = ScreenOf(Actor())

        layer.show(screen)
        assertSame(layer.stage, screen.built?.stage, "the root was never added to the stage")

        layer.hide()

        assertNull(screen.built?.stage, "the root is still in the stage")
        assertTrue(screen.disposed, "the screen was not disposed")
    }

    @Test
    fun `showing a second screen replaces the first rather than stacking on it`() {
        // The failure this prevents: a loading screen's actors left behind a menu, still
        // receiving clicks from a player who cannot see them.
        val layer = layer()
        val first = ScreenOf(Actor())
        val second = ScreenOf(Actor())

        layer.show(first)
        layer.show(second)

        assertTrue(first.disposed, "the first screen was not disposed")
        assertNull(first.built?.stage)
        assertSame(layer.stage, second.built?.stage)
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

    // --- fixture -------------------------------------------------------------------------

    private fun layer(): UiLayer = UiLayer(frameTime) { viewport -> Stage(viewport, batch.batch) }

    private fun pipelineWith(layer: UiLayer): RenderPipeline {
        val registry = RenderRegistry()
        registry.register(RenderPhase.UI, { layer })
        return registry.build(world(), ctx, targets)
    }

    private class MutableFrameTime(override var frameSeconds: Float = 0f) : FrameTime

    /** A [UiScreen] over a ready-made actor, so a test can hold on to what was mounted. */
    private class ScreenOf(private val actor: Actor) : UiScreen {

        var built: Actor? = null
            private set

        var disposed: Boolean = false
            private set

        override fun build(stage: Stage): Actor = actor.also { built = it }

        override fun dispose() {
            disposed = true
        }
    }

    /** A scene2d action that records every delta it is advanced by. */
    private class RecordingAction : com.badlogic.gdx.scenes.scene2d.Action() {

        val deltas = ArrayList<Float>()

        override fun act(delta: Float): Boolean {
            deltas += delta
            return false
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
