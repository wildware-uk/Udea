package dev.wildware.udea.render.interp

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.loop.WorldSimulation
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.core.physics.Teleport
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The judder signature, and the three cases where interpolating is the wrong answer.
 *
 * The design claim (spec 3.3) is that a 60Hz simulation renders smoothly at a refresh rate that
 * is not a multiple of 60. The observable property is the *per-frame positional delta*: with
 * interpolation it is nearly constant, and without it the deltas repeat `0, 0, one tick's
 * worth`. These tests measure that number rather than asserting a helper was called.
 *
 * The clock is advanced by a real [WorldSimulation], because `SimClock` only moves for the
 * kernel — which is also why the rewind case substitutes a [PoseHistory] instead of winding the
 * clock back by hand.
 */
class InterpolationTest {

    private val ctx: GameContext = testGameContext(seed = 5L)

    private val world: World = configureWorld {
        injectables { gameContext(ctx) }
        systems {
            add(InterpSnapshotSystem())
            // After the pose has been recorded, exactly as movement runs after PreSimulation.
            add(ConstantVelocitySystem())
        }
    }

    private val sim = WorldSimulation(ctx, world)

    private val snapshots: InterpSnapshotSystem = world.system<InterpSnapshotSystem>()

    private val interpolator = Interpolator(ctx.clock, snapshots)

    private val pose = Pose()

    @Test
    fun `a constant velocity entity renders with a near constant per-frame delta at 144Hz`() {
        val entity = spawn(velocityX = SPEED)

        val deltas = sampleAt144Hz(entity, frames = 144)

        // The judder signature is a repeating 0 / 0 / big pattern. Every delta landing within
        // 1% of the mean is the direct denial of it.
        val mean = deltas.average()
        val worst = deltas.maxOf { abs(it - mean) / mean }
        assertTrue(worst < 0.01, "per-frame delta varied by ${worst * 100}%: $deltas")
        assertTrue(deltas.none { it <= 0.0 }, "a frame made no progress: $deltas")
    }

    @Test
    fun `drawing at the raw simulated pose produces the judder this exists to remove`() {
        // The control, and what makes the test above capable of failing: if `interpolate`
        // quietly returned the current pose, the assertion above would pass on a broken
        // implementation. This measures the same motion read straight off the component and
        // asserts the stall is there, so the two cannot both be right by accident.
        val entity = spawn(velocityX = SPEED)
        val deltas = ArrayList<Double>()
        var previous = 0.0

        repeat(144 + WARM_UP_FRAMES) { frame ->
            advanceTicksFor(frame)
            val body = with(world) { entity[PhysicsBody] }
            if (frame >= WARM_UP_FRAMES) deltas += body.x - previous
            previous = body.x.toDouble()
        }

        val stalledFrames = deltas.count { it == 0.0 }
        assertTrue(
            stalledFrames > 50,
            "at 144Hz over 60Hz most frames should show no movement, was $stalledFrames of 144",
        )
    }

    @Test
    fun `alpha of one reproduces the current pose to the bit`() {
        val entity = spawn(velocityX = SPEED)
        sim.step()
        sim.step()
        sim.step()
        with(world) { entity[PhysicsBody].x = 12.3456789f }

        interpolator.interpolate(world, entity, 1f, pose)

        val body = with(world) { entity[PhysicsBody] }
        assertEquals(body.x.toRawBits(), pose.x.toRawBits(), "x must be bit-identical at alpha 1")
        assertEquals(body.y.toRawBits(), pose.y.toRawBits(), "y must be bit-identical at alpha 1")
    }

    @Test
    fun `alpha of zero reproduces the pose the tick started from`() {
        val entity = spawn(velocityX = SPEED)
        sim.step()
        val startOfTick = with(world) { entity[Interp].prevX }
        with(world) { entity[PhysicsBody].x = 99f }

        interpolator.interpolate(world, entity, 0f, pose)

        assertEquals(startOfTick.toRawBits(), pose.x.toRawBits())
    }

    @Test
    fun `a teleported entity renders at its destination rather than sweeping to it`() {
        val entity = spawn(velocityX = 0f)
        sim.step()
        sim.step()

        with(world) { entity.configure { it += Teleport(x = TELEPORT_X, y = 0f) } }
        // The tick the teleport is consumed on. InterpSnapshotSystem sees the command and marks
        // the entity; the move itself is `TeleportSystem`'s job in a real game and is done by
        // hand here, because that system belongs to udea-core and this is about the renderer.
        sim.step()
        with(world) {
            entity[PhysicsBody].x = TELEPORT_X
            entity.configure { it -= Teleport }
        }

        // The clock advanced normally, so a pass here cannot be coming from the restore rule.
        assertFalse(interpolator.isRestoreFrame, "this must be a Teleport snap, not a restore")
        for (alpha in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            interpolator.interpolate(world, entity, alpha, pose)
            assertEquals(TELEPORT_X, pose.x, "an intermediate position was drawn at alpha $alpha")
        }
    }

