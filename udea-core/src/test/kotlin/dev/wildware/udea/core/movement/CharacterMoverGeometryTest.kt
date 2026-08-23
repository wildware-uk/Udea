package dev.wildware.udea.core.movement

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The three shapes that break a character controller: an inside corner, a slope, and the seam
 * between two adjacent colliders.
 *
 * Each of them fails in one of two opposite ways, and a controller usually trades one for the
 * other. **Tunnelling** is a mover that ends up on the wrong side of a wall - the failure a
 * single-shot depenetration pass produces at speed. **Sticking** is a mover that catches on a
 * join between two colliders that are geometrically flush - the failure an over-eager
 * depenetration produces, because the far endpoint of the second segment reports a contact the
 * first has already resolved. Both are asserted here on the *final position*, which is the only
 * thing a player or a server sees.
 */
class CharacterMoverGeometryTest {

    private val dt = MoverScenario.DT

    /** Position tolerance: a few times [CharacterMover.SKIN], and far below anything visible. */
    private val tolerance = 1e-2f

    @Test
    fun `a mover driven into an inside corner settles against both walls and passes through neither`() {
        val geometry = StaticCollision.Builder(cellSize = 2f)
            .segment(-10f, 0f, 10f, 0f)
            .segment(-10f, 0f, -10f, 10f)
            .build()
        val config = MoverScenario.config()
        val mover = CharacterMover()
        val state = MoverState(x = -8f, y = 2f)
        val intent = MoveIntent(move = -1f)

        var lowest = state.y
        var leftmost = state.x
        repeat(300) {
            mover.move(state, intent, config, geometry, dt)
            if (state.y < lowest) lowest = state.y
            if (state.x < leftmost) leftmost = state.x
        }

        val restingY = config.halfHeight + config.radius
        val restingX = -10f + config.radius
        assertTrue(
            abs(state.y - restingY) < tolerance,
            "expected to rest at y=$restingY, ended at ${state.y}",
        )
        assertTrue(
            abs(state.x - restingX) < tolerance,
            "expected to rest at x=$restingX, ended at ${state.x}",
        )
        assertTrue(state.grounded, "a mover resting on a flat floor is grounded; state was $state")
        assertTrue(
            leftmost > -10f - tolerance && lowest > -tolerance,
            "the mover left the corner: leftmost=$leftmost lowest=$lowest",
        )
    }

    @Test
    fun `a mover walks up a slope, gains height, and never sinks into it`() {
        // 26.57 degrees: within the default 45-degree walkable limit, so this is a slope the mover
        // is supposed to climb rather than one it is supposed to be stopped by.
        val geometry = StaticCollision.Builder(cellSize = 2f)
            .segment(-6f, 0f, 0f, 0f)
            .segment(0f, 0f, 12f, 6f)
            // A landing at the top. Without it the mover walks off the end of the slope inside
            // the run and the test measures a fall rather than a climb.
            .segment(12f, 6f, 24f, 6f)
            .build()
        val config = MoverScenario.config()
        val mover = CharacterMover()
        val state = MoverState(x = -4f, y = 1f)
        val intent = MoveIntent(move = 1f)

        // The slope's upward-left unit normal, written out so the assertion does not depend on
        // anything the mover computed.
        val length = kotlin.math.sqrt(12f * 12f + 6f * 6f)
        val normalX = -6f / length
        val normalY = 12f / length

        var worstPenetration = Float.MAX_VALUE
        var groundedTicks = 0
        val ticks = 250
        repeat(ticks) {
            mover.move(state, intent, config, geometry, dt)
            if (state.grounded) groundedTicks++
            if (state.x > 0.5f && state.x < 11.5f) {
                // Distance from the capsule's lowest core point to the slope's line.
                val distance = normalX * state.x + normalY * (state.y - config.halfHeight)
                if (distance < worstPenetration) worstPenetration = distance
            }
        }

        assertTrue(state.x > 6f, "the mover did not climb the slope; it ended at x=${state.x}")
        assertTrue(state.y > 2f, "the mover gained no height; it ended at y=${state.y}")
        assertTrue(
            groundedTicks > ticks * 3 / 4,
            "the mover was grounded on only $groundedTicks of $ticks ticks: it is bouncing up " +
                "the slope rather than walking it",
        )
        assertTrue(
            worstPenetration > config.radius - tolerance,
            "the capsule sank into the slope: closest core distance was $worstPenetration, " +
                "radius is ${config.radius}",
        )
    }

