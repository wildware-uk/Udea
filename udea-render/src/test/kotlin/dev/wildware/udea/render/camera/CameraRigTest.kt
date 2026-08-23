package dev.wildware.udea.render.camera

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.loop.WorldSimulation
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.render.FrameTime
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.interp.InterpSnapshotSystem
import dev.wildware.udea.render.interp.Interpolator
import dev.wildware.udea.render.support.HeadlessGl
import dev.wildware.udea.render.support.testTargets
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The camera follows, smooths in wall time, clamps to the level — and is not simulation state.
 *
 * The last of those is the one that matters most and is the easiest to lose: `CameraTrackSystem`
 * wrote `gameScreen.camera` from inside the world tick, so a dedicated server ticked a camera
 * and a snapshot's hash depended on where somebody was looking.
 */
class CameraRigTest {

    private val target: OffscreenTarget = testTargets(width = 1280, height = 720).offscreen

    /**
     * A `Viewport.update` binds the GL viewport, so even the arithmetic needs `Gdx.gl` present.
     * Nothing here draws; see [HeadlessGl] for why that one static lookup is worth faking.
     */
    private var gl: HeadlessGl? = null

    @BeforeEach
    fun installGl() {
        gl = HeadlessGl.installed(width = 1280, height = 720)
    }

    @AfterEach
    fun removeGl() {
        gl?.uninstall()
        gl = null
    }

    @Test
    fun `the camera settles at a bounded lag behind a moving target`() {
        val fixture = Fixture()
        val entity = fixture.spawnFollowed(x = 0f, velocityX = 6f)

        val lags = ArrayList<Float>()
        repeat(120) {
            fixture.sim.step()
            fixture.rig.advance(target, alpha = 1f)
            lags += with(fixture.world) { entity[PhysicsBody].x } - fixture.rig.camera.position.x
        }

        val bodyX = with(fixture.world) { entity[PhysicsBody].x }
        assertTrue(bodyX > 10f, "the target must actually have moved, was $bodyX")

        // An exponential follow does *not* reach a moving target: it settles at a constant
        // lag of roughly `speed * halfLife / ln 2`, about 0.87 world units here. Asserting
        // "the camera arrives" would therefore be asserting something false, and a test that
        // demanded it would only pass with the smoothing removed. What must be true is that
        // the lag stops growing.
        val settled = lags.takeLast(30)
        assertTrue(settled.max() - settled.min() < 0.01f, "the lag was still changing: $settled")
        assertTrue(settled.last() < 1f, "the camera fell ${settled.last()} units behind")
    }

    @Test
    fun `the camera arrives exactly on a target that stops`() {
        val fixture = Fixture()
        val entity = fixture.spawnFollowed(x = 0f, velocityX = 6f)
        repeat(60) {
            fixture.sim.step()
            fixture.rig.advance(target, alpha = 1f)
        }

        with(fixture.world) { entity[PhysicsBody].linearX = 0f }
        repeat(120) {
            fixture.sim.step()
            fixture.rig.advance(target, alpha = 1f)
        }

        val bodyX = with(fixture.world) { entity[PhysicsBody].x }
        assertTrue(
            abs(fixture.rig.camera.position.x - bodyX) < 0.001f,
            "camera at ${fixture.rig.camera.position.x}, target at $bodyX",
        )
    }

    @Test
    fun `smoothing is frame rate independent`() {
        // The property a half-life buys, and the reason it is not `position += delta * 0.1f`:
        // two frames of a 120Hz display must move the camera as far as one frame of a 60Hz one.
        // With a fixed per-frame fraction the fast display would arrive twice as quickly, so a
        // camera tuned on one machine feels wrong on another.
        val slow = Fixture(frameSeconds = 1f / 60f).apply { spawnFollowed(x = 10f, velocityX = 0f) }
        val fast = Fixture(frameSeconds = 1f / 120f).apply { spawnFollowed(x = 10f, velocityX = 0f) }
        slow.sim.step()
        fast.sim.step()

        repeat(30) { slow.rig.advance(target, 1f) }
        repeat(60) { fast.rig.advance(target, 1f) }

        assertTrue(
            abs(slow.rig.camera.position.x - fast.rig.camera.position.x) < 0.01f,
            "60Hz reached ${slow.rig.camera.position.x}, 120Hz reached " +
                "${fast.rig.camera.position.x} over the same half second",
        )
    }

