package dev.wildware.moba.item

import dev.wildware.moba.ability.MobaAbilityModule
import dev.wildware.moba.lane.ChampionSystem
import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.ReplicatedComponentType
import dev.wildware.udea.core.snapshot.fleksComponentType
import dev.wildware.udea.gas.GasServices

/**
 * The shop and the things it sells.
 *
 * ## Why this is a module of its own
 *
 * `MobaModule` owns what a unit is, `MatchModule` what a session is, `LaneModule` what a lane is,
 * and this owns what a champion is *carrying*. As with the lane, the useful property is that it
 * comes apart: a definition assembled without this one is the game before there was anything to
 * spend gold on, which is what every combat and lane test wants and what a player does not.
 *
 * ## It must come after `LaneModule`
 *
 * [ShopSystem] declares `after(ChampionSystem)`, because a shopper needs the `Wallet` that system
 * grants, and `SimRegistry` can only resolve an edge against a system some module has already
 * contributed. So a definition that lists this module must list `LaneModule` first. That is the
 * same constraint `LaneModule` itself states against `MatchModule`'s `RespawnSystem`, and it is
 * stated here rather than left for a `NoSuchElementException` at world build time to say.
 *
 * ## The catalogue is a constructor parameter
 *
 * `MobaGame` hands in `ItemCatalog.read(MobaAssets.registry)`, and a test hands in one read from
 * a graph it built. It is not a `MobaAssets.registry` call inside this file, for the reason
 * `LaneModule` takes its `MobaAbilityModule`: a module that reaches for a process-wide singleton
 * is a module that cannot be assembled twice differently, and every asset-shaped test in this
 * game would then need the packed bundle on its classpath to construct a world.
 */
public class ItemModule(
    /** Every item this build ships. See [ItemCatalog]. */
    public val catalog: ItemCatalog,
    /**
     * This game's effect table indices and tag vocabulary.
     *
     * The whole `MobaAbilityModule` rather than the three values off it, for the reason the
     * catalogue is a parameter at all: an `AttributeId` and a `GameplayEffectTable` index are
     * indices into *one* table, so a module handed them separately is four chances to build a
     * game whose item bonuses index a different table from the one its units were dressed with.
     * Handing in the object they all came from makes that unreachable rather than unlikely.
     */
    private val combat: MobaAbilityModule,
) : UdeaModule {

    override val name: String get() = "moba-item"

    /**
     * Where orders go in and outcomes come out.
     *
     * One object, constructed here, so the system that drains the queue and the bot or test that
     * fills it cannot be holding two different queues. The authority stays in [Inventory] and
     * `Wallet` on entities; see [ShopService] for why a mirror exists at all.
     */
    public val service: ShopService = ShopService()

    override fun context(builder: GameContextBuilder) {
        builder.service(ShopService.KEY, service)
    }

    override fun simulation(registry: SimRegistry) {
        registry.add(SimPhase.Gameplay, { InventoryGrantSystem() })
        registry.add(SimPhase.Gameplay, { ShopSystem(catalog, service) }) {
            after(ChampionSystem::class)
            after(InventoryGrantSystem::class)
        }
        // Both `after(ShopSystem)`, so a purchase made on this tick is on the champion's stats and
        // on its ability bar on this tick rather than on the next. Both are reconcilers - they ask
        // what the inventory says rather than what the shop did - so the edge is about latency and
        // not about correctness: without it they would be one tick behind, and never wrong.
        registry.add(
            SimPhase.Gameplay,
            { ctx ->
                ItemPassiveSystem(
                    catalog = catalog,
                    effects = combat.effects,
                    applier = ctx[GasServices.KEY].applier,
                    magnitudeTag = combat.tags.dataItemStat,
                )
            },
        ) { after(ShopSystem::class) }
        registry.add(
            SimPhase.Gameplay,
            { ctx ->
                val gas = ctx[GasServices.KEY]
                ItemActiveSystem(catalog, gas.activation, gas.abilities)
            },
        ) { after(ShopSystem::class) }
    }

    override fun toString(): String = "ItemModule($catalog, $service)"

    /**
     * The snapshot registry entry for this package's one component.
     *
     * Handed to `MobaGame.componentRegistry` rather than written out there, so adding a component
     * to the shop is one edit in the package that owns it - the arrangement `LaneModule` uses and
     * states at length. An unregistered component is not partly captured, it is **invisible** to
     * capture, and an inventory a rewind did not restore would be a champion whose gold came back
     * and whose items did not.
     *
     * The `FieldKind` list must agree with the replicator **field for field in the replicator's
     * own order, which is alphabetical by field name and not declaration order**. For `Inventory`
     * that is `slot0`, `slot1`, `slot2`, `slot3`, `slot4`, `slot5`, `trinket` - which is also
     * declaration order here, because the names were chosen so the two cannot diverge - and every
     * one of the seven is an `Int` holding an `AssetIndex` value or [Inventory.EMPTY].
     *
     * `ComponentSchema.of` refuses a list whose length disagrees with `fieldNames`, so an eighth
     * slot fails here rather than silently shifting a column.
     */
    public companion object {

        /** @see ItemModule.Companion */
        public fun snapshotTypes(): List<ReplicatedComponentType<*>> = listOf(
            fleksComponentType(
                InventoryReplicator,
                ComponentSchema.of(
                    InventoryReplicator,
                    "Inventory",
                    List(Inventory.CAPACITY) { FieldKind.Int },
                ),
                Inventory,
            ) { Inventory() },
        )
    }
}
