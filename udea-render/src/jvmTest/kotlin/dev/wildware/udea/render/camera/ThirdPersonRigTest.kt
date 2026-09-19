package dev.wildware.udea.render.camera

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.loop.WorldSimulation
import dev.wildware.udea.core.rng.DefaultRngService
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.SnapshotService
import dev.wildware.udea.core.snapshot.WorldHasher
import dev.wildware.udea.core.snapshot.fleksComponentType
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.core.spatial.Transform3DReplicator
import dev.wildware.udea.render.FrameTime
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.draw.SpriteBatch2D
import dev.wildware.udea.render.draw.SpriteRecord
import dev.wildware.udea.render.draw.SpriteTexture
import dev.wildware.udea.render.input.PointerMotion
import dev.wildware.udea.render.interp.Interp3DSnapshotSystem
import dev.wildware.udea.render.input.PointerState
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.view.WorldViewport
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The third-person rig (issue #248): behind and above its target at the distance it was given,
 * turned by the mouse, smoothed in seconds, and not simulation state.
 *
 * Everything here is arithmetic on a [ModelCamera], so it needs no render context. What the
 * camera's numbers look like on screen - the target in the middle of the picture, at the size its
 * distance gives it - is `GlThirdPersonRigTest`'s, through a real Kool context.
 */
class ThirdPersonRigTest {

    @Test
    fun `the camera looks at the target from the configured distance, from behind and above`() {
        val fixture = Fixture()
        fixture.rig.followHalfLife = 0f
        fixture.rig.distance = 6f
        fixture.rig.focusHeight = 1f
        fixture.rig.yawDegrees = 0f
        fixture.rig.pitchDegrees = 30f
        fixture.spawnFollowed(x = 3f, y = 4f, z = 0.5f)

        fixture.frame()

        val camera = fixture.rig.camera
        assertNear(3f, camera.targetX, "looks at the target's x")
        assertNear(4f, camera.targetY, "looks at the target's y")
        assertNear(1.5f, camera.targetZ, "looks at the target's z plus focusHeight")
        assertNear(6f, eyeDistance(camera), "the eye is the configured distance from the target")
        // Facing +X, so behind is -X; 30 degrees up puts the eye 3 above the focus.
        assertNear(3f - 6f * cos(radians(30f)), camera.eyeX, "the eye is behind the target along the facing")
        assertNear(4f, camera.eyeY, "the eye is not off to one side")
        assertNear(1.5f + 3f, camera.eyeZ, "the eye is raised by the pitch")
    }

    @Test
    fun `the camera keeps the target centred and at its distance as it moves`() {
        val fixture = Fixture()
        fixture.rig.followHalfLife = 0f
        fixture.rig.distance = 8f
        fixture.rig.focusHeight = 0f
        val entity = fixture.spawnFollowed(x = 0f, y = 0f, z = 0f)

        for (step in 1..5) {
            with(fixture.world) {
                entity[Transform3D].x = step * 2.5f
                entity[Transform3D].y = -step * 1.5f
                entity[Transform3D].z = step * 0.25f
            }
            fixture.frame()
            val camera = fixture.rig.camera
            assertNear(step * 2.5f, camera.targetX, "step $step: the view is not centred on the target")
            assertNear(-step * 1.5f, camera.targetY, "step $step: the view is not centred on the target")
            assertNear(step * 0.25f, camera.targetZ, "step $step: the view is not centred on the target")
            assertNear(8f, eyeDistance(camera), "step $step: the eye drifted off the configured distance")
        }
    }

    @Test
    fun `mouse motion turns the view, and is spent by the frame that used it`() {
        val fixture = Fixture()
        fixture.rig.degreesPerPixel = 0.2f
        fixture.rig.yawDegrees = 90f
        fixture.rig.pitchDegrees = 20f
        fixture.spawnFollowed(x = 0f, y = 0f, z = 0f)

        // Right and down: the view turns right (the yaw falls, counter-clockwise being positive
        // about Z) and tips down (the eye rises).
        fixture.motion.move(dx = 50f, dy = 25f)
        fixture.frame()

        assertNear(80f, fixture.rig.yawDegrees, "moving the mouse 50 pixels right turned the view")
        assertNear(25f, fixture.rig.pitchDegrees, "moving the mouse 25 pixels down tipped the view")
        assertEquals(1, fixture.motion.spent, "the rig did not spend the motion it read")

        fixture.frame()
        assertNear(80f, fixture.rig.yawDegrees, "motion already spent turned the view again")
    }

    @Test
    fun `the view the mouse turned is the one the camera draws`() {
        val fixture = Fixture()
        fixture.rig.followHalfLife = 0f
        fixture.rig.yawDegrees = 0f
        fixture.rig.pitchDegrees = 20f
        fixture.spawnFollowed(x = 0f, y = 0f, z = 0f)
        fixture.frame()
        val before = groundBearing(fixture.rig.camera)

        fixture.motion.move(dx = -450f, dy = 0f)
        fixture.frame()

        // -450 pixels at the default sensitivity is a left turn; the eye's bearing from the target
        // is what a screenshot shows, so the drawn camera has to have turned with the yaw.
        val after = groundBearing(fixture.rig.camera)
        assertNear(fixture.rig.yawDegrees, after, "the drawn camera does not face where the yaw says")
        assertTrue(abs(after - before) > 45f, "the drawn camera did not turn: $before to $after degrees")
    }

    @Test
    fun `pitch stays inside its limits however far the mouse goes`() {
        val fixture = Fixture()
        fixture.rig.minPitchDegrees = 5f
        fixture.rig.maxPitchDegrees = 70f
        fixture.spawnFollowed(x = 0f, y = 0f, z = 0f)

        fixture.motion.move(dx = 0f, dy = 100_000f)
        fixture.frame()
        assertEquals(70f, fixture.rig.pitchDegrees, "the view tipped past straight down's limit")

        fixture.motion.move(dx = 0f, dy = -100_000f)
        fixture.frame()
        assertEquals(5f, fixture.rig.pitchDegrees, "the view tipped past the lower limit")
        assertTrue(fixture.rig.camera.eyeZ > fixture.rig.camera.targetZ, "the eye went below its target")
    }

    @Test
    fun `a pitch limit is refused when it would put the eye straight above or the limits cross`() {
        val rig = Fixture().rig
        assertFailsWith<IllegalArgumentException> { rig.maxPitchDegrees = 90f }
        assertFailsWith<IllegalArgumentException> { rig.minPitchDegrees = -90f }
        rig.maxPitchDegrees = 40f
        assertFailsWith<IllegalArgumentException> { rig.minPitchDegrees = 50f }
        assertFailsWith<IllegalArgumentException> { rig.distance = 0f }
        assertFailsWith<IllegalArgumentException> { rig.followHalfLife = -1f }
    }

    @Test
    fun `with a turn button the mouse turns the view only while the button is held`() {
        val fixture = Fixture()
        fixture.rig.turnButton = RIGHT_BUTTON
        fixture.rig.yawDegrees = 0f
        fixture.spawnFollowed(x = 0f, y = 0f, z = 0f)

        fixture.motion.move(dx = 100f, dy = 0f)
        fixture.frame()
        assertEquals(0f, fixture.rig.yawDegrees, "the view turned with the turn button up")
        assertEquals(1, fixture.motion.spent, "motion with the button up must be spent, or it lands when it goes down")

        fixture.buttons.down = RIGHT_BUTTON
        fixture.motion.move(dx = 100f, dy = 0f)
        fixture.frame()
        assertNear(-20f, fixture.rig.yawDegrees, "the view did not turn with the turn button held")
    }

    @Test
    fun `the ground facing is the camera's own, as plain floats`() {
        val fixture = Fixture()
        fixture.rig.followHalfLife = 0f
        fixture.spawnFollowed(x = 1f, y = 2f, z = 0f)

        for (yaw in listOf(0f, 90f, 135f, -60f)) {
            fixture.rig.yawDegrees = yaw
            fixture.frame()
            val rig = fixture.rig
            val camera = rig.camera
            // Forward is where the camera looks, flattened onto the ground: from the eye towards the
            // target, with the height taken out.
            val lookX = camera.targetX - camera.eyeX
            val lookY = camera.targetY - camera.eyeY
            val length = sqrt(lookX * lookX + lookY * lookY)
            assertNear(lookX / length, rig.forwardX, "yaw $yaw: forward is not where the camera looks")
            assertNear(lookY / length, rig.forwardY, "yaw $yaw: forward is not where the camera looks")
            // Right is a quarter turn clockwise of forward, seen from above.
            assertNear(rig.forwardY, rig.rightX, "yaw $yaw: right is not a quarter turn clockwise")
            assertNear(-rig.forwardX, rig.rightY, "yaw $yaw: right is not a quarter turn clockwise")
        }
    }

    @Test
    fun `smoothing eases towards a moved target, frame-rate independently`() {
        val slow = Fixture(frameSeconds = 1f / 60f)
        val fast = Fixture(frameSeconds = 1f / 120f)
        val slowTarget = slow.spawnFollowed(x = 0f, y = 0f, z = 0f)
        val fastTarget = fast.spawnFollowed(x = 0f, y = 0f, z = 0f)
        slow.frame()
        fast.frame()

        with(slow.world) { slowTarget[Transform3D].x = 10f }
        with(fast.world) { fastTarget[Transform3D].x = 10f }
        slow.frame()
        assertTrue(slow.rig.camera.targetX in 0.5f..9.5f, "the camera jumped rather than eased: ${slow.rig.camera.targetX}")

        repeat(9) { slow.frame() }
        repeat(20) { fast.frame() }
        assertNear(slow.rig.camera.targetX, fast.rig.camera.targetX, "60Hz and 120Hz disagree over the same sixth of a second")

        repeat(120) { slow.frame() }
        assertNear(10f, slow.rig.camera.targetX, "the camera never arrived on a target that stopped")
    }

    /**
     * The rig follows the target where its model is drawn: between the last two ticks at the render
     * alpha (issue #246), not where the last tick left it. Following the raw transform would put the
     * model a fraction of a tick off the middle of the picture on every frame between two ticks.
     */
    @Test
    fun `the camera follows the interpolated pose the model is drawn at`() {
        val fixture = Fixture(interpolated = true)
        fixture.rig.followHalfLife = 0f
        fixture.rig.focusHeight = 0f
        val entity = fixture.spawnFollowed(x = 0f, y = 0f, z = 0f, moving = true)
        fixture.sim.step()
        val before = with(fixture.world) { entity[Transform3D].x }
        fixture.sim.step()
        val after = with(fixture.world) { entity[Transform3D].x }
        assertTrue(after > before, "the target must have moved between the two ticks: $before to $after")

        fixture.frame(alpha = 0.5f)

        assertNear((before + after) / 2f, fixture.rig.camera.targetX, "the camera is not on the pose drawn at alpha 0.5")
    }

    @Test
    fun `a new target is framed at once rather than eased to from the old one`() {
        val fixture = Fixture()
        fixture.spawnFollowed(x = 0f, y = 0f, z = 0f)
        fixture.frame()

        fixture.spawnFollowed(x = 40f, y = -30f, z = 0f)
        fixture.frame()

        assertNear(40f, fixture.rig.camera.targetX, "the camera eased across the level to its new target")
        assertNear(-30f, fixture.rig.camera.targetY, "the camera eased across the level to its new target")
    }

    @Test
    fun `an editor Scene view's second run does not move the camera again`() {
        val fixture = Fixture()
        fixture.rig.followHalfLife = 0f
        val entity = fixture.spawnFollowed(x = 0f, y = 0f, z = 0f)
        fixture.frame()

        with(fixture.world) { entity[Transform3D].x = 5f }
        fixture.motion.move(dx = 100f, dy = 0f)
        val yaw = fixture.rig.yawDegrees
        fixture.resources.viewing.current = sceneView()
        try {
            fixture.rig.render(fixture.target, alpha = 1f)
        } finally {
            fixture.resources.viewing.current = null
        }

        assertEquals(0f, fixture.rig.camera.targetX, "a Scene view's run followed the target")
        assertEquals(yaw, fixture.rig.yawDegrees, "a Scene view's run turned the view")
        assertEquals(0, fixture.motion.spent, "a Scene view's run spent the game's mouse motion")
    }

    @Test
    fun `a world ticked with the rig present hashes the same as one ticked without it`() {
        val withRig = Fixture()
        val without = Fixture()
        withRig.spawnFollowed(x = 0f, y = 0f, z = 0f, moving = true)
        without.spawnFollowed(x = 0f, y = 0f, z = 0f, moving = true)

        repeat(TICKS) {
            withRig.motion.move(dx = 7f, dy = 3f)
            withRig.sim.step()
            withRig.frame()
            without.sim.step()
        }

        assertTrue(abs(withRig.rig.camera.targetX) > 1f, "the rig never followed anything, so this asserts nothing")
        assertEquals(without.hash(), withRig.hash())
    }

    @Test
    fun `the hash this rests on sees a one-ulp nudge to the followed transform`() {
        // The control for the test above: an equality over a hash that cannot tell two worlds apart
        // is a test that cannot fail. A rig that wrote the smallest possible change into the
        // transform it follows must come out different.
        val plain = Fixture()
        val nudged = Fixture()
        plain.spawnFollowed(x = 0f, y = 0f, z = 0f, moving = true)
        val entity = nudged.spawnFollowed(x = 0f, y = 0f, z = 0f, moving = true)
        repeat(TICKS) {
            plain.sim.step()
            nudged.sim.step()
        }
        with(nudged.world) { entity[Transform3D].z = Math.nextUp(entity[Transform3D].z) }

        assertNotEquals(plain.hash(), nudged.hash())
    }

    // --- fixture -------------------------------------------------------------------------

    private class Fixture(frameSeconds: Float = 1f / 60f, interpolated: Boolean = false) {

        // A capturable random service: the snapshot the hash is taken over carries its streams.
        val ctx: GameContext = testGameContext(seed = SEED) { rng = DefaultRngService(SEED) }

        val world: World = configureWorld {
            injectables { gameContext(ctx) }
            systems {
                add(WalkSystem())
                // Last, as `RenderModule` registers it: it records the pose the whole tick produced.
                if (interpolated) add(Interp3DSnapshotSystem())
            }
        }

        val sim = WorldSimulation(ctx, world)

        val netIds = NetIdIndex()

        val target: OffscreenTarget = OffscreenTarget(WIDTH, HEIGHT)

        val resources = RenderResources(SpriteBatch2D(SpriteTexture.whitePixel("rig-test-white")), target)

        val motion = FakeMotion()

        val buttons = FakeButtons()

        val rig = ThirdPersonRig(resources, netIds, FixedFrameTime(frameSeconds)).also {
            it.motion = motion
            it.buttons = buttons
            it.onBind(world, ctx)
        }

        private val snapshots = SnapshotService(transformOnly(), world, ctx, netIds)

        fun spawnFollowed(x: Float, y: Float, z: Float, moving: Boolean = false): Entity = world.entity {
            it += Transform3D(x = x, y = y, z = z)
            if (moving) it += Walker()
        }.also { entity -> rig.target = netIds.allocate(entity) }

        fun frame(alpha: Float = 1f) = rig.render(target, alpha)

        fun hash(): Long = WorldHasher.hash(snapshots.capture())
    }

    /** Mouse motion a test puts in, and a count of how often the rig spent it. */
    private class FakeMotion : PointerMotion {
        override var motionX: Float = 0f
            private set
        override var motionY: Float = 0f
            private set
        var spent = 0

        fun move(dx: Float, dy: Float) {
            motionX += dx
            motionY += dy
        }

        override fun spendMotion() {
            if (motionX != 0f || motionY != 0f) spent++
            motionX = 0f
            motionY = 0f
        }
    }

    /** One button held down, or none. */
    private class FakeButtons : PointerState {
        var down: Int = -1
        override fun isButtonDown(button: Int): Boolean = button == down
        override fun pressesSince(button: Int): Int = 0
        override fun endSample() = Unit
    }

    private class FixedFrameTime(override val frameSeconds: Float) : FrameTime

    /** Marks a transform [WalkSystem] moves. */
    private class Walker : com.github.quillraven.fleks.Component<Walker> {
        override fun type() = Walker

        companion object : com.github.quillraven.fleks.ComponentType<Walker>()
    }

    /** Walks every [Walker] along a slow curve, so there is a simulation for the rig to follow. */
    private class WalkSystem : SimSystem() {

        private val walkers = world.family { all(Transform3D, Walker) }

        override fun onTick() {
            walkers.forEach { entity ->
                val transform = entity[Transform3D]
                transform.x += 3f * ctx.clock.dt
                transform.y += 0.5f * ctx.clock.dt
                transform.rotationZ += 0.2f * ctx.clock.dt
            }
        }
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 360
        const val TICKS = 120
        const val SEED = 7L

        /** Kool's right mouse button. */
        const val RIGHT_BUTTON = 1

        const val TOLERANCE = 1e-3f

        fun transformOnly(): ComponentRegistry = ComponentRegistry(
            listOf(
                fleksComponentType(
                    Transform3DReplicator,
                    ComponentSchema.of(Transform3DReplicator, "Transform3D", List(Transform3DReplicator.FIELD_COUNT) { FieldKind.Float }),
                    Transform3D,
                ) { Transform3D() },
            ),
        )

        fun sceneView(): WorldViewport {
            val record = SpriteRecord()
            return WorldViewport(
                camera = null,
                target = OffscreenTarget(WIDTH, HEIGHT),
                record = record,
                batch = SpriteBatch2D(SpriteTexture.whitePixel("rig-test-view-white"), home = record),
                frame = null,
                captures = null,
                kool = null,
            )
        }

        fun radians(degrees: Float): Float = (degrees * Math.PI / 180.0).toFloat()

        fun eyeDistance(camera: ModelCamera): Float {
            val dx = camera.eyeX - camera.targetX
            val dy = camera.eyeY - camera.targetY
            val dz = camera.eyeZ - camera.targetZ
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        /** Degrees about Z of the direction the drawn camera looks along, seen from above. */
        fun groundBearing(camera: ModelCamera): Float = Math.toDegrees(
            atan2((camera.targetY - camera.eyeY).toDouble(), (camera.targetX - camera.eyeX).toDouble()),
        ).toFloat()

        fun assertNear(expected: Float, actual: Float, message: String) {
            assertTrue(abs(expected - actual) <= TOLERANCE * maxOf(1f, abs(expected)), "$message: expected $expected, was $actual")
        }
    }
}