    @Test
    fun `a mover crossing the seam between two adjacent colliders neither dips nor sticks`() {
        // Two flush, collinear floors meeting at x = 0. The shared endpoint is reported by *both*
        // segments, so the mover resolves two contacts at the same point on the tick it crosses -
        // which is exactly where a controller catches.
        val geometry = StaticCollision.Builder(cellSize = 2f)
            .segment(-10f, 0f, 0f, 0f)
            .segment(0f, 0f, 10f, 0f)
            .build()
        val config = MoverScenario.config()
        val mover = CharacterMover()
        val state = MoverState(x = -5f, y = config.halfHeight + config.radius)
        val intent = MoveIntent(move = 1f)

        val restingY = config.halfHeight + config.radius
        var lowest = state.y
        var highest = state.y
        var slowestNearSeam = Float.MAX_VALUE
        // 100 ticks at 6 units per second is 10 units, which keeps the mover inside the floor:
        // a longer run walks it off the right end and measures a fall instead of a seam.
        repeat(100) {
            mover.move(state, intent, config, geometry, dt)
            if (state.y < lowest) lowest = state.y
            if (state.y > highest) highest = state.y
            if (state.x > -1f && state.x < 1f && state.velocityX < slowestNearSeam) {
                slowestNearSeam = state.velocityX
            }
        }

        assertTrue(state.x > 2f, "the mover stuck at the seam; it ended at x=${state.x}")
        assertTrue(
            lowest > restingY - tolerance && highest < restingY + tolerance,
            "the mover's height moved crossing a flat seam: $lowest..$highest around $restingY",
        )
        assertTrue(
            slowestNearSeam > config.maxSpeed * 0.9f,
            "the mover slowed to $slowestNearSeam crossing the seam, from ${config.maxSpeed}",
        )
    }

    @Test
    fun `a mover asked to move further than its cap allows is slowed, never passed through a wall`() {
        // The documented refusal to tunnel: `maxTravelPerMove` is the cap, and past it the mover
        // moves slower rather than through. A `maxSpeed` of 1000 asks for 16.6 units in a tick,
        // and the cap is 1.6.
        val geometry = StaticCollision.Builder(cellSize = 2f)
            .segment(-20f, 0f, 20f, 0f)
            .segment(4f, 0f, 4f, 12f)
            .build()
        val config = MoverScenario.config().apply {
            maxSpeed = 1000f
            acceleration = 100_000f
        }
        val mover = CharacterMover()
        val state = MoverState(x = -8f, y = 0.9f)
        val intent = MoveIntent(move = 1f)

        val cap = mover.maxTravelPerMove(config)
        var furthest = state.x
        repeat(200) {
            val before = state.x
            mover.move(state, intent, config, geometry, dt)
            assertTrue(
                state.x - before <= cap + tolerance,
                "one move carried the mover ${state.x - before}, past the $cap cap",
            )
            if (state.x > furthest) furthest = state.x
        }

        assertTrue(
            furthest < 4f + tolerance,
            "the mover tunnelled through the wall at x=4; it reached $furthest",
        )
        assertTrue(state.x > 3f, "the mover never reached the wall, so nothing was tested")
    }