    @Test
    fun `a zero half life follows exactly with no easing`() {
        val fixture = Fixture()
        fixture.rig.followHalfLife = 0f
        fixture.spawnFollowed(x = 25f, velocityX = 0f)
        fixture.sim.step()

        fixture.rig.advance(target, alpha = 1f)

        assertEquals(25f, fixture.rig.camera.position.x)
    }

    @Test
    fun `the camera stops at the level edge rather than framing the void`() {
        val fixture = Fixture()
        fixture.rig.followHalfLife = 0f
        fixture.rig.bounds = CameraBounds(minX = 0f, minY = 0f, maxX = 100f, maxY = 100f)
        fixture.spawnFollowed(x = 0f, velocityX = 0f)
        fixture.sim.step()

        fixture.rig.advance(target, alpha = 1f)

        val halfWidth = fixture.rig.viewport.worldWidth / 2f
        assertTrue(halfWidth > 0f, "the viewport must be sized for this assertion to mean anything")
        assertEquals(halfWidth, fixture.rig.camera.position.x)
    }

    @Test
    fun `bounds narrower than the view centre it instead of jamming it against one edge`() {
        val fixture = Fixture()
        fixture.rig.followHalfLife = 0f
        // One world unit wide, far narrower than the ~32-unit viewport.
        fixture.rig.bounds = CameraBounds(minX = 10f, minY = 10f, maxX = 11f, maxY = 11f)
        fixture.spawnFollowed(x = 0f, velocityX = 0f)
        fixture.sim.step()

        fixture.rig.advance(target, alpha = 1f)

        assertEquals(10.5f, fixture.rig.camera.position.x)
        assertEquals(10.5f, fixture.rig.camera.position.y)
    }

    @Test
    fun `following nothing leaves the camera where it was`() {
        val fixture = Fixture()
        fixture.spawnFollowed(x = 40f, velocityX = 0f)
        fixture.rig.target = null
        fixture.sim.step()

        fixture.rig.advance(target, alpha = 1f)

        assertEquals(0f, fixture.rig.camera.position.x)
    }

    @Test
    fun `a world ticked with the rig present is identical to one ticked without it`() {
        // The whole point of moving the camera out of the tick. `CameraTrackSystem` wrote the
        // camera's position into a `Camera` component from inside `onTickEntity`, so the world
        // — and therefore every snapshot taken of it — carried where somebody was looking.
        //
        // The comparison is over the simulated component values rather than
        // `WorldHasher.hash`, because the hasher reads a `WorldFieldStore` built from generated
        // `Replicator`s and no component in this module has one. For this fixture the two are
        // the same statement: `PhysicsBody` is the entire simulation state.
        val withRig = Fixture()
        val withoutRig = Fixture()
        withRig.spawnFollowed(x = 0f, velocityX = 3f)
        withoutRig.spawnFollowed(x = 0f, velocityX = 3f)

        repeat(120) {
            withRig.sim.step()
            withRig.rig.advance(target, alpha = 0.5f)
            withoutRig.sim.step()
        }

        assertTrue(withRig.rig.camera.position.x > 1f, "the rig must have moved to mean anything")
        assertEquals(simulationDigest(withoutRig), simulationDigest(withRig))
    }

    /**
     * Every simulated value in a fixture's world, as a comparable string.
     *
     * Raw bits, not printed floats: two positions differing in the last ulp print identically
     * and would make this assertion pass over a real divergence.
     */
    private fun simulationDigest(fixture: Fixture): String {
        val entries = ArrayList<String>()
        with(fixture.world) {
            fixture.world.family { all(PhysicsBody) }.forEach { entity ->
                val body = entity[PhysicsBody]
                entries += listOf(body.x, body.y, body.angle, body.linearX, body.linearY)
                    .joinToString(",") { it.toRawBits().toString() }
            }
        }
        return "tick=${fixture.ctx.clock.tick.value} entities=${entries.sorted()}"
    }

    /** A world, a simulation, a NetId index and a rig wired the way a game wires them. */
    // --- the control surface: what `render.set_camera` and `render.follow_entity` reach ------

