package dev.wildware.udea.core.module

import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.physics.PhysicsWorld

/**
 * Systems and modules the registry tests register.
 *
 * They are ordinary classes with ordinary constructors, which is the point being tested: none
 * of them has a no-arg constructor, so none of them could have been created by the
 * `kClass.createInstance()` the old engine used.
 */

/** A collaborator no context names, so it can only arrive through a constructor. */
internal class Scoreboard {
    var recorded: Int = 0
}

/** Takes only the context, so it can be registered as a bare constructor reference. */
internal class ContextOnlySystem(val context: GameContext) : SimSystem() {
    var ticks: Int = 0

    override fun onTick() {
        ticks++
    }
}

/** Takes the context *and* a collaborator: two real constructor parameters, both checked. */
internal class ScoringSystem(
    val context: GameContext,
    val scoreboard: Scoreboard,
) : SimSystem() {
    override fun onTick() {
        scoreboard.recorded++
    }
}

/** Takes a service off the context, which is the shape the standards prefer for a system. */
internal class SensorSystem(val physics: PhysicsWorld) : SimSystem() {
    override fun onTick(): Unit = Unit
}

internal open class NoOpSystem : SimSystem() {
    override fun onTick(): Unit = Unit
}

internal class EngineAbilitySystem : NoOpSystem()

internal class EngineAttributeSystem : NoOpSystem()

internal class GameBuffSystem : NoOpSystem()

internal class FirstSystem : NoOpSystem()

internal class SecondSystem : NoOpSystem()

internal class ThirdSystem : NoOpSystem()

/** Two systems that each insist on running after the other. */
internal class CycleA : NoOpSystem()

internal class CycleB : NoOpSystem()

internal class CycleC : NoOpSystem()

/** A system nobody registers, so a constraint naming it must fail loudly. */
internal class UnregisteredSystem : NoOpSystem()
