package dev.wildware.udea.render.camera

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.render.FrameTime
import dev.wildware.udea.render.interp.Pose
import dev.wildware.udea.render.interp.PoseSource
import dev.wildware.udea.render.support.HeadlessGl
import dev.wildware.udea.render.support.testTargets
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The camera follows a **game's own** spatial component, not only a `PhysicsBody`.
 *
 * ## The bug this closes, in one sentence
 *
 * `CameraRig` took an `Interpolator`, `Interpolator` reads `PhysicsBody`, and a `moba` unit has
 * neither - so `render.follow_entity` resolved the id, found no pose, moved nothing, and answered
 * `{"following": <id>}` anyway. Both `MobaScene` and `MobaAgent` had that written into their KDoc
 * as a known lie, which is the state a rule against silent acceptance is supposed to prevent.
 *
 * Naming the capability ([PoseSource]) instead of the implementation is the whole fix, and this
 * is the test that says the capability is genuinely what the rig uses: the pose here comes from a
 * component the renderer has never heard of, and the camera still tracks it.
 */
class CameraFollowsAnyPoseSourceTest {

    private val target = testTargets(width = 640, height = 360).offscreen

    private var gl: HeadlessGl? = null

    @BeforeEach
    fun installGl() {
        gl = HeadlessGl.installed(width = 640, height = 360)
    }

    @AfterEach
    fun removeGl() {
        gl?.uninstall()
        gl = null
    }

    @Test
    fun `the camera tracks an entity whose position is a game component`() {
        val fixture = Fixture()
        val spot = Spot(x = 40f, y = 12f)
        val id = fixture.netIds.allocate(fixture.world.entity { it += spot })

        fixture.rig.followHalfLife = 0f
        fixture.rig.requestFollow(id)
        fixture.rig.advance(target, alpha = 1f)

        assertNear(40f, fixture.rig.camera.position.x)
        assertNear(12f, fixture.rig.camera.position.y)

        spot.x = -25f
        fixture.rig.advance(target, alpha = 1f)

        assertNear(-25f, fixture.rig.camera.position.x)
    }

    /**
     * The refusal survives, and now means something.
     *
     * With one universal answer ("no pose"), `entity_not_followable` was true of everything and
     * told an agent nothing. With a game-supplied source it separates the entity that can be
     * followed from the one that cannot - an effect or a marker with no position - which is the
     * distinction the error kind was invented to carry.
     */
    @Test
    fun `an entity the game has no pose for is still refused`() {
        val fixture = Fixture()
        val followable = fixture.netIds.allocate(fixture.world.entity { it += Spot(1f, 2f) })
        val poseless = fixture.netIds.allocate(fixture.world.entity { })

        assertEquals(CameraOutcome.APPLIED, fixture.rig.followability(followable))
        assertEquals(CameraOutcome.UNFOLLOWABLE, fixture.rig.followability(poseless))
    }

    private fun assertNear(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) < 1e-3f, "expected about $expected, was $actual")
    }

    private class Fixture {

        val netIds = NetIdIndex()

        val ctx = testGameContext(seed = 11L)

        val world: World = configureWorld { injectables { gameContext(ctx) } }

        val rig = CameraRig(
            netIds = netIds,
            poses = SpotPoses,
            frameTime = SixtyHertz,
        ).also { it.onBind(world, ctx) }
    }

    /** A steady 60Hz frame, so smoothing is not what a failure would be blamed on. */
    private object SixtyHertz : FrameTime {
        override val frameSeconds: Float get() = 1f / 60f
    }

    /** A position component `udea-render` knows nothing about, which is the point. */
    private class Spot(var x: Float, var y: Float) : Component<Spot> {
        override fun type(): ComponentType<Spot> = Spot
        companion object : ComponentType<Spot>()
    }

    /** The game's reader for its own component. Eight lines, and it makes following real. */
    private object SpotPoses : PoseSource {
        override fun poseOf(world: World, entity: com.github.quillraven.fleks.Entity, alpha: Float, into: Pose): Boolean {
            with(world) {
                val spot = entity.getOrNull(Spot) ?: return false
                into.x = spot.x
                into.y = spot.y
                into.angle = 0f
                return true
            }
        }
    }
}
