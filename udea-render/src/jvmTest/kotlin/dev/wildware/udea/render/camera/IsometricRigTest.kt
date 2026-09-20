package dev.wildware.udea.render.camera

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
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
import dev.wildware.udea.render.model.ModelProjection
import dev.wildware.udea.render.view.WorldViewport
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The isometric rig (issue #257): the camera an isometric strategy game is played through - tilted
 * 30 degrees above the ground at a yaw of 45, flattened so distance does not shrink anything, slid
 * across the ground and zoomed by how much of the world fits in the picture.
 *
 * Everything here is arithmetic on a `ModelCamera`, so it needs no render context, exactly as
 * [ThirdPersonRigTest] is to its own rig. What those numbers look like on screen - a cube the same
 * size wherever it stands, with edges that stay parallel - is `GlIsoCameraTest`'s, through a real
 * Kool context.
 *
 * The rule the two rigs share and that matters most: **this reads nothing from the world and writes
 * nothing into it.** It is a `RenderSystem`, never a Fleks system, so a dedicated server has no
 * camera at all and a snapshot carries nothing about where anybody was looking. The last two tests
 * are that claim and its control.
 */
class IsometricRigTest {

    // --- the preset ------------------------------------------------------------------------

    @Test
    fun `the rig starts at the isometric preset - thirty degrees above the ground at a yaw of forty-five`() {
        val fixture = Fixture()

        assertEquals(45f, fixture.rig.yawDegrees, "the preset yaw is 45 degrees")
        assertEquals(30f, fixture.rig.pitchDegrees, "the preset pitch is 30 degrees above the ground")
    }

    @Test
    fun `the eye sits at the rig's pitch and yaw, its distance out from what it looks at`() {
        val fixture = Fixture()
        fixture.rig.eyeDistance = 40f
        fixture.frame()

        val camera = fixture.rig.camera
        assertNear(0f, camera.targetX, "the rig looks at its focus")
        assertNear(0f, camera.targetY, "the rig looks at its focus")
        assertNear(0f, camera.targetZ, "the rig looks at the ground")
        assertNear(40f, eyeDistance(fixture), "the eye is not the distance it was given from the focus")
        // Yaw 45 and pitch 30: the eye is back along the yaw and up by the pitch.
        val level = 40f * cos(radians(30f))
        assertNear(-level * cos(radians(45f)), camera.eyeX, "the eye is not back along the yaw")
        assertNear(-level * sin(radians(45f)), camera.eyeY, "the eye is not back along the yaw")
        assertNear(40f * sin(radians(30f)), camera.eyeZ, "the eye is not raised by the pitch")
    }

    @Test
    fun `the rig draws through an orthographic camera of its view height`() {
        val fixture = Fixture()
        fixture.rig.viewHeight = 18f
        fixture.frame()

        assertEquals(ModelProjection.Orthographic, fixture.rig.camera.projection, "an isometric rig must flatten the world")
        assertEquals(18f, fixture.rig.camera.viewHeight, "the camera does not show the world the rig's view height")
    }

    @Test
    fun `the rig reaches further than the world it can see, so nothing near the camera is clipped away`() {
        val fixture = Fixture()
        fixture.rig.eyeDistance = 50f
        fixture.rig.viewHeight = 30f
        fixture.frame()

        val camera = fixture.rig.camera
        assertTrue(camera.near < 50f, "the near plane is beyond the focus, so the ground would vanish: ${camera.near}")
        assertTrue(camera.far > 50f, "the far plane is nearer than the focus, so the ground would vanish: ${camera.far}")
    }

    // --- panning, zooming, turning -----------------------------------------------------------

    @Test
    fun `panning right slides the view across the ground the way the picture faces`() {
        val fixture = Fixture()
        fixture.rig.moveHalfLife = 0f
        fixture.frame()

        fixture.rig.panBy(right = 5f, forward = 0f)
        fixture.frame()

        // At a yaw of 45 the camera faces (cos45, sin45); its right is a quarter turn clockwise.
        assertNear(5f * sin(radians(45f)), fixture.rig.camera.targetX, "panning right did not slide the view right")
        assertNear(-5f * cos(radians(45f)), fixture.rig.camera.targetY, "panning right did not slide the view right")
        assertNear(0f, fixture.rig.camera.targetZ, "panning left the ground")
    }

    @Test
    fun `panning forward slides the view the way the camera looks`() {
        val fixture = Fixture()
        fixture.rig.moveHalfLife = 0f
        fixture.frame()

        fixture.rig.panBy(right = 0f, forward = 5f)
        fixture.frame()

        assertNear(5f * cos(radians(45f)), fixture.rig.camera.targetX, "panning forward did not slide the view forward")
        assertNear(5f * sin(radians(45f)), fixture.rig.camera.targetY, "panning forward did not slide the view forward")
    }

    @Test
    fun `zooming in shows less of the world and moves the view nowhere`() {
        val fixture = Fixture()
        fixture.rig.moveHalfLife = 0f
        fixture.rig.viewHeight = 20f
        fixture.frame()
        val before = fixture.rig.camera

        fixture.rig.zoomBy(0.5f)
        fixture.frame()

        assertNear(10f, fixture.rig.camera.viewHeight, "zooming to half did not halve how much of the world fits")
        assertNear(0f, before.targetX, "zooming slid the view")
        assertNear(0f, before.targetY, "zooming slid the view")
    }

    @Test
    fun `the zoom stops at the limits it was given`() {
        val fixture = Fixture()
        fixture.rig.minViewHeight = 6f
        fixture.rig.maxViewHeight = 40f

        fixture.rig.zoomBy(0.001f)
        assertEquals(6f, fixture.rig.viewHeight, "zooming in past the limit was allowed")

        fixture.rig.zoomBy(1000f)
        assertEquals(40f, fixture.rig.viewHeight, "zooming out past the limit was allowed")
    }

    @Test
    fun `a quarter turn left turns the view ninety degrees anticlockwise`() {
        val fixture = Fixture()

        fixture.rig.turnLeft()

        assertNear(135f, fixture.rig.yawDegrees, "a quarter turn left is 90 degrees anticlockwise from 45")
    }

    @Test
    fun `four quarter turns right come back to where the view started`() {
        val fixture = Fixture()
        fixture.rig.moveHalfLife = 0f
        fixture.frame()
        val startEyeX = fixture.rig.camera.eyeX
        val startEyeY = fixture.rig.camera.eyeY

        repeat(4) { fixture.rig.turnRight() }
        fixture.frame()

        assertNear(45f, fixture.rig.yawDegrees, "four quarter turns did not come back round")
        assertNear(startEyeX, fixture.rig.camera.eyeX, "four quarter turns left the eye somewhere else")
        assertNear(startEyeY, fixture.rig.camera.eyeY, "four quarter turns left the eye somewhere else")
    }

    @Test
    fun `snapping puts a freely turned view back on the nearest quarter turn`() {
        val fixture = Fixture()

        fixture.rig.yawDegrees = 160f
        fixture.rig.snapYaw()

        assertNear(135f, fixture.rig.yawDegrees, "160 degrees should snap back to 135, a quarter turn from the preset 45")
    }

    @Test
    fun `snapping is measured from the rig's home yaw, not from north`() {
        val fixture = Fixture()
        fixture.rig.homeYawDegrees = 0f

        fixture.rig.yawDegrees = 160f
        fixture.rig.snapYaw()

        assertNear(180f, fixture.rig.yawDegrees, "with a home yaw of 0 the quarter turns are 0, 90, 180 and 270")
    }

    // --- easing -------------------------------------------------------------------------------

    @Test
    fun `the first frame places the camera where it was set, without easing towards it`() {
        val fixture = Fixture()
        fixture.rig.moveHalfLife = 0.5f
        fixture.rig.panBy(right = 0f, forward = 12f)
        fixture.rig.viewHeight = 33f

        fixture.frame()

        assertNear(12f * cos(radians(45f)), fixture.rig.camera.targetX, "the first frame eased in from somewhere else")
        assertNear(33f, fixture.rig.camera.viewHeight, "the first frame eased the zoom in from somewhere else")
    }

    @Test
    fun `a turn eases round and arrives`() {
        val fixture = Fixture()
        fixture.rig.moveHalfLife = 0.1f
        fixture.frame()

        fixture.rig.turnLeft()
        fixture.frame()
        val afterOneFrame = yawOf(fixture)
        // A tenth of a second at a sixtieth of a second a frame: about a ninth of the way.
        assertTrue(
            afterOneFrame > 45f && afterOneFrame < 90f,
            "one frame of a 0.1s ease should have turned part of the way from 45 to 135, it is at $afterOneFrame",
        )

        repeat(120) { fixture.frame() }
        assertNear(135f, yawOf(fixture), "the eased turn never arrived")
    }

    @Test
    fun `a turn eases the short way round, never the long way`() {
        val fixture = Fixture()
        fixture.rig.moveHalfLife = 0.1f
        fixture.rig.yawDegrees = 170f
        fixture.frame()

        // 170 to -170 is 20 degrees the short way and 340 the long way.
        fixture.rig.yawDegrees = -170f
        fixture.frame()

        val shown = yawOf(fixture)
        assertTrue(
            shown > 170f || shown < -170f,
            "the ease went the long way round: it is at $shown, which is not between 170 and -170",
        )
    }

    @Test
    fun `a Scene view's run does not move the camera on, so an open editor does not ease twice`() {
        val fixture = Fixture()
        fixture.rig.moveHalfLife = 0.1f
        fixture.frame()
        fixture.rig.turnLeft()
        fixture.frame()
        val afterTheGameFrame = yawOf(fixture)

        fixture.resources.viewing.current = sceneView()
        try {
            fixture.frame()
        } finally {
            fixture.resources.viewing.current = null
        }

        assertEquals(afterTheGameFrame, yawOf(fixture), "a Scene view's run eased the camera a second time")
    }

    // --- what the numbers must be ---------------------------------------------------------------

    @Test
    fun `a pitch must leave the camera above the ground and short of straight down`() {
        val fixture = Fixture()
        assertFailsWith<IllegalArgumentException> { fixture.rig.pitchDegrees = 0f }
        assertFailsWith<IllegalArgumentException> { fixture.rig.pitchDegrees = 90f }
        assertFailsWith<IllegalArgumentException> { fixture.rig.pitchDegrees = Float.NaN }
    }

    @Test
    fun `a distance and a view height must be positive numbers of world units`() {
        val fixture = Fixture()
        assertFailsWith<IllegalArgumentException> { fixture.rig.eyeDistance = 0f }
        assertFailsWith<IllegalArgumentException> { fixture.rig.viewHeight = -1f }
        assertFailsWith<IllegalArgumentException> { fixture.rig.moveHalfLife = -0.1f }
    }

    @Test
    fun `a yaw is folded into one turn, so turning round and round keeps its precision`() {
        val fixture = Fixture()
        fixture.rig.yawDegrees = 45f + 360f * 100f
        assertNear(45f, fixture.rig.yawDegrees, "a yaw of a hundred turns was not folded back into one")
    }

    // --- not simulation --------------------------------------------------------------------------

    @Test
    fun `a world ticked with the rig present hashes the same as one ticked without it`() {
        val withRig = Fixture()
        val without = Fixture()
        withRig.spawnMoving()
        without.spawnMoving()

        repeat(TICKS) {
            withRig.rig.panBy(right = 0.3f, forward = 0.2f)
            withRig.rig.zoomBy(1.003f)
            if (it == 40) withRig.rig.turnRight()
            withRig.sim.step()
            withRig.frame()
            without.sim.step()
        }

        assertTrue(
            abs(withRig.rig.camera.targetX) > 1f,
            "the rig never moved, so this asserts nothing",
        )
        assertEquals(without.hash(), withRig.hash())
    }

    @Test
    fun `the rig is handed no world, so there is nothing it could write into`() {
        // The test above can only go red if somebody gives the rig a world to write to, which is the
        // point of it. This is that same claim checked at the other end: over the compiled class,
        // rather than over a reading of the source.
        //
        // What it asserts: `IsometricRig` holds no field of a Fleks, context or entity-index type, so
        // there is nothing it could write a component into between frames. It says nothing about what
        // is reachable through `RenderResources`, which the rig only reads, and nothing about the
        // `onBind` the interface gives every RenderSystem for free - the fixture above calls that, so
        // the hash tests cover it.
        val fields = IsometricRig::class.java.declaredFields.map { it.type.name }
        assertTrue(
            fields.none { it.startsWith("com.github.quillraven.fleks") || it.endsWith("GameContext") || it.endsWith("NetIdIndex") },
            "IsometricRig holds a world, a context or an entity index, so it can write to the simulation: $fields",
        )
    }

    @Test
    fun `the hash this rests on sees a one-ulp nudge to a transform`() {
        // The control for the test above: an equality over a hash that cannot tell two worlds apart
        // is a test that cannot fail. A rig that wrote the smallest possible change into a transform
        // must come out different.
        val plain = Fixture()
        val nudged = Fixture()
        plain.spawnMoving()
        val entity = nudged.spawnMoving()
        repeat(TICKS) {
            plain.sim.step()
            nudged.sim.step()
        }
        with(nudged.world) { entity[Transform3D].z = Math.nextUp(entity[Transform3D].z) }

        assertNotEquals(plain.hash(), nudged.hash())
    }

    // --- fixture -------------------------------------------------------------------------

    private class Fixture(frameSeconds: Float = 1f / 60f) {

        // A capturable random service: the snapshot the hash is taken over carries its streams.
        val ctx: GameContext = testGameContext(seed = SEED) { rng = DefaultRngService(SEED) }

        val world: World = configureWorld {
            injectables { gameContext(ctx) }
            systems { add(WalkSystem()) }
        }

        val sim = WorldSimulation(ctx, world)

        val netIds = NetIdIndex()

        val target: OffscreenTarget = OffscreenTarget(WIDTH, HEIGHT)

        val resources = RenderResources(SpriteBatch2D(SpriteTexture.whitePixel("iso-test-white")), target)

        // Bound as `RenderPipeline` binds every RenderSystem, so the hash tests below cover whatever
        // the rig does with the world it is handed there - which today is nothing.
        val rig = IsometricRig(resources, FixedFrameTime(frameSeconds)).also { it.onBind(world, ctx) }

        private val snapshots = SnapshotService(transformOnly(), world, ctx, netIds)

        /**
         * A moving entity **with a `NetId`**, because a `SnapshotService` captures the entities its
         * index knows and no others: an entity without one makes every hash below the hash of an empty
         * snapshot, which two worlds always agree about.
         */
        fun spawnMoving(): Entity = world.entity {
            it += Transform3D()
            it += Walker()
        }.also { netIds.allocate(it) }

        fun frame(alpha: Float = 1f) = rig.render(target, alpha)

        fun hash(): Long = WorldHasher.hash(snapshots.capture())
    }

    private class FixedFrameTime(override val frameSeconds: Float) : FrameTime

    /** Marks a transform [WalkSystem] moves. */
    private class Walker : com.github.quillraven.fleks.Component<Walker> {
        override fun type() = Walker

        companion object : com.github.quillraven.fleks.ComponentType<Walker>()
    }

    /** Walks every [Walker] along a slow curve, so there is a simulation running beside the rig. */
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

    /** How far the eye is from what it looks at, in world units. */
    private fun eyeDistance(fixture: Fixture): Float {
        val camera = fixture.rig.camera
        val dx = camera.eyeX - camera.targetX
        val dy = camera.eyeY - camera.targetY
        val dz = camera.eyeZ - camera.targetZ
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Where the eye stands round the focus, in degrees: the yaw the picture actually shows. */
    private fun yawOf(fixture: Fixture): Float {
        val camera = fixture.rig.camera
        return degrees(atan2(camera.targetY - camera.eyeY, camera.targetX - camera.eyeX))
    }

    private fun assertNear(expected: Float, actual: Float, what: String) {
        assertTrue(abs(actual - expected) < TOLERANCE, "$what: expected $expected, was $actual")
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 360
        const val TICKS = 120
        const val SEED = 7L
        const val TOLERANCE = 1e-2f

        fun radians(degrees: Float): Float = (degrees * PI / 180.0).toFloat()

        fun degrees(radians: Float): Float = (radians * 180.0 / PI).toFloat()

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
                batch = SpriteBatch2D(SpriteTexture.whitePixel("iso-test-view-white"), home = record),
                frame = null,
                captures = null,
                kool = null,
            )
        }
    }
}
