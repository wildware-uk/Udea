package dev.wildware.udea.core.physics

import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.UdeaGameDef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Physics advances exactly once per simulation tick, and writes a transform on no other tick.
 *
 * Both halves are reactions to `Box2DSystem`. It stepped a hardcoded `1/60f` from a world
 * driven by the render delta (`Box2DSystem.kt:80`), so how much physics a wall second contained
 * depended on the frame rate; and it wrote `body.setTransform(...)` for every entity
 * immediately before each step (`:77`), so the solver's own result was discarded every tick.
 */
class PhysicsStepTest {

    private fun host(physics: PhysicsWorld): GameHost =
        GameHost(RenderMode.Headless, UdeaGameDef(listOf(PhysicsOverrideModule(physics))))

    @Test
    fun `randomised frame deltas produce exactly one physics step per simulation tick`() {
        val physics = SpyPhysicsWorld()
        val host = host(physics)
        val random = Random(20_260_823L)

        // Frame pacing a real machine produces: a stutter here, a fast frame there. None of it
        // may change how much physics a tick contains.
        repeat(600) {
            host.frame(0.002f + random.nextFloat() * 0.028f)
        }

        assertTrue(host.totalTicks > 0, "the randomised frames advanced the simulation")
        assertEquals(
            host.totalTicks.toInt(),
            physics.stepCount,
            "physics stepped ${physics.stepCount} times over ${host.totalTicks} ticks",
        )
        assertEquals(0L, host.loop.truncatedFrames, "no frame hit the catch-up ceiling")
    }

    @Test
    fun `600 ticks produce exactly 600 physics steps`() {
        val physics = SpyPhysicsWorld()
        val host = host(physics)

        host.run(600)

        assertEquals(600, physics.stepCount)
        assertEquals(600L, host.totalTicks)
    }

    @Test
    fun `the tick the loop runs is the fixed tick, whatever the frame delta was`() {
        val physics = SpyPhysicsWorld()
        val host = host(physics)

        // Ten wall seconds, delivered in wildly uneven frames, is exactly 600 ticks at 60Hz —
        // the property the integer accumulator exists for, restated here because physics is
        // what would drift first if it were ever false.
        repeat(1000) { host.frame(0.01f) }

        assertEquals(600, physics.stepCount)
        assertEquals(1f / 60f, host.game.simulation.dt, "the fixed dt is the tick rate's")
    }

    @Test
    fun `no transform is written on a tick nobody asked for a teleport`() {
        val physics = SpyPhysicsWorld()
        val host = host(physics)

        host.world.entity {
            it += PhysicsBody(x = 3f, y = 4f)
            it += Box()
        }

        host.run(600)

        assertEquals(0, physics.teleportCount, "600 ticks and not one setTransform")
        assertEquals(600, physics.stepCount)
        assertTrue(physics.events.none { it.startsWith("teleport") }, "${physics.events.take(5)}")
        assertEquals(600L, host.tick.value, "the run really happened")
    }

    @Test
    fun `a Teleport command is applied once, before the step, and then removed`() {
        val physics = SpyPhysicsWorld()
        val host = host(physics)

        val entity = host.world.entity {
            it += PhysicsBody(x = 0f, y = 0f)
            it += Box()
        }
        val body = with(host.world) { entity[PhysicsBody] }
        body.handle = physics.createBody(BodyDef(body, NetId.NONE, emptyList()))

        with(host.world) { entity.configure { it += Teleport(x = 12f, y = -5f, angle = 1.5f) } }
        physics.clearEvents()

        host.run(1)

        assertEquals(1, physics.teleportCount, "applied exactly once")
        assertEquals(
            listOf(0, 1),
            listOf(
                physics.events.indexOfFirst { it.startsWith("teleport") },
                physics.events.indexOfFirst { it == "step" },
            ),
            "PreSimulation runs before Physics, so the teleport lands first: ${physics.events}",
        )
        assertEquals(12f, body.x)
        assertEquals(-5f, body.y)
        assertNull(
            with(host.world) { entity.getOrNull(Teleport) },
            "the command is consumed, so it cannot re-fire next tick",
        )

        physics.clearEvents()
        host.run(5)
        assertEquals(1, physics.teleportCount, "and it does not re-fire on later ticks")
    }

    @Test
    fun `a teleport queued after a rebuild does not kill the tick over a body with no NetId`() {
        // The end of the chain the dangling handle starts: an entity with a PhysicsBody and no
        // NetId (debris, a server-only projectile) survives a restore, something teleports it,
        // and TeleportSystem's `handle.isValid` gate decides whether the tick lives. If the
        // rebuild leaves the destroyed body's handle in place the gate says yes, `teleport`
        // throws NoSuchBodyException out of onTick and out of world.update, and the loop dies.
        val physics = SpyPhysicsWorld()
        val host = host(physics)
        val entity = host.world.entity {
            it += PhysicsBody(x = 0f, y = 0f)
            it += Box()
        }
        val body = with(host.world) { entity[PhysicsBody] }
        body.handle = physics.createBody(BodyDef(body, NetId.NONE, emptyList()))

        physics.rebuildFrom(host.world, host.ctx[CoreModule.NET_IDS])
        with(host.world) { entity.configure { it += Teleport(x = 3f, y = 4f) } }

        host.run(1)

        assertEquals(1L, host.tick.value, "the tick completed instead of throwing out of onTick")
        assertEquals(0, physics.teleportCount, "there is no body to teleport, so none was tried")
        assertEquals(3f, body.x, "and the component still moved: the command is authoritative")
        assertEquals(4f, body.y)
        assertNull(
            with(host.world) { entity.getOrNull(Teleport) },
            "and the command was consumed rather than left to re-fire",
        )
    }
}
