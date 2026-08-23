package dev.wildware.moba

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.EntityCreateContext
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.blueprint.Blueprint
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.core.blueprint.BlueprintSpawner
import dev.wildware.udea.core.blueprint.SpawnPlacement
import dev.wildware.udea.core.blueprint.blueprintSpawner
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaModule

/**
 * The example game's content, such as it is: one component, one blueprint, one system.
 *
 * ## What this module is and is not
 *
 * It is a real [UdeaModule], contributed to a real [dev.wildware.udea.core.module.UdeaGameDef],
 * and every entry point in `dev.wildware.moba.entry` builds the same one. That is the property
 * spec 4 asks for and the property that was missing: before this, nothing in the repository could
 * boot a game at all, so "dedicated server, agent harness and player run the identical
 * `Simulation`" was a claim with no executable behind it.
 *
 * It is **not** a MOBA. There are no champions, no lanes, no abilities and no network role; the
 * `raw-assets` tree next door has art for all of them and nothing consumes it yet. Adding content
 * here is Phase 2's work, and inventing some now would only make the wiring harder to read.
 */
public class MobaModule : UdeaModule {

    override val name: String get() = "moba"

    /**
     * The spawner, published on the context so `ctx.blueprints` can find it.
     *
     * Assigned by [MobaGame] between constructing this module and building the definition: a
     * [BlueprintSpawner] needs the `SimBarrier` and the `NetIdIndex`, both of which come off the
     * definition's `core` module, which cannot exist before the module list does.
     */
    public var spawner: BlueprintSpawner? = null

    override fun context(builder: GameContextBuilder) {
        builder.blueprintSpawner(
            checkNotNull(spawner) { "MobaGame wires the spawner before building the definition" },
        )
    }

    override fun simulation(registry: SimRegistry) {
        registry.add(SimPhase.Movement, { DriftSystem() })
    }
}

/**
 * Moves every [Position] a fixed amount per tick.
 *
 * The smallest system that makes a running instance *observably* running: two `/state` reads a
 * few ticks apart differ, a `time.step(120)` moves the world by a stated amount, and a rewind is
 * visible as a coordinate going back. Deterministic in ticks rather than seconds, so the value
 * after N ticks is the same on every machine and survives a snapshot restore.
 */
public class DriftSystem : SimSystem() {

    private val moving = family { all(Position) }

    override fun onTick() {
        moving.forEach { entity ->
            val position = entity[Position]
            position.x += DRIFT_PER_TICK
        }
    }

    private companion object {
        /** World units per tick. A round number so a reader can check the arithmetic by eye. */
        const val DRIFT_PER_TICK: Float = 0.25f
    }
}

/** This game's spatial component is [Position]. */
public object PositionPlacement : SpawnPlacement {

    override fun defaultIfAbsent(world: World, entity: Entity) {
        with(world) {
            if (entity.getOrNull(Position) == null) entity.configure { it += Position() }
        }
    }

    override fun moveTo(world: World, entity: Entity, x: Float, y: Float) {
        with(world) {
            val position = entity[Position]
            position.x = x
            position.y = y
        }
    }
}

/** The one thing that can be spawned. */
public object GruntBlueprint : Blueprint {

    override val id: BlueprintId = BlueprintId("grunt")

    override fun configure(context: EntityCreateContext, entity: Entity) {
        with(context) { entity += Position(hp = 40f) }
    }
}
