package dev.wildware.hollow

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.generated.Fox as FoxModel
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #251's first acceptance criterion, headless: a fox in range chases, and one out of range
 * wanders - with the range shown from **both** sides.
 *
 * "In range" is a threshold, and a threshold proved from one side is a threshold anywhere. So every
 * pair here is two foxes set up identically but for the one number the rule reads: one a tenth of a
 * metre inside [FoxBrain.CHASE_RANGE], one a tenth outside; one at [FoxBrain.FLEE_AT] health, one a
 * point above it. Each fox is given a wander goal on its own spot and no decision until long after
 * the test ends, so the only thing that can move it is the rule under test.
 *
 * The whole game, headless: the real definition, the real Box2D solver and the real systems, over an
 * empty level with no waves, so the foxes a test places are the only ones in the world.
 */
class FoxBehaviourTest {

    private val scene = PlayerScene(waves = null)

    @AfterTest
    fun close() {
        scene.close()
    }

    @Test
    fun `a fox a tenth of a metre inside chase range runs at the player`() {
        val player = scene.spawn()
        val fox = still(FoxBrain.CHASE_RANGE - MARGIN)

        scene.run(OBSERVE)

        val state = scene.foxOf(fox)
        assertEquals(FoxMode.Chase, state.mode, "a fox ${FoxBrain.CHASE_RANGE - MARGIN} away did not chase: $state")
        assertEquals(player, state.target, "it chased somebody else: $state")
        val closed = FoxBrain.CHASE_RANGE - MARGIN - distance(fox, player)
        assertTrue(closed > CLOSED_AT_LEAST, "it chased on paper but closed only $closed on the player")
        assertEquals(FoxModel.Clips.Run.index, scene.animatorOf(fox).current.clip, "a chasing fox is not running")
    }

    @Test
    fun `a fox a tenth of a metre outside chase range wanders and does not close in`() {
        val player = scene.spawn()
        val fox = still(FoxBrain.CHASE_RANGE + MARGIN)

        // Every tick, not the last one: a fox that chased for a tick and gave up would pass a check
        // taken at the end.
        repeat(OBSERVE) {
            scene.run(1)
            val state = scene.foxOf(fox)
            assertEquals(FoxMode.Wander, state.mode, "a fox ${FoxBrain.CHASE_RANGE + MARGIN} away left wander at ${scene.tick}: $state")
            assertEquals(NetId.NONE, state.target, "a wandering fox has a target: $state")
        }
        assertEquals(FoxBrain.CHASE_RANGE + MARGIN, distance(fox, player), DRIFT, "the fox moved towards the player")
        assertEquals(FoxModel.Clips.Survey.index, scene.animatorOf(fox).current.clip, "a fox standing still is not surveying")
    }

    @Test
    fun `a fox with nobody near chooses somewhere and walks there`() {
        scene.spawn(x = FAR_AWAY)
        // `decideAt` is now: the fox chooses on its first tick, from the `AI` stream.
        val fox = Fox.spawn(scene.world, scene.netIds, x = 0f, y = 0f, decideAt = scene.tick)

        scene.run(WANDER_TICKS)

        val state = scene.foxOf(fox)
        assertEquals(FoxMode.Wander, state.mode, "a fox with nobody near is not wandering: $state")
        val at = scene.transformOf(fox)
        val walked = sqrt(at.x * at.x + at.y * at.y)
        assertTrue(walked > WANDERED_AT_LEAST, "a wandering fox covered only $walked in $WANDER_TICKS ticks")
        assertTrue(
            walked < FoxBrain.WALK_SPEED * WANDER_TICKS / TICK_RATE + DRIFT,
            "a wandering fox covered $walked, more than walking could: it is running",
        )
    }

    @Test
    fun `a walking fox plays the walk`() {
        scene.spawn(x = FAR_AWAY)
        // A goal three metres east and no decision to replace it: the fox walks straight there.
        val fox = Fox.spawn(scene.world, scene.netIds, x = 0f, y = 0f, goalX = 3f, goalY = 0f, decideAt = NEVER)

        scene.run(WALK_A_BIT)

        assertEquals(FoxMode.Wander, scene.foxOf(fox).mode)
        assertEquals(FoxModel.Clips.Walk.index, scene.animatorOf(fox).current.clip, "a walking fox is not walking")
        assertTrue(scene.transformOf(fox).x > WALKED_AT_LEAST, "the fox did not walk towards its goal: ${scene.transformOf(fox)}")
    }

