package dev.wildware.udea.render.control

import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.render.RenderPipeline
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.camera.CameraRig
import dev.wildware.udea.render.capture.CaptureRegion
import dev.wildware.udea.render.draw.DebugDraw
import dev.wildware.udea.render.interp.InterpSnapshotSystem
import dev.wildware.udea.render.interp.Interpolator
import dev.wildware.udea.render.support.FakePixelSource
import dev.wildware.udea.render.support.HeadlessGl
import dev.wildware.udea.render.support.testTargets
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.render.FrameTime
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.util.concurrent.CompletionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The presentation side of the agent's render toolset, with no GL context behind it.
 *
 * Every claim here is about *wiring* — which framebuffer a region is measured against, which
 * thread a capture may be asked for from, what a pipeline with no pixel source answers — and all
 * of it is checkable in a plain JVM because `PixelSource` is the seam. The pixels themselves are
 * `GlCaptureTest`'s.
 */
class PresentationControlTest {

    /** `Viewport.update` reaches `Gdx.gl` through `HdpiUtils`, and the rig binds one. */
    private var gl: HeadlessGl? = null

    @BeforeEach
    fun installGl() {
        gl = HeadlessGl.installed(width = 64, height = 32)
    }

    @AfterEach
    fun removeGl() {
        gl?.uninstall()
        gl = null
    }

    @Test
    fun `the framebuffer size is the offscreen target's and not the window's`() {
        val control = PresentationControl(pipeline(offscreenWidth = 64, offscreenHeight = 32))

        assertEquals(64, control.framebufferWidth)
        assertEquals(32, control.framebufferHeight)
    }

    /**
     * A capture is queued without blocking, and served by the next frame.
     *
     * The first assertion is the one the whole toolset design rests on: the tool that asks for a
     * capture runs on the thread that draws the frame serving it, so an implementation that
     * waited here would wait forever.
     */
    @Test
    fun `capture returns an unsettled future and the next frame settles it`() {
        val pipeline = pipeline()
        val control = PresentationControl(pipeline)

        val pending = control.capture()

        assertFalse(pending.isDone, "capture must not wait for a frame")

        pipeline.render(alpha = 0f)

        assertTrue(pending.isDone)
        assertEquals(64, pending.join().width)
    }

    @Test
    fun `a region is carried through to the read verbatim`() {
        val pixels = FakePixelSource()
        val pipeline = pipeline(pixels = pixels)
        val control = PresentationControl(pipeline)

        control.capture(CaptureRegion(4, 8, 16, 16))
        pipeline.render(alpha = 0f)

        assertEquals(listOf("4,8,16,16"), pixels.requests)
    }

    /**
     * A pipeline with no way to read pixels says so, rather than handing back a future nothing
     * will ever settle.
     *
     * The difference matters to the caller: an unsettled future looks like a slow renderer and is
     * waited out, while this is a host wiring fault that no amount of waiting fixes.
     */
    @Test
    fun `a pipeline with no pixel source fails the capture immediately`() {
        val control = PresentationControl(pipeline(pixels = null))

        val pending = control.capture()

        assertTrue(pending.isDone)
        val failure = assertFailsWith<CompletionException> { pending.join() }.cause
        assertTrue(failure is IllegalStateException, "was $failure")
        assertFalse(control.capturable)
    }

    @Test
    fun `camera commands reach the rig and debug commands reach the switch`() {
        val rig = rig()
        val debug = DebugDraw(enabled = false)
        val pipeline = pipeline(rig)
        val control = PresentationControl(pipeline, rig, debug)

        control.lookAt(5f, 6f, 2f)
        control.follow(NetId.of(index = 3, generation = 1))
        pipeline.render(alpha = 0f)

        // `follow` is applied first and `lookAt` second, in one frame: the placement wins and
        // clears the target, which is the documented resolution of asking for both at once.
        assertEquals(5f, rig.camera.position.x)
        assertEquals(2f, rig.camera.zoom)
        assertEquals(null, rig.target)

        assertTrue(control.toggleDebugDraw(null))
        assertTrue(debug.enabled)
        assertFalse(control.toggleDebugDraw(false))
        assertFalse(debug.enabled)
    }

    /** A host with neither a camera nor a debug switch answers rather than throwing. */
    @Test
    fun `a control with no camera and no debug switch still answers`() {
        val control = PresentationControl(pipeline())

        control.lookAt(1f, 2f, 1f)
        control.follow(null)

        assertFalse(control.hasCamera)
        assertFalse(control.toggleDebugDraw(true), "nothing draws debug, so nothing may claim to")
    }

    // --- fixture -----------------------------------------------------------------------------

    private fun rig(): CameraRig {
        val ctx = testGameContext(seed = 7L)
        val world = configureWorld {
            injectables { gameContext(ctx) }
            systems { add(InterpSnapshotSystem()) }
        }
        return CameraRig(
            netIds = NetIdIndex(),
            interpolator = Interpolator(ctx.clock, world.system<InterpSnapshotSystem>()),
            frameTime = object : FrameTime {
                override val frameSeconds: Float = 1f / 60f
            },
        ).also { it.onBind(world, ctx) }
    }

    private fun pipeline(
        rig: CameraRig? = null,
        pixels: FakePixelSource? = FakePixelSource(),
        offscreenWidth: Int = 64,
        offscreenHeight: Int = 32,
    ): RenderPipeline {
        val ctx = testGameContext(seed = 11L)
        val world = configureWorld { injectables { gameContext(ctx) } }
        val registry = RenderRegistry()
        if (rig != null) registry.register(dev.wildware.udea.render.RenderPhase.PreRender, { rig })
        return registry.build(
            world,
            ctx,
            testTargets(pixels = pixels, width = offscreenWidth, height = offscreenHeight),
        )
    }
}
