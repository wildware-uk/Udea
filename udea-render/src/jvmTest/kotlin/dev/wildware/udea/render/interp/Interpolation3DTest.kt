package dev.wildware.udea.render.interp

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.RngStream
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.loop.BarrierAction
import dev.wildware.udea.core.loop.WorldSimulation
import dev.wildware.udea.core.spatial.Transform3D
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A `Transform3D` drawn between the last two ticks at the render alpha (issue #246).
 *
 * The clock is advanced by a real [WorldSimulation], as `InterpolationTest` does, because
 * `SimClock` only moves for the kernel. [Interp3DSnapshotSystem] is added last, standing in for
 * its real place in `SimPhase.Cleanup`, after everything that moves an entity.
 */
class Interpolation3DTest {

    private val ctx: GameContext = testGameContext(seed = 9L)

    private val world: World = configureWorld {
        injectables { gameContext(ctx) }
        systems {
            add(WalkSystem())
            add(Interp3DSnapshotSystem())
        }
    }

    private val sim = WorldSimulation(ctx, world)

    private val interpolator = Interpolator3D(ctx.clock, world.system<Interp3DSnapshotSystem>())

    private val pose = Pose3D()

    @Test
    fun `at fractional alpha the pose lies that fraction of the way between the last two ticks`() {
        val entity = spawn(Transform3D(x = 1f, y = 2f, z = 3f, rotationZ = 0.5f), Walk(dx = 4f, dy = -2f, dz = 1f, turn = 0.25f))
        sim.step()
        sim.step()
        // After two ticks: the end of the first tick is (5, 0, 4) heading 0.75, the end of the
        // second is (9, -2, 5) heading 1.0. Values chosen so every lerp below is exact in floats.
        for ((alpha, expected) in listOf(
            0f to listOf(5f, 0f, 4f, 0.75f),
            0.25f to listOf(6f, -0.5f, 4.25f, 0.8125f),
            0.5f to listOf(7f, -1f, 4.5f, 0.875f),
            0.75f to listOf(8f, -1.5f, 4.75f, 0.9375f),
        )) {
            assertTrue(interpolator.interpolate(world, entity, alpha, pose))
            assertEquals(expected, listOf(pose.x, pose.y, pose.z, pose.rotationZ), "at alpha $alpha")
        }
    }

    @Test
    fun `alpha of one reproduces the current transform to the bit`() {
        val entity = spawn(Transform3D(), Walk(dx = 0.1f, dy = 0.37f, dz = 0.013f, turn = 0.0071f))
        repeat(7) { sim.step() }

        interpolator.interpolate(world, entity, 1f, pose)

        val t = with(world) { entity[Transform3D] }
        assertEquals(
            listOf(t.x, t.y, t.z, t.rotationZ).map { it.toRawBits() },
            listOf(pose.x, pose.y, pose.z, pose.rotationZ).map { it.toRawBits() },
        )
    }

    @Test
    fun `a constant velocity 3D entity renders with a near constant per-frame step at 144Hz`() {
        val entity = spawn(Transform3D(), Walk(dx = 0.2f, dy = 0.1f, dz = 0.05f, turn = 0f))
        val steps = ArrayList<Double>()
        var previous: Triple<Float, Float, Float>? = null

        repeat(FRAMES + WARM_UP_FRAMES) { frame ->
            val alpha = advanceTicksFor(frame)
            interpolator.interpolate(world, entity, alpha, pose)
            val here = Triple(pose.x, pose.y, pose.z)
            if (frame >= WARM_UP_FRAMES && previous != null) steps += distance(previous!!, here)
            previous = here
        }

        // Without interpolation the steps repeat 0 / 0 / one tick's worth. Every step within 1% of
        // the mean is the denial of that.
        val mean = steps.average()
        val worst = steps.maxOf { abs(it - mean) / mean }
        assertTrue(worst < 0.01, "per-frame step varied by ${worst * 100}%: $steps")
    }

    @Test
    fun `a position a snapshot applied through the barrier is slid to, not jumped to`() {
        // What a client does: the server's position lands through the SimBarrier at the top of a
        // step, before any system runs. A pose recorded at the *start* of the tick would already
        // hold it, and the model would hop from snapshot to snapshot.
        val entity = spawn(Transform3D(x = 0f, y = 0f, z = 0f), walk = null)
        sim.step()
        sim.barrier.submit(MoveTo(entity, x = 6f, y = -3f, z = 2f, heading = 1f))
        sim.step()

        interpolator.interpolate(world, entity, 0.5f, pose)

        assertEquals(listOf(3f, -1.5f, 1f, 0.5f), listOf(pose.x, pose.y, pose.z, pose.rotationZ))
    }

    @Test
    fun `a transform moved between ticks is drawn where it now is`() {
        // An editor gizmo dragged while the game is paused writes the transform with no tick
        // running. Drawing a lerp would put the model a tick behind the handle under the pointer.
        val entity = spawn(Transform3D(), Walk(dx = 1f, dy = 0f, dz = 0f, turn = 0f))
        sim.step()
        sim.step()
        with(world) { entity[Transform3D].x = 40f }

        interpolator.interpolate(world, entity, 0.5f, pose)

        assertEquals(40f, pose.x)
    }

    @Test
    fun `an entity no tick has ended on is drawn where it is`() {
        sim.step()
        val entity = spawn(Transform3D(x = 12f, y = 3f, z = 1f, rotationZ = 2f), walk = null)

        interpolator.interpolate(world, entity, 0.5f, pose)

        assertEquals(listOf(12f, 3f, 1f, 2f), listOf(pose.x, pose.y, pose.z, pose.rotationZ))
    }

    @Test
    fun `the first frame after a rewind draws the restored pose rather than a lerp`() {
        val entity = spawn(Transform3D(), Walk(dx = 1f, dy = 0f, dz = 0f, turn = 0f))
        repeat(10) { sim.step() }
        val rewound = Interpolator3D(ctx.clock, StaleHistory(Tick(9_999)))

        assertTrue(rewound.isRestoreFrame, "a broken tick sequence must be seen as a restore")
        assertFalse(interpolator.isRestoreFrame, "the live history must not read as a restore")
        rewound.interpolate(world, entity, 0.5f, pose)
        assertEquals(with(world) { entity[Transform3D].x }, pose.x)
    }

    @Test
    fun `the heading turns the short way across pi`() {
        val entity = spawn(Transform3D(rotationZ = 3.0f), Walk(dx = 0f, dy = 0f, dz = 0f, turn = 0.2f))
        sim.step()
        sim.step()
        // 3.2 at the end of the first tick, 3.4 at the end of the second: across pi. Written back
        // into (-pi, pi] the way a game that wraps its heading would, so plain lerp goes the long way.
        with(world) { entity[Transform3D].rotationZ = 3.4f - 2f * kotlin.math.PI.toFloat() }
        with(world) { entity[Interp3D].lastHeading = entity[Transform3D].rotationZ }

        interpolator.interpolate(world, entity, 0.5f, pose)

        assertTrue(abs(pose.rotationZ - 3.3f) < 1e-4f, "expected the short arc through 3.3, got ${pose.rotationZ}")
    }

    @Test
    fun `pitch, roll and scale are drawn as they stand`() {
        val entity = spawn(
            Transform3D(rotationX = 0.1f, rotationY = 0.2f, scaleX = 2f, scaleY = 3f, scaleZ = 4f),
            Walk(dx = 1f, dy = 0f, dz = 0f, turn = 0f),
        )
        sim.step()
        sim.step()

        interpolator.interpolate(world, entity, 0.5f, pose)

        assertEquals(listOf(0.1f, 0.2f, 2f, 3f, 4f), listOf(pose.rotationX, pose.rotationY, pose.scaleX, pose.scaleY, pose.scaleZ))
    }

    @Test
    fun `an entity with no transform has no 3D pose`() {
        val entity = world.entity { }

        assertFalse(interpolator.interpolate(world, entity, 0.5f, pose))
    }

    @Test
    fun `a world ticked with the 3D snapshot system is identical to one ticked without it`() {
        fun run(withSnapshots: Boolean): String {
            val runCtx = testGameContext(seed = 21L)
            val runWorld = configureWorld {
                injectables { gameContext(runCtx) }
                systems {
                    add(WalkSystem(drawRandom = true))
                    if (withSnapshots) add(Interp3DSnapshotSystem())
                }
            }
            val runSim = WorldSimulation(runCtx, runWorld)
            runWorld.entity {
                it += Transform3D(x = 1f)
                it += Walk(dx = 0.3f, dy = -0.2f, dz = 0.1f, turn = 0.05f)
            }
            repeat(120) { runSim.step() }
            val digest = ArrayList<String>()
            with(runWorld) {
                runWorld.family { all(Transform3D) }.forEach { entity ->
                    val t = entity[Transform3D]
                    digest += listOf(t.x, t.y, t.z, t.rotationX, t.rotationY, t.rotationZ, t.scaleX, t.scaleY, t.scaleZ)
                        .joinToString(",") { it.toRawBits().toString() }
                }
            }
            return "tick=${runCtx.clock.tick.value} $digest"
        }

        assertEquals(run(withSnapshots = false), run(withSnapshots = true))
    }

    // --- helpers -------------------------------------------------------------------------

    private fun spawn(transform: Transform3D, walk: Walk?): Entity = world.entity {
        it += transform
        if (walk != null) it += walk
    }

    private fun distance(a: Triple<Float, Float, Float>, b: Triple<Float, Float, Float>): Double {
        val dx = (b.first - a.first).toDouble()
        val dy = (b.second - a.second).toDouble()
        val dz = (b.third - a.third).toDouble()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Runs the ticks due by frame [frame] of a 144Hz display and returns that frame's alpha. */
    private fun advanceTicksFor(frame: Int): Float {
        val exactTicks = frame.toDouble() / RENDER_HZ * SIM_HZ
        val due = exactTicks.toLong()
        while (ctx.clock.tick.value < due) sim.step()
        return (exactTicks - due).toFloat()
    }

    /** A per-tick velocity and turn rate: the test's movement model. */
    private class Walk(val dx: Float, val dy: Float, val dz: Float, val turn: Float) :
        com.github.quillraven.fleks.Component<Walk> {
        override fun type(): com.github.quillraven.fleks.ComponentType<Walk> = Walk

        companion object : com.github.quillraven.fleks.ComponentType<Walk>()
    }

    /**
     * Moves every walking transform once per tick. With [drawRandom] it also parks a draw from the
     * shared stream in `rotationX`, so the purity test sees a system that stole a draw.
     */
    private class WalkSystem(private val drawRandom: Boolean = false) : SimSystem() {

        private val walkers: Family = world.family { all(Transform3D, Walk) }

        override fun onTick() {
            walkers.forEach { entity ->
                val t = entity[Transform3D]
                val walk = entity[Walk]
                t.x += walk.dx
                t.y += walk.dy
                t.z += walk.dz
                t.rotationZ += walk.turn
                if (drawRandom) t.rotationX = ctx.rng.nextFloat(RngStream.AI)
            }
        }
    }

    /** What a client's snapshot apply does to one entity, queued on the barrier. */
    private class MoveTo(
        private val entity: Entity,
        private val x: Float,
        private val y: Float,
        private val z: Float,
        private val heading: Float,
    ) : BarrierAction {
        override val label: String = "apply a snapshot"

        override fun apply(world: World, ctx: GameContext) {
            with(world) {
                val t = entity[Transform3D]
                t.x = x
                t.y = y
                t.z = z
                t.rotationZ = heading
            }
        }
    }

    private class StaleHistory(override val lastTick: Tick) : PoseHistory

    private companion object {
        const val SIM_HZ = 60.0
        const val RENDER_HZ = 144.0
        const val FRAMES = 144

        /** 144Hz frames before the second tick has ended, when there is nothing to lerp between yet. */
        const val WARM_UP_FRAMES = 6
    }
}