    @Test
    fun `a fox at the flee threshold runs away from the player`() {
        val player = scene.spawn()
        val fox = still(NEAR)
        scene.foxOf(fox).health = FoxBrain.FLEE_AT

        scene.run(OBSERVE)

        val state = scene.foxOf(fox)
        assertEquals(FoxMode.Flee, state.mode, "a fox on ${FoxBrain.FLEE_AT} health did not flee: $state")
        assertEquals(player, state.target, "it fled from somebody else: $state")
        val gained = distance(fox, player) - NEAR
        assertTrue(gained > CLOSED_AT_LEAST, "it fled on paper but gained only $gained on the player")
        assertEquals(FoxModel.Clips.Run.index, scene.animatorOf(fox).current.clip, "a fleeing fox is not running")
    }

    @Test
    fun `a fox one point above the flee threshold chases instead`() {
        val player = scene.spawn()
        val fox = still(NEAR)
        scene.foxOf(fox).health = FoxBrain.FLEE_AT + 1

        scene.run(OBSERVE)

        val state = scene.foxOf(fox)
        assertEquals(FoxMode.Chase, state.mode, "a fox on ${FoxBrain.FLEE_AT + 1} health did not chase: $state")
        assertTrue(distance(fox, player) < NEAR, "it did not close on the player")
    }

    @Test
    fun `a chasing fox faces the player it is running at`() {
        scene.spawn()
        // Due north of the player, so running at it is running south: a heading of minus a quarter
        // turn, and a model turned by that plus its own facing.
        val fox = Fox.spawn(scene.world, scene.netIds, x = 0f, y = NEAR, decideAt = NEVER)

        scene.run(FACE_TICKS)

        val south = -HALF_PI
        assertEquals(FoxMode.Chase, scene.foxOf(fox).mode)
        assertEquals(south, scene.foxOf(fox).heading, ANGLE, "the fox is not heading south at the player")
        assertEquals(south + Fox.MODEL_FACING, scene.transformOf(fox).rotationZ, ANGLE, "the model is not turned to the heading")
    }

    /** A fox [x] metres east of the origin, told to stand on its own spot until long after the test. */
    private fun still(x: Float): NetId =
        Fox.spawn(scene.world, scene.netIds, x = x, y = 0f, goalX = x, goalY = 0f, decideAt = NEVER)

    private fun distance(a: NetId, b: NetId): Float {
        val first = scene.transformOf(a)
        val second = scene.transformOf(b)
        val dx = first.x - second.x
        val dy = first.y - second.y
        return sqrt(dx * dx + dy * dy)
    }

    private companion object {
        /** How far either side of a threshold a fox is placed. */
        const val MARGIN = 0.1f

        /** Half a second: long enough for a fox that is going to act to have acted. */
        const val OBSERVE = 30

        /**
         * How far a chasing fox must have closed in [OBSERVE] ticks.
         *
         * At [FoxBrain.RUN_SPEED] half a second is about two metres; a quarter of that is a floor
         * with room in it, and far more than any settling of the solver could move a still fox.
         */
        const val CLOSED_AT_LEAST = FoxBrain.RUN_SPEED * OBSERVE / 60f / 4f

        /** Well inside chase range and well outside biting range. */
        const val NEAR = 3f

        /** A player a fox cannot see, so a wandering fox has nobody to chase. */
        const val FAR_AWAY = 40f

        /** Four seconds: at least one choice of goal, and time to walk towards it. */
        const val WANDER_TICKS = 240

        const val WANDERED_AT_LEAST = 0.5f

        /** A third of a second of walking. */
        const val WALK_A_BIT = 20

        const val WALKED_AT_LEAST = 0.2f

        /** Ticks for a fox to turn to the player and settle on the line. */
        const val FACE_TICKS = 10

        const val TICK_RATE = 60

        /** A decision that never comes within any test. */
        val NEVER = Tick(1_000_000L)

        /** What the solver's settling can move a body that nothing drives. */
        const val DRIFT = 0.01f

        const val HALF_PI = 1.5707964f

        const val ANGLE = 1e-3f
    }
}
