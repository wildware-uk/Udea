package dev.wildware.moba.level

import dev.wildware.moba.MobaAssets
import dev.wildware.moba.Player
import dev.wildware.udea.assets.EntityDefinition
import dev.wildware.udea.assets.Level
import dev.wildware.udea.core.RngStream
import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.blueprint.SpawnOverrides
import dev.wildware.udea.core.blueprint.SpawnPosition
import dev.wildware.udea.core.blueprint.SpawnRequest
import dev.wildware.udea.core.blueprint.blueprints
import dev.wildware.udea.core.scene.Scene
import dev.wildware.udea.core.scene.SceneScope
import dev.wildware.udea.generated.GameAssets

/**
 * The ported `test_level`: twenty-seven units, three sides, one clearing each, and a fight.
 *
 * ## What it is a port of
 *
 * `example/src/main/resources/assets/level/test_level.udea.kts` spawned a player-controlled
 * soldier, a priest, five orcs, ten skeletons and ten soldiers, each at
 * `Random.nextFloat()`-scattered offsets around four cluster centres, and registered the six
 * gameplay systems by class. This is the same roster and the same four clusters.
 *
 * Three things about it are different, and each is a rule this engine has that the old one did
 * not:
 *
 * - **The roster is data and the layout is authored.** `assets/level/test_level.udea.kts` names
 *   every entity, its blueprint and its cluster centre; this class reads the packed [Level] out
 *   of the bundle and spawns what it finds. Deleting an entity from that file removes a unit
 *   from the game with no Kotlin recompiled, which is the property `.udeapak` exists for.
 * - **The scatter is a seeded stream, not `kotlin.random`.** The old file called
 *   `Random.nextFloat()` at *pack* time, so two builds of identical sources produced different
 *   layouts (`DeterminismValidator` bans the name outright now, UDEA0034). Here the scatter is
 *   drawn at *spawn* time from [RngStream.Spawn] - a named stream, so a change to how combat
 *   rolls cannot move where anything spawned, and a replay of this level survives an unrelated
 *   gameplay change.
 * - **Systems are not named by a level.** The old `systems = { add(...) }` block let a level
 *   decide the tick order of a simulation. `MobaModule` decides it now, once, for every scene,
 *   which is what makes "all three entry points run the identical Simulation" checkable.
 *
 * ## Where it runs
 *
 * [populate] is called by `BarrierSceneManager` from inside a `BarrierAction`, so it is already
 * at a safe point: the world is cleared, net ids are reset, and there is no iteration in
 * progress. That is why it spawns through `BlueprintSpawner.spawnNow` rather than `spawn` -
 * queueing 27 spawn actions from inside a barrier action would put them a tick later, and the
 * scene would be reported as swapped over an empty world.
 *
 * @param seed carried for [Scene], and **not** what the scatter uses. The scatter comes from the
 *   context's [RngStream.Spawn] stream, which the snapshot ring captures and a rewind restores;
 *   a private generator here would be state no rewind knew about.
 */
public class TestLevelScene(
    override val seed: Long = 0L,
) : Scene {

    override val id: SceneId = ID

    /** Units placed by the most recent [populate]. A health signal for a test, not state. */
    public var spawned: Int = 0
        private set

    override fun populate(scope: SceneScope) {
        val level = level()
        val spawner = scope.ctx.blueprints
        // Off the context, not a singleton: a unit's blueprint is built against one
        // `MobaAbilityModule`'s attribute, effect and ability tables, and those belong to the one
        // definition this scene is being populated into. See `MobaBlueprints.KEY`.
        val blueprints = scope.ctx[MobaBlueprints.KEY]
        val rng = scope.ctx.rng
        var placed = 0
        for (definition in level.entities) {
            val blueprint = blueprints.byAssetId(definition.blueprint?.id)
            // Two draws per entity, always, and always in this order: an entity that took its
            // draws conditionally would make every later unit's position depend on whether an
            // earlier one had an authored position, which is a layout that changes when an
            // unrelated line of the level file changes.
            val jitterX = scatter(rng.nextFloat(RngStream.Spawn))
            val jitterY = scatter(rng.nextFloat(RngStream.Spawn))
            val centre = definition.position
            spawner.spawnNow(
                scope.world,
                SpawnRequest(
                    blueprint = blueprint,
                    position = SpawnPosition(
                        x = (centre?.x ?: 0f) + jitterX,
                        y = (centre?.y ?: 0f) + jitterY,
                    ),
                    // The one entity a human drives. The old level said the same thing with a
                    // `player()` component in its `components` block; it is an override here
                    // because a packed `ComponentSpec` is data no loader turns into a Fleks
                    // component yet, and the level naming the unit is the half that survives.
                    //
                    // It is deliberately *this* unit and not a twenty-eighth one spawned beside
                    // the level: a player standing next to the field rather than in it is a
                    // different game from the one the level describes, and two soldiers both
                    // answering to the camera is a bug that only shows up as "it followed the
                    // wrong one".
                    overrides = if (definition.name == PLAYER_ENTITY) PLAYER else null,
                ),
            )
            placed++
        }
        check(placed > 0) {
            "$ID packed no entities, so the scene swapped onto an empty world; " +
                "`assets/level/test_level.udea.kts` declares the roster and " +
                "`udeaPackBundle` is what puts it in the bundle"
        }
        spawned = placed
    }

    override fun toString(): String = "TestLevelScene($ID, seed=$seed, spawned=$spawned)"

    public companion object {

        /** How the scene is addressed: `scenes.requestScene(TestLevelScene.ID)`. */
        public val ID: SceneId = SceneId("level/test_level")

        /**
         * The authored entity a human drives.
         *
         * A name and not a component in the level file, because a level's `components` block
         * packs as `ComponentSpec` data and nothing turns that into a Fleks component yet. The
         * name is authored, so moving the player to the skeleton camp is still an asset edit.
         */
        public const val PLAYER_ENTITY: String = "player"

        /** What [PLAYER_ENTITY] gets on top of its blueprint. One object, reused per load. */
        private val PLAYER = SpawnOverrides { context, entity ->
            with(context) { entity += Player() }
        }

        /**
         * Half the width of a cluster, in world units.
         *
         * The old level scattered over `spawnDistance = 4F` in a world where a character was
         * about one unit across. A unit sprite here is about fifty world units across, so the
         * same *relative* spread is about forty: tight enough that a cluster reads as a group,
         * loose enough that twenty-seven units are not one stack of sprites.
         */
        public const val SCATTER: Float = 40f

        /** The four cluster centres the authored level places entities at, for tests to name. */
        public const val ORC_CLEARING_X: Float = -50f

        /** @see ORC_CLEARING_X */
        public const val SKELETON_CAMP_X: Float = 100f

        /** @see ORC_CLEARING_X */
        public const val SOLDIER_CAMP_Y: Float = -50f

        /** A `[0,1)` draw mapped onto `[-SCATTER, SCATTER)`. */
        private fun scatter(unit: Float): Float = (unit * 2f - 1f) * SCATTER

        /**
         * The packed level, out of the bundle this process was built with.
         *
         * Read through the generated accessor, so a level renamed in the asset tree fails to
         * compile here rather than failing to load at boot.
         */
        public fun level(): Level = MobaAssets.registry[GameAssets.level.testLevel]

        /** Which blueprint an authored entity names. Exposed for tests that check the roster. */
        public fun blueprintOf(
            blueprints: MobaBlueprints,
            definition: EntityDefinition,
        ): UnitBlueprint = blueprints.byAssetId(definition.blueprint?.id)
    }
}