    /**
     * A placement asked for from another thread lands at the next frame, and not before it.
     *
     * The "not before" half is the point. `render.set_camera` arrives on the simulation thread,
     * which on an `Offscreen` or `Windowed` host is also the render thread — but a host that
     * pumps its agent loop separately is a legitimate arrangement, and writing the camera from
     * that thread would tear the projection matrix a frame is being drawn with. Queuing it makes
     * the question moot at the cost of one uncontended CAS per frame.
     */
    @Test
    fun `a requested placement is applied at the next frame and not before`() {
        val fixture = Fixture()

        fixture.rig.requestLookAt(x = 12f, y = -4f, zoom = 2f)

        assertEquals(0f, fixture.rig.camera.position.x, "the camera moved before a frame was drawn")

        fixture.rig.advance(target, alpha = 1f)

        assertEquals(12f, fixture.rig.camera.position.x)
        assertEquals(-4f, fixture.rig.camera.position.y)
        assertEquals(2f, fixture.rig.camera.zoom)
    }

    /**
     * Placing the camera stops it following.
     *
     * Without this, an agent that asked to look at a corner of the map while the rig was tracking
     * a unit would see the camera snap straight back on the same frame that applied its request —
     * a tool that reports success and visibly does nothing, which is worse than a refusal.
     */
    @Test
    fun `a placement stops the rig following`() {
        val fixture = Fixture()
        fixture.spawnFollowed(x = 40f, velocityX = 0f)
        fixture.sim.step()
        fixture.rig.advance(target, alpha = 1f)
        assertTrue(fixture.rig.camera.position.x > 1f, "the rig never reached its target")

        fixture.rig.requestLookAt(x = 0f, y = 0f, zoom = 1f)
        fixture.rig.advance(target, alpha = 1f)
        fixture.rig.advance(target, alpha = 1f)

        assertEquals(null, fixture.rig.target, "the placement did not stop the follow")
        assertEquals(0f, fixture.rig.camera.position.x, "the follow dragged the camera back")
    }

    /** A follow request is applied at the next frame, and a null one stops following. */
    @Test
    fun `a requested follow target is applied at the next frame`() {
        val fixture = Fixture()
        val entity = fixture.world.entity { it += PhysicsBody(x = 25f, y = 0f) }
        val netId = fixture.netIds.allocate(entity)
        fixture.rig.followHalfLife = 0f

        fixture.rig.requestFollow(netId)
        fixture.rig.advance(target, alpha = 1f)

        assertEquals(netId, fixture.rig.target)
        assertTrue(abs(fixture.rig.camera.position.x - 25f) < 0.001f)

        fixture.rig.requestFollow(null)
        fixture.rig.advance(target, alpha = 1f)

        assertEquals(null, fixture.rig.target, "a null follow request must stop following")
    }

    /** A zoom of zero collapses the projection; it is refused where it is asked for. */
    @Test
    fun `a placement with a non-positive zoom is refused`() {
        val fixture = Fixture()

        assertFailsWith<IllegalArgumentException> { fixture.rig.requestLookAt(0f, 0f, 0f) }
        assertFailsWith<IllegalArgumentException> { fixture.rig.requestLookAt(0f, 0f, -1f) }
        assertFailsWith<IllegalArgumentException> {
            fixture.rig.requestLookAt(Float.NaN, 0f, 1f)
        }

        fixture.rig.advance(target, alpha = 1f)
        assertEquals(1f, fixture.rig.camera.zoom, "a refused placement reached the camera anyway")
    }

    private class Fixture(frameSeconds: Float = 1f / 60f) {

        val ctx: GameContext = testGameContext(seed = 42L)

        val world: World = configureWorld {
            injectables { gameContext(ctx) }
            systems {
                add(InterpSnapshotSystem())
                add(MoveSystem())
            }
        }

        val sim = WorldSimulation(ctx, world)

        val netIds = NetIdIndex()

        val rig = CameraRig(
            netIds = netIds,
            interpolator = Interpolator(ctx.clock, world.system<InterpSnapshotSystem>()),
            frameTime = FixedFrameTime(frameSeconds),
        ).also { it.onBind(world, ctx) }

        fun spawnFollowed(x: Float, velocityX: Float) = world.entity {
            it += PhysicsBody(x = x, y = 0f, linearX = velocityX)
        }.also { entity ->
            rig.target = netIds.allocate(entity)
        }
    }

    /** A [FrameTime] that always reports the same frame length. Time in tests is never the wall. */
    private class FixedFrameTime(override val frameSeconds: Float) : FrameTime

    private class MoveSystem : SimSystem() {

        private val bodies = world.family { all(PhysicsBody) }

        override fun onTick() {
            bodies.forEach { entity ->
                val body = entity[PhysicsBody]
                body.x += body.linearX * ctx.clock.dt
            }
        }
    }
}
