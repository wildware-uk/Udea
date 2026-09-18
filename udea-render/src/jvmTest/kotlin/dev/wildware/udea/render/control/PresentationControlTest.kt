package dev.wildware.udea.render.control

import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.render.RenderPipeline
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.render.camera.CameraOutcome
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
        val fixture = rig()
        val rig = fixture.rig
        val debug = DebugDraw(enabled = false)
        val pipeline = pipeline(fixture)
        val control = PresentationControl(pipeline, rig, debug)

        assertEquals(CameraOutcome.APPLIED, control.follow(fixture.spawnFollowable()))
        assertEquals(CameraOutcome.APPLIED, control.lookAt(5f, 6f, 2f))
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

    /**
     * A camera command lands on the camera, and the assertion is the camera and not the call.
     *
     * `follow` reporting [CameraOutcome.APPLIED] is only worth anything if the frame after it
     * actually tracks the entity, so this drives a real frame and reads the position back. The
     * agent-side test for `render.follow_entity` can only see the port; this is the half that
     * proves the port was telling the truth.
     */
    @Test
    fun `an applied follow moves the camera onto the entity`() {
        val fixture = rig()
        val rig = fixture.rig
        rig.followHalfLife = 0f
        val pipeline = pipeline(fixture)
        val control = PresentationControl(pipeline, rig)

        assertEquals(CameraOutcome.APPLIED, control.follow(fixture.spawnFollowable(x = 12f)))
        pipeline.render(alpha = 1f)

        assertEquals(12f, rig.camera.position.x, "the camera did not follow what it accepted")
    }

    /**
     * The three ways a follow request cannot work, each answered by name.
     *
     * `follow` used to take all of them, return `Unit`, and leave the camera where it was — which
     * is what let `render.follow_entity` answer `{"following": n}` for an entity the camera was
     * never going to track.
     */
    @Test
    fun `follow reports why a camera would not move rather than accepting silently`() {
        val fixture = rig()
        val control = PresentationControl(pipeline(fixture), fixture.rig)

        assertEquals(
            CameraOutcome.UNKNOWN_ENTITY,
            control.follow(NetId.of(index = 3, generation = 1)),
        )
        assertEquals(CameraOutcome.UNFOLLOWABLE, control.follow(fixture.spawnPoseless()))
        assertEquals(null, fixture.rig.target, "a refused follow was queued anyway")

        val unbound = fixture.unboundRig()
        assertEquals(
            CameraOutcome.CAMERA_UNBOUND,
            PresentationControl(pipeline(), unbound).follow(fixture.spawnFollowable()),
        )
    }

    /** A host with neither a camera nor a debug switch answers rather than throwing. */
    @Test
    fun `a control with no camera says so instead of accepting the command`() {
        val control = PresentationControl(pipeline())

        assertEquals(CameraOutcome.NO_CAMERA, control.lookAt(1f, 2f, 1f))
        assertEquals(CameraOutcome.NO_CAMERA, control.follow(null))
        assertEquals(CameraOutcome.NO_CAMERA, control.follow(NetId.of(index = 1, generation = 0)))

        assertFalse(control.hasCamera)
        assertFalse(control.toggleDebugDraw(true), "nothing draws debug, so nothing may claim to")
    }

    // --- fixture -----------------------------------------------------------------------------

    private fun rig(): RigFixture = RigFixture()

    /**
     * A bound rig, the world behind it, and the entities a follow test needs.
     *
     * The entities are the point: `followability` resolves an id and asks for a pose, so a test
     * that wants a real answer has to have a real world with real components in it. A rig alone
     * could only ever produce `UNKNOWN_ENTITY`.
     */
    private class RigFixture {

        val ctx = testGameContext(seed = 7L)

        val world = configureWorld {
            injectables { gameContext(ctx) }
            systems { add(InterpSnapshotSystem()) }
        }

        val netIds = NetIdIndex()

        val rig: CameraRig = newRig().also { it.onBind(world, ctx) }

        /** An entity with a body, so `Interpolator` can give the camera a position to track. */
        fun spawnFollowable(x: Float = 4f): NetId =
            netIds.allocate(world.entity { it += PhysicsBody(x = x, y = 0f) })

        /** An entity with no body: live, resolvable, and impossible to follow. `moba`'s case. */
        fun spawnPoseless(): NetId = netIds.allocate(world.entity { })

        /** A rig that was never registered with a registry, so `onBind` never ran. */
        fun unboundRig(): CameraRig = newRig()

        private fun newRig() = CameraRig(
            netIds = netIds,
            poses = Interpolator(ctx.clock, world.system<InterpSnapshotSystem>()),
            frameTime = object : FrameTime {
                override val frameSeconds: Float = 1f / 60f
            },
        )
    }

    /**
     * A pipeline, optionally with [fixture]'s rig in it.
     *
     * When a rig is passed the pipeline is built over **that fixture's world**, and it has to be:
     * `RenderRegistry.build` binds every system it holds, so a pipeline built over a world of its
     * own would silently re-bind the rig away from the world the test spawned its entities in -
     * and every follow would then answer `UNKNOWN_ENTITY` for an entity that plainly exists.
     */
    private fun pipeline(
        fixture: RigFixture? = null,
        pixels: FakePixelSource? = FakePixelSource(),
        offscreenWidth: Int = 64,
        offscreenHeight: Int = 32,
    ): RenderPipeline {
        val ctx = fixture?.ctx ?: testGameContext(seed = 11L)
        val world = fixture?.world ?: configureWorld { injectables { gameContext(ctx) } }
        val registry = RenderRegistry()
        val rig = fixture?.rig
        if (rig != null) registry.register(dev.wildware.udea.render.RenderPhase.PreRender, { rig })
        return registry.build(
            world,
            ctx,
            testTargets(pixels = pixels, width = offscreenWidth, height = offscreenHeight),
        )
    }
}
