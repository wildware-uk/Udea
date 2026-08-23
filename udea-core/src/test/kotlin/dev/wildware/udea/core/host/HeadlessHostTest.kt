package dev.wildware.udea.core.host

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.loop.BarrierAction
import dev.wildware.udea.core.loop.SimBarrier
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.core.scene.MarkerScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A 200-entity scene runs ten thousand ticks in a plain JUnit JVM — no window, no GL, no
 * `Gdx.app`, no `postRunnable`.
 *
 * This is the thing `GameScreen` structurally could not do: it owned a viewport, a
 * `SpriteBatch` and a `RayHandler`, so constructing it required a GL context, so nothing it
 * owned could run on a dedicated server or in CI. The absence of GL is not asserted by hoping —
 * a GL call would throw here, and the `udeaVerifyHeadless` bytecode gate in `udea-render`
 * covers the compiled classes of this module as well.
 */
class HeadlessHostTest {

    /** Moves every body a little, so ten thousand ticks are ten thousand ticks of work. */
    private class DriftSystem : SimSystem() {
        private val bodies = world.family { all(PhysicsBody) }

        var updates: Long = 0L
            private set

        override fun onTick() {
            val entities = bodies.entities
            var index = 0
            while (index < entities.size) {
                val body = entities[index][PhysicsBody]
                body.x += body.linearX * ctx.clock.dt
                body.y += body.linearY * ctx.clock.dt
                index++
                updates++
            }
        }
    }

    private class DriftModule : UdeaModule {
        override fun simulation(registry: SimRegistry) {
            registry.add(SimPhase.Movement, { DriftSystem() })
        }
    }

    @Test
    fun `a 200-entity scene runs 10000 ticks with no window and no GL context`() {
        val scene = MarkerScene(SceneId("arena"), seed = 31_337L, entityCount = 200, withBodies = true)
        val def = UdeaGameDef(listOf(DriftModule()))
        def.core.scenes.register(scene)
        val host = GameHost(RenderMode.Headless, def)
        host.ctx.scenes.requestScene(scene.id)

        host.run(10_000)

        assertEquals(10_000L, host.totalTicks)
        assertEquals(10_000L, host.tick.value)
        assertEquals(200, host.world.numEntities)
        assertEquals(200, host.ctx.physics.bodyCount, "the scene's bodies are all still there")
        assertEquals(
            200L * 10_000L,
            host.world.system<DriftSystem>().updates,
            "every entity was updated on every tick",
        )
        assertNull(host.presentation, "Headless has no Presentation")
    }

    @Test
    fun `stop() ends an unpaced headless run from inside a system`() {
        val scene = MarkerScene(SceneId("arena"), seed = 5L, entityCount = 3)
        val def = UdeaGameDef(emptyList())
        def.core.scenes.register(scene)
        val host = GameHost(RenderMode.Headless, def)
        host.ctx.scenes.requestScene(scene.id)

        // A barrier action stops the host at a tick boundary, which is the shape an agent's
        // `stop` tool call has.
        host.ctx[SimBarrier.KEY].submit(
            object : BarrierAction {
                override val label: String get() = "stop the host"

                override fun apply(world: World, ctx: GameContext) {
                    host.stop()
                }
            },
        )

        host.run()

        assertTrue(!host.running, "run() returned because stop() was called")
        assertEquals(1L, host.tick.value, "and it returned after the tick in flight, not mid-tick")
    }

    @Test
    fun `two headless hosts in one JVM do not see each other`() {
        fun build(seed: Long): GameHost {
            val scene = MarkerScene(SceneId("arena"), seed = seed, entityCount = 4)
            val def = UdeaGameDef(emptyList())
            def.core.scenes.register(scene)
            return GameHost(RenderMode.Headless, def).also { it.ctx.scenes.requestScene(scene.id) }
        }

        val alpha = build(1L)
        val beta = build(2L)

        alpha.run(50)
        beta.run(7)

        assertEquals(50L, alpha.tick.value)
        assertEquals(7L, beta.tick.value, "the second host has its own clock")
        assertTrue(
            alpha.ctx[CoreModule.NET_IDS] !== beta.ctx[CoreModule.NET_IDS],
            "and its own id space; the old file-level globals made this impossible",
        )
    }
}