    @Test
    fun `the snap lasts one tick and interpolation resumes on the next`() {
        val entity = spawn(velocityX = SPEED)
        with(world) { entity.configure { it += Teleport(x = TELEPORT_X, y = 0f) } }
        sim.step()
        with(world) { entity.configure { it -= Teleport } }

        sim.step()

        assertFalse(
            with(world) { entity[Interp].snap },
            "a teleport must snap once, not pin the entity to its destination forever",
        )
    }

    @Test
    fun `the first frame after a rewind renders the restored pose rather than a lerp`() {
        val entity = spawn(velocityX = SPEED)
        repeat(60) { sim.step() }
        val movedTo = with(world) { entity[PhysicsBody].x }
        assertTrue(movedTo > 0f, "the entity must have moved for this test to mean anything")

        // What a restore looks like from the renderer's side: component values are replaced with
        // an older world's, and the pose history now describes a tick that is no longer next.
        with(world) { entity[PhysicsBody].x = 0f }
        val rewound = Interpolator(ctx.clock, StalePoseHistory(Tick(9_999)))

        assertTrue(rewound.isRestoreFrame, "a broken tick sequence must be seen as a restore")
        rewound.interpolate(world, entity, 0.5f, pose)
        assertEquals(0f, pose.x, "the restored pose must be drawn, not a lerp from the old one")
    }

    @Test
    fun `an entity gains its Interp on the first tick it exists for`() {
        val entity = spawn(velocityX = 0f)

        sim.step()

        assertTrue(with(world) { Interp in entity }, "a body must not be left without an Interp")
    }

    @Test
    fun `rotation takes the short arc across the pi boundary`() {
        val half = Interpolator.lerpAngle(from = 3.10f, to = -3.10f, t = 0.5f)

        // The long way round passes through 0; the short way passes just outside +-pi.
        assertTrue(abs(half) > 3.1f, "expected the short arc, went through $half")
    }

    // --- helpers -------------------------------------------------------------------------

    private fun sampleAt144Hz(entity: Entity, frames: Int): List<Double> {
        val deltas = ArrayList<Double>(frames)
        var previous = Double.NaN

        repeat(frames + WARM_UP_FRAMES) { frame ->
            val alpha = advanceTicksFor(frame)
            interpolator.interpolate(world, entity, alpha, pose)
            // The first frames of a 144Hz display fall before the first 60Hz tick has run, so
            // there is genuinely nothing to interpolate yet. Measuring them would be measuring
            // start-up rather than judder.
            if (frame >= WARM_UP_FRAMES && !previous.isNaN()) deltas += pose.x - previous
            previous = pose.x.toDouble()
        }
        return deltas
    }

    /**
     * Runs whatever ticks have fallen due by frame [frame] of a 144Hz display, and returns the
     * alpha that frame should be drawn at.
     *
     * Derived from exact rational time rather than an accumulator, so this measures the
     * interpolator instead of a second copy of `GameLoop`'s arithmetic.
     */
    private fun advanceTicksFor(frame: Int): Float {
        val exactTicks = frame.toDouble() / RENDER_HZ * SIM_HZ
        val due = exactTicks.toLong()
        while (ctx.clock.tick.value < due) sim.step()
        return (exactTicks - due).toFloat()
    }

    private fun spawn(velocityX: Float): Entity = world.entity {
        it += PhysicsBody(x = 0f, y = 0f, linearX = velocityX)
    }

    /** A [PoseHistory] pointing at a tick the clock is nowhere near: a rewind, from here. */
    private class StalePoseHistory(override val lastTick: Tick) : PoseHistory

    /**
     * Stands in for `CharacterMover`: integrates velocity once per tick.
     *
     * Movement has to be a system rather than a loop in the test body, because the whole point
     * of [InterpSnapshotSystem] is that it records the pose *before* movement runs, and only a
     * real system ordering can demonstrate that.
     */
    private class ConstantVelocitySystem : SimSystem() {

        private val bodies: Family = world.family { all(PhysicsBody) }

        override fun onTick() {
            bodies.forEach { entity ->
                val body = entity[PhysicsBody]
                body.x += body.linearX * ctx.clock.dt
                body.y += body.linearY * ctx.clock.dt
            }
        }
    }

    private companion object {
        const val SIM_HZ = 60.0
        const val RENDER_HZ = 144.0
        const val SPEED = 4f
        const val TELEPORT_X = 50f

        /** 144Hz frames that fall before the first 60Hz tick, plus one for the first delta. */
        const val WARM_UP_FRAMES = 6
    }
}
