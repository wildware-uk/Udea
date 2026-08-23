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
 * Moves every [Position] a fixed amount per tick, around a field of fixed width.
 *
 * The smallest system that makes a running instance *observably* running: two `/state` reads a
 * few ticks apart differ, a `time.step(120)` moves the world by a stated amount, and a rewind is
 * visible as a coordinate going back. Deterministic in ticks rather than seconds, so the value
 * after N ticks is the same on every machine and survives a snapshot restore.
 *
 * ## Why it wraps, which it did not before
 *
 * It used to be `position.x += DRIFT_PER_TICK` with no bound, and that made **every screenshot
 * after the first eight seconds a black frame.** The unit leaves `MobaScene`'s camera at about
 * tick 460; an instance an agent connects to has usually been up for thousands of ticks, so
 * `render.screenshot` returned a perfectly valid PNG of an empty framebuffer and
 * `render.compare_artifacts` reported `identical:true` for every pair of them. That reads
 * exactly like a broken renderer, and it took a live instance to tell the two apart - which is
 * the whole reason a blank capture is worse than a red one.
 *
 * So the field is `[0, FIELD_WIDTH)` and the unit laps it, in [LAP_TICKS] ticks. `MobaScene`
 * frames that interval, so a unit is always somewhere in shot.
 *
 * **What that costs, stated rather than discovered:** `x` is now bounded game state.
 * `world.set_component_field` writing `x = 200` is accepted and the write is real - `/state`
 * shows 200 - and the **next tick** normalises it to 20. A game with a playfield behaves this
 * way; an agent testing the write surface on a paused instance sees the value it wrote, and one
 * that steps afterwards sees the wrap. There is deliberately no clamp-on-write: a barrier
 * mutation that silently rewrote its own argument would be worse.
 *
 * **And the aliasing:** two captures exactly [LAP_TICKS] apart are identical by construction. A
 * `time.rewind` of a whole lap therefore reports zero differing pixels and is not a bug.
 */
public class DriftSystem : SimSystem() {

    private val moving = family { all(Position) }

    override fun onTick() {
        moving.forEach { entity ->
            val position = entity[Position]
            position.x = wrap(position.x + DRIFT_PER_TICK)
        }
    }

    public companion object {

        /** World units per tick. A round number so a reader can check the arithmetic by eye. */
        public const val DRIFT_PER_TICK: Float = 0.25f

        /**
         * The playfield, in world units: `x` is always in `[0, FIELD_WIDTH)` after a tick.
         *
         * Sized to sit inside `MobaScene`'s camera with room on both sides, so a unit at either
         * end of the field is fully drawn rather than half off the edge.
         */
        public const val FIELD_WIDTH: Float = 90f

        /** Ticks for one lap of the field. At the default 60Hz tick rate, six seconds. */
        public const val LAP_TICKS: Int = (FIELD_WIDTH / DRIFT_PER_TICK).toInt()

        /**
         * [x] brought into `[0, FIELD_WIDTH)`.
         *
         * `%` alone is not enough and the difference is reachable: `x` is agent-writable, so
         * `world.set_component_field x = -5` is one HTTP call away, and Kotlin's `%` keeps the
         * sign - which would leave the unit at `-5`, off camera, drifting toward the field from
         * outside it for twenty seconds. Public and pure so `MobaSceneTest` can drive it.
         */
        public fun wrap(x: Float): Float {
            if (!x.isFinite()) return 0f
            val remainder = x % FIELD_WIDTH
            return if (remainder < 0f) remainder + FIELD_WIDTH else remainder
        }
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
