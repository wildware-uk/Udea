package dev.wildware.hollow

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Input moves the character, and a rock stops it (issue #250's first acceptance criterion).
 *
 * Headless: no window, no GL context, no network. What is driven is the real game - the real
 * `IntentSource` seam, the real Box2D solver, the real four systems - so what these assert about is
 * the code a player runs rather than a model of it.
 */
class PlayerMovementTest {

    private val scene = PlayerScene()

    @AfterTest
    fun close() {
        scene.close()
    }

    @Test
    fun `holding a direction walks the character that way, and letting go stops it`() {
        val me = scene.spawn()

        scene.moveX = 1f
        scene.run(TICKS_PER_SECOND)
        // Read out as numbers, not kept as a component. `transformOf` hands back the live
        // `Transform3D`, so a reference held across the next `run` would be re-read at assert time
        // and every comparison against it would be the value against itself - a test that cannot
        // fail, which is exactly what the first pass of this file was.
        val walkedX = scene.transformOf(me).x
        val walkedY = scene.transformOf(me).y

        assertEquals(
            HollowMovement.WALK_SPEED,
            walkedX,
            WALK_TOLERANCE,
            "a second of walking east should cover about ${HollowMovement.WALK_SPEED} units, and covered $walkedX",
        )
        assertTrue(abs(walkedY) < DRIFT, "it drifted north: $walkedY")

        // Let go. The ground plane has no gravity and no friction, so a character that was not
        // asked to stop every tick would coast for ever.
        scene.moveX = 0f
        scene.run(TICKS_PER_SECOND)
        assertEquals(walkedX, scene.transformOf(me).x, DRIFT, "it kept walking after the key went up")
    }

    @Test
    fun `holding the run control covers more ground than walking does`() {
        val walker = scene.spawn(y = -4f)
        val runner = scene.spawn(y = 4f)

        scene.moveX = 1f
        scene.running = false
        scene.run(TICKS_PER_SECOND)
        val walkedAlone = scene.transformOf(walker).x

        // Both characters read the same axis - there is one pair of hands in a standalone game - so
        // the second run has to be a separate scene's worth of ticks. Instead, run the same scene
        // again with the control held, and compare how far each got in its own second.
        val before = scene.transformOf(runner).x
        scene.running = true
        scene.run(TICKS_PER_SECOND)
        val ranInASecond = scene.transformOf(runner).x - before

        assertEquals(
            HollowMovement.WALK_SPEED,
            walkedAlone,
            WALK_TOLERANCE,
            "the walking second covered $walkedAlone",
        )
        assertEquals(
            HollowMovement.RUN_SPEED,
            ranInASecond,
            RUN_TOLERANCE,
            "the running second covered $ranInASecond",
        )
        assertTrue(ranInASecond > walkedAlone * RUN_IS_FASTER_BY, "running was not meaningfully faster")
    }

    @Test
    fun `a diagonal is no faster than a straight line`() {
        val me = scene.spawn()

        // Both keys down. `DeviceIntent` clamps a keyboard diagonal, and so does `MoveAxis`, which
        // is the rule both the server and a predictor run - an unclamped diagonal is forty per cent
        // of free speed.
        scene.moveX = 1f
        scene.moveY = 1f
        scene.run(TICKS_PER_SECOND)

        val at = scene.transformOf(me)
        val distance = sqrt(at.x * at.x + at.y * at.y)
        assertEquals(HollowMovement.WALK_SPEED, distance, WALK_TOLERANCE, "the diagonal covered $distance")
    }

    @Test
    fun `the character stops at a rock and does not walk through it`() {
        val me = scene.spawn()
        // A big stone six metres east, at the scale the clearing plants them. Its collision circle
        // is `Prop.blockRadius * scale`, which `ClearingBodySystem` builds on the first tick.
        val rock = scene.prop(Prop.STONE_LARGE_A, x = ROCK_X, y = 0f, scale = ROCK_SCALE)
        val rockRadius = Prop.STONE_LARGE_A.blockRadius * ROCK_SCALE

        scene.moveX = 1f
        // Long enough to have walked well past the rock on open ground: five seconds of walking is
        // twelve metres, and the rock's near face is under five away.
        scene.run(5 * TICKS_PER_SECOND)

        val at = scene.transformOf(me)
        val touching = ROCK_X - rockRadius - Player.RADIUS
        assertTrue(at.x > touching - REACHED, "it never reached the rock: x = ${at.x}, the face is at $touching")
        assertTrue(at.x <= touching + CONTACT_SLOP, "it walked into the rock: x = ${at.x}, the face is at $touching")
        // The rock did not move either: a static body is not pushed.
        assertEquals(ROCK_X, scene.transformOf(rock).x, "the rock moved")
    }

    @Test
    fun `a character pushing into a rock can still walk away from it`() {
        val me = scene.spawn()
        scene.prop(Prop.STONE_LARGE_A, x = ROCK_X, y = 0f, scale = ROCK_SCALE)

        scene.moveX = 1f
        scene.run(3 * TICKS_PER_SECOND)
        val stopped = scene.transformOf(me).x

        scene.moveX = -1f
        scene.run(TICKS_PER_SECOND)

        assertTrue(
            scene.transformOf(me).x < stopped - HollowMovement.WALK_SPEED + WALK_TOLERANCE,
            "it did not get away from the rock: ${scene.transformOf(me).x} from $stopped",
        )
    }

    @Test
    fun `the character faces the way it is walking`() {
        val me = scene.spawn()

        scene.moveX = 1f
        scene.moveY = 0f
        scene.run(TICKS)
        val east = scene.transformOf(me).rotationZ

        scene.moveX = 0f
        scene.moveY = 1f
        scene.run(TICKS)
        val north = scene.transformOf(me).rotationZ

        // East is heading zero and north a quarter turn from it, both with the model's own facing
        // added - which is what points the model's front along the heading rather than across it.
        assertEquals(Player.MODEL_FACING, east, HEADING_TOLERANCE, "walking east faced $east")
        assertEquals(Player.MODEL_FACING + QUARTER_TURN, north, HEADING_TOLERANCE, "walking north faced $north")

        // Standing still keeps the last facing rather than snapping back to east.
        scene.moveY = 0f
        scene.run(TICKS)
        assertEquals(north, scene.transformOf(me).rotationZ, "it turned while standing still")
    }

    private companion object {
        const val TICKS_PER_SECOND = 60
        const val TICKS = 30

        /** Box2D's soft step is a fraction of a tick short over the first tick of a new velocity. */
        const val WALK_TOLERANCE = 0.06f
        const val RUN_TOLERANCE = 0.12f

        /** How far off a straight line counts as drift rather than float noise. */
        const val DRIFT = 1e-3f

        const val RUN_IS_FASTER_BY = 1.5f

        const val ROCK_X = 6f
        const val ROCK_SCALE = 2f

        /** How close to the rock's face counts as having reached it. */
        const val REACHED = 0.05f

        /** Box2D lets a contact settle a few millimetres deep. */
        const val CONTACT_SLOP = 0.02f

        const val QUARTER_TURN = 1.5707964f
        const val HEADING_TOLERANCE = 1e-4f
    }
}
