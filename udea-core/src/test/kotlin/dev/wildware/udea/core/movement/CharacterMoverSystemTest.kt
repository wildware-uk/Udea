package dev.wildware.udea.core.movement

import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.physics.PhysicsStepSystem
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The system half: `CoreModule` registers a mover at [SimPhase.Movement], it runs on a real
 * assembled world, and it runs **before** the solver.
 *
 * A mover nothing calls is a library, not the authoritative movement model. So this drives a
 * `GameHost` - the shipped loop - rather than calling `move` directly, and asserts on entity
 * state after real ticks.
 */
class CharacterMoverSystemTest {

    /**
     * A definition with no game modules. `CoreModule` is implicit and is reachable as
     * [UdeaGameDef.core], which is where the scene's geometry is set from.
     */
    private fun definition(): UdeaGameDef = UdeaGameDef(emptyList())

    private fun host(def: UdeaGameDef): GameHost = GameHost(RenderMode.Headless, def)

    private fun floor(): StaticCollision =
        StaticCollision.Builder(cellSize = 2f).segment(-20f, 0f, 20f, 0f).build()

    @Test
    fun `an entity with a state, a config and an intent is moved by the assembled loop`() {
        val def = definition()
        def.core.sceneCollision.geometry = floor()
        val host = host(def)
        val config = MoverScenario.config()
        val state = MoverState(x = 0f, y = 4f)

        host.game.world.entity {
            it += state
            it += config
            it += MoveIntent(move = 1f)
        }

        host.run(120)

        val restingY = config.halfHeight + config.radius
        assertTrue(
            abs(state.y - restingY) < 1e-2f,
            "the entity did not land on the floor; it is at ${state.y}",
        )
        assertTrue(state.x > 5f, "the entity did not walk right; it is at x=${state.x}")
        assertTrue(state.grounded, "the entity landed but is not grounded: $state")
    }

    @Test
    fun `an entity missing an intent is not moved`() {
        // The family is the contract: movement is driven by intent, and an entity nobody is
        // controlling must not drift. Without this, a family widened to `all(MoverState)` would
        // start applying gravity to scenery.
        val def = definition()
        def.core.sceneCollision.geometry = floor()
        val host = host(def)
        val state = MoverState(x = 3f, y = 9f)

        host.game.world.entity {
            it += state
            it += MoverScenario.config()
        }

        host.run(120)

        assertEquals(3f, state.x)
        assertEquals(9f, state.y)
        assertEquals(0f, state.velocityY, "gravity was applied to an entity with no intent")
    }

    @Test
    fun `the mover system runs in Movement, before the physics step`() {
        // Spec 3.4: Box2D reacts to authoritative movement rather than deciding it. The
        // constraint is declared in `CoreModule` and would fail world construction if it were
        // contradicted; this asserts the resolved order that constraint is protecting.
        val manifest = definition().build().manifest.render()
        val lines = manifest.trim().lines()
        val moverLine = lines.indexOfFirst { it.contains(CharacterMoverSystem::class.java.name) }
        val physicsLine = lines.indexOfFirst { it.contains(PhysicsStepSystem::class.java.name) }

        assertTrue(moverLine >= 0, "CoreModule registers no CharacterMoverSystem:\n$manifest")
        assertTrue(physicsLine >= 0, "CoreModule registers no PhysicsStepSystem:\n$manifest")
        assertTrue(
            moverLine < physicsLine,
            "the solver runs before the mover, which inverts spec 3.4:\n$manifest",
        )
        assertTrue(
            lines[moverLine].startsWith(SimPhase.Movement.name),
            "the mover is registered in ${lines[moverLine].substringBefore(' ')}, not Movement",
        )
    }

    @Test
    fun `two hosts in one JVM have their own scene collision`() {
        // The reason the geometry is instance state on a module and not an object: the smell
        // section 1 names is `lateinit var gameScreen`, and one shared level would be exactly it.
        val first = definition()
        val second = definition()
        first.core.sceneCollision.geometry = floor()

        assertEquals(1, first.core.sceneCollision.geometry.segmentCount)
        assertEquals(0, second.core.sceneCollision.geometry.segmentCount)

        val firstHost = host(first)
        val secondHost = host(second)
        val onFloor = MoverState(x = 0f, y = 4f)
        val inSpace = MoverState(x = 0f, y = 4f)
        firstHost.game.world.entity {
            it += onFloor
            it += MoverScenario.config()
            it += MoveIntent()
        }
        secondHost.game.world.entity {
            it += inSpace
            it += MoverScenario.config()
            it += MoveIntent()
        }

        firstHost.run(120)
        secondHost.run(120)

        assertTrue(onFloor.grounded, "the mover in the world with a floor did not land: $onFloor")
        assertTrue(!inSpace.grounded, "the mover in the empty world found a floor: $inSpace")
        assertTrue(inSpace.y < onFloor.y - 1f, "the empty world's mover did not fall: $inSpace")
    }
}