    @Test
    fun `a mover falling fast lands on the floor rather than through it`() {
        val geometry = StaticCollision.Builder(cellSize = 2f).segment(-20f, 0f, 20f, 0f).build()
        val config = MoverScenario.config()
        val mover = CharacterMover()
        val state = MoverState(x = 0f, y = 8f, velocityY = -400f)
        val intent = MoveIntent()

        var lowest = state.y
        repeat(600) {
            mover.move(state, intent, config, geometry, dt)
            if (state.y < lowest) lowest = state.y
        }

        val restingY = config.halfHeight + config.radius
        assertTrue(lowest > restingY - tolerance, "the mover reached y=$lowest, below the floor")
        assertTrue(abs(state.y - restingY) < tolerance, "the mover settled at ${state.y}")
        assertTrue(state.grounded, "the mover landed but is not grounded: $state")
    }

    @Test
    fun `step-down keeps a mover on a descending staircase that it would otherwise arc off`() {
        // What `stepDownHeight` is for, measured as a comparison rather than against a number
        // guessed in advance. Four treads, each a 0.25 drop - inside the default 0.3 - walked at
        // the same pace twice: once with the step-down and once with it disabled. If the feature
        // were inert the two counts would be equal, and this fails.
        val airborneWith = airborneTicksOnStairs(stepDown = 0.3f)
        val airborneWithout = airborneTicksOnStairs(stepDown = 0f)

        println(
            "[CharacterMoverGeometryTest] descending stairs over $TREAD_TICKS ticks: " +
                "airborne $airborneWith with step-down, $airborneWithout without",
        )
        assertTrue(
            airborneWithout > airborneWith * 2,
            "step-down is doing nothing: $airborneWith airborne ticks with it, $airborneWithout " +
                "without. Either the feature is inert or the staircase is not steep enough to " +
                "leave the ground on.",
        )
        assertTrue(
            airborneWith < TREAD_TICKS / 5,
            "with step-down the mover was still airborne on $airborneWith of $TREAD_TICKS ticks",
        )
    }

    @Test
    fun `a mover walks the whole staircase rather than stopping on a riser`() {
        // The counterpart to the comparison above: a mover that never moved would be airborne on
        // zero ticks and would pass it. This is the "never sticks" half for a descending stair.
        val geometry = staircase()
        val config = MoverScenario.config()
        val mover = CharacterMover()
        val state = MoverState(x = 0.5f, y = 1f + config.halfHeight + config.radius)
        val intent = MoveIntent(move = 1f)

        repeat(TREAD_TICKS) { mover.move(state, intent, config, geometry, dt) }

        assertTrue(state.x > 5f, "the mover did not walk down the steps; it ended at x=${state.x}")
        // The bottom tread is at y = 0.25, so a mover that walked all four is below where it began.
        assertTrue(
            state.y < 1f + config.halfHeight + config.radius,
            "the mover ended at y=${state.y}, no lower than it started: it did not descend",
        )
        assertTrue(state.grounded, "the mover ended in mid-air on a staircase: $state")
    }

    /** Four 2-wide treads dropping 0.25 each, with a riser joining every pair. */
    private fun staircase(): StaticCollision {
        val builder = StaticCollision.Builder(cellSize = 1f)
        var tread = 0
        while (tread < 4) {
            val left = tread * 2f
            val height = 1f - tread * 0.25f
            builder.segment(left, height, left + 2f, height)
            if (tread > 0) builder.segment(left, height, left, height + 0.25f)
            tread++
        }
        return builder.build()
    }

    /** Walks the staircase with the given step-down and returns how many ticks were airborne. */
    private fun airborneTicksOnStairs(stepDown: Float): Int {
        val geometry = staircase()
        val config = MoverScenario.config().apply { stepDownHeight = stepDown }
        val mover = CharacterMover()
        val state = MoverState(x = 0.5f, y = 1f + config.halfHeight + config.radius)
        val intent = MoveIntent(move = 1f)

        var airborne = 0
        repeat(TREAD_TICKS) {
            mover.move(state, intent, config, geometry, dt)
            if (!state.grounded) airborne++
        }
        return airborne
    }

    private companion object {
        /** Long enough to cross all four treads at walking pace, short enough to stay on them. */
        const val TREAD_TICKS: Int = 70
    }
}
