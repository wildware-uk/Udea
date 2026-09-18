package dev.wildware.udea.render.interp

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.RngStream
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.loop.WorldSimulation
import dev.wildware.udea.core.physics.PhysicsBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [RenderModule]'s one concession to spec 3.3, held to the claim its KDoc makes.
 *
 * `RenderModule` registers [InterpSnapshotSystem] into the world's system list — the single
 * presentation-owned thing inside the tick — and defends it with "a world ticked with the
 * interpolation machinery present is value-for-value identical to one ticked without it". That
 * claim was previously credited to `CameraRigTest`, which puts an `InterpSnapshotSystem` in
 * **both** of its fixtures: the only variable there is whether the *camera rig* advances, so
 * nothing in the suite asserted anything about this system at all. Adding a write to a
 * simulated component inside [InterpSnapshotSystem.onTick] left the whole module green.
 *
 * Here the system's presence is the variable, and the comparison covers both ways it could
 * perturb a tick:
 *
 * - **simulated component values**, compared as raw bits rather than printed floats, because
 *   two positions differing in the last ulp print identically and would make this pass over a
 *   real divergence;
 * - **the shared RNG stream**, because a system that merely *drew* from it would leave every
 *   component value untouched on the tick it ran and shift every later one. [MoveSystem] parks
 *   each draw in a field the digest reads, so a stolen draw shows up as a divergence rather
 *   than as nothing.
 */
class InterpSnapshotPurityTest {

    @Test
    fun `a world ticked with the interpolation system is identical to one ticked without it`() {
        val withSnapshot = Fixture(snapshots = true)
        val without = Fixture(snapshots = false)
        withSnapshot.spawn()
        without.spawn()

        repeat(TICKS) {
            withSnapshot.sim.step()
            without.sim.step()
        }

        // The system has to have done something, or "identical" is a statement about two
        // no-ops. `capturedCount` is one pose per body per tick.
        assertTrue(
            withSnapshot.snapshotSystem!!.capturedCount >= TICKS.toLong(),
            "the interpolation system recorded nothing, so this asserts nothing",
        )
        assertEquals(simulationDigest(without), simulationDigest(withSnapshot))
    }

    @Test
    fun `the digest this rests on is sensitive to a single stolen RNG draw`() {
        // The control. The assertion above is an equality, and an equality over a digest that
        // cannot tell two different worlds apart is §8's test that cannot fail. A third fixture
        // whose only difference is one extra draw from the shared stream must come out
        // different, or the comparison above is measuring nothing.
        val plain = Fixture(snapshots = true)
        val thief = Fixture(snapshots = true, stealing = true)
        plain.spawn()
        thief.spawn()

        repeat(TICKS) {
            plain.sim.step()
            thief.sim.step()
        }

        assertNotEquals(simulationDigest(plain), simulationDigest(thief))
    }

    /**
     * Every simulated value in a fixture's world, as a comparable string.
     *
     * Raw bits, not printed floats: two positions differing in the last ulp print identically.
     * The same shape as `CameraRigTest.simulationDigest`, and for the same reason — a
     * `WorldHasher.hash` would read a `WorldFieldStore` built from generated `Replicator`s, and
     * no component in this module has one yet. For this fixture the two are the same statement:
     * [PhysicsBody] is the entire simulation state.
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

    /** A world with or without the interpolation system, and nothing else different. */
    private class Fixture(snapshots: Boolean, stealing: Boolean = false) {

        val ctx: GameContext = testGameContext(seed = 42L)

        val world: World = configureWorld {
            injectables { gameContext(ctx) }
            systems {
                if (snapshots) add(InterpSnapshotSystem())
                add(MoveSystem())
                if (stealing) add(RngThiefSystem())
            }
        }

        val sim = WorldSimulation(ctx, world)

        val snapshotSystem: InterpSnapshotSystem? =
            if (snapshots) world.system<InterpSnapshotSystem>() else null

        fun spawn() {
            world.entity { it += PhysicsBody(x = 0f, y = 0f, linearX = 3f) }
            world.entity { it += PhysicsBody(x = -7f, y = 2f, linearX = -1.5f) }
        }
    }

    /**
     * Moves bodies and parks one RNG draw per body per tick in `linearY`.
     *
     * The draw is what makes the digest sensitive to the *stream* and not only to the component
     * values: a system that consumed randomness without writing anything would be invisible to
     * a position comparison on the tick it ran, and would change every position afterwards.
     */
    private class MoveSystem : SimSystem() {

        private val bodies = world.family { all(PhysicsBody) }

        override fun onTick() {
            bodies.forEach { entity ->
                val body = entity[PhysicsBody]
                body.x += body.linearX * ctx.clock.dt
                body.linearY = ctx.rng.nextFloat(RngStream.AI)
            }
        }
    }

    /** Draws once per tick and writes nothing. The negative control's only difference. */
    private class RngThiefSystem : SimSystem() {
        override fun onTick() {
            ctx.rng.nextFloat(RngStream.AI)
        }
    }

    private companion object {
        /** Long enough that a one-draw shift has moved every body somewhere else. */
        const val TICKS = 200
    }
}
