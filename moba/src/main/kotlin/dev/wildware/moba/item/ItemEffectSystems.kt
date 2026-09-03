package dev.wildware.moba.item

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import dev.wildware.moba.ability.MobaEffects
import dev.wildware.moba.ability.UnitBlueprint
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.gas.Abilities
import dev.wildware.udea.gas.AbilityActivation
import dev.wildware.udea.gas.AbilityTable
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.gas.EffectApplier
import dev.wildware.udea.gas.GameplayEffects
import dev.wildware.udea.gas.GameplayTag

/**
 * What carrying an item does to a champion's stats, and what carrying two of one unique does not.
 *
 * ## The two kinds of bonus, and why they behave differently
 *
 * - **`stats`**, which stack. Every carried item's flat bonuses are summed per attribute and
 *   applied as one `item/stat_*` effect per attribute. Two strength items move one number rather
 *   than adding a second application beside the first, so however full the inventory, this system
 *   holds at most one stat application per entry of [MobaEffects.ITEM_STATS].
 * - **`passive`**, which an item's `unique` group deduplicates. An item declaring a `unique`
 *   contributes its named passive only if no *lower* inventory slot holds an item of the same
 *   group, so `item/bulwark` and `item/sentinel_greaves` - both `unique/fortified` - grant one
 *   `item/passive_fortified` between them. An item with a `passive` and no `unique` contributes
 *   one per copy, because there is no group to deduplicate it by.
 *
 * That split is what the genre means by "unique passive": the stats on two similar items add up
 * and the named bonus does not. Deduplicating the stats too would make the second item of a group
 * worth nothing at all, which is a different design and not the one the field name describes.
 *
 * ## Why it reconciles instead of reacting to a purchase
 *
 * [ShopSystem] could apply an effect on the tick it moves an item into a slot, and then every
 * other writer of an [Inventory] would have to remember to do the same: a rewind that restores an
 * older inventory, an agent's `world.set_component_field`, a match restart. This asks the question
 * the other way round - *given what is in the inventory right now, what should be applied?* - so
 * there is one place to be wrong, it is idempotent, and a `time.rewind` needs nothing from it. The
 * restored world's next tick reconciles whatever the restore left behind.
 *
 * ## Where the handles are, and why they are nowhere
 *
 * An applied effect's [dev.wildware.udea.gas.EffectHandle] is not stored on a component. It could
 * not be: a handle on a component is a field no replicator carries, so a rewind would restore a
 * champion whose bonuses this system could no longer find and could never remove. The effects it
 * owns are *found* by their def index instead - the same arrangement `DeathTagSystem` uses, for
 * the same reason.
 *
 * The set it owns is exactly [MobaEffects.ITEM_STATS] and [MobaEffects.ITEM_PASSIVES], and nothing
 * else applies one of those. That boundary is why an item's `passive` may only name an effect in
 * [MobaEffects.ITEM_PASSIVES], enforced at construction: `ability/passive_health_regen` is applied
 * to AI units by `UnitBrain`, and a system that removed applications it did not make would fight
 * that one over the same champion every tick.
 *
 * ## Ordering
 *
 * `SimPhase.Gameplay`, `after(ShopSystem)`, so a purchase made on this tick is reflected on this
 * tick rather than on the next. `SimPhase.Attribute` runs after `Gameplay`, so the recompute that
 * folds these modifiers into `current` sees this tick's set.
 */
public class ItemPassiveSystem(
    /** Every item this build ships. Read once at load; see [ItemCatalog]. */
    private val catalog: ItemCatalog,
    /** This game's effect table indices: which effect carries which stat, and what each is worth. */
    private val effects: MobaEffects,
    /** Applies an effect, and releases the handle when one is removed. */
    private val applier: EffectApplier,
    /** The set-by-caller key every one of these effects reads its magnitude from. */
    private val magnitudeTag: GameplayTag,
) : SimSystem() {

    private val champions: Family = world.family { all(Inventory, Attributes, GameplayEffects) }

    /** The effect table index of each entry of [MobaEffects.ITEM_STATS], in that order. */
    private val statEffects: IntArray = IntArray(MobaEffects.ITEM_STATS.size) { index ->
        effects.itemStatByName.getValue(MobaEffects.ITEM_STATS[index].stat)
    }

    /** The effect table index of each entry of [MobaEffects.ITEM_PASSIVES], in that order. */
    private val passiveEffects: IntArray = IntArray(MobaEffects.ITEM_PASSIVES.size) { index ->
        effects.table.indexOf(MobaEffects.ITEM_PASSIVES[index].effect)
    }

    /**
     * The desired total for each entry of [statEffects], rebuilt per champion per tick.
     *
     * A dense array rather than a map, so a reconcile allocates nothing. Scratch: every entry is
     * written before it is read.
     */
    private val statTotals = FloatArray(MobaEffects.ITEM_STATS.size)

    /** How many applications each entry of [passiveEffects] should have. Scratch, as above. */
    private val passiveWanted = IntArray(MobaEffects.ITEM_PASSIVES.size)

    init {
        // Loud at world-build time rather than per tick: an item whose `passive` names an effect
        // outside `ITEM_PASSIVES` is a bonus a player pays for and never gets, and it would be
        // found by a player rather than by a build. Adding one is a row in `ITEM_PASSIVES` and a
        // `gameplayEffect(...)` in `assets/item/stats.udea.kts`.
        for (entry in catalog.entries) {
            val passive = entry.item.passive?.id?.value ?: continue
            require(MobaEffects.ITEM_PASSIVES.any { it.effect == passive }) {
                "item '${entry.item.id}' names passive '$passive', which is not one of " +
                    "${MobaEffects.ITEM_PASSIVES.map { it.effect }}. ItemPassiveSystem owns only " +
                    "those, because it removes what it does not want and must not remove an " +
                    "application some other system made."
            }
        }
    }

    /** Stat and passive applications this system has made. A signal for a test, not state. */
    public var applied: Long = 0L
        private set

    /** Applications it has removed, because what they were for is no longer carried. */
    public var removed: Long = 0L
        private set

    override fun onTick() {
        val entities = champions.entities
        var index = 0
        while (index < entities.size) {
            reconcile(entities[index])
            index++
        }
    }

    private fun reconcile(entity: Entity) = with(world) {
        val attributes = entity[Attributes]
        val applications = entity[GameplayEffects]
        readInventory(entity[Inventory])
        reconcileStats(attributes, applications)
        reconcilePassives(attributes, applications)
    }

    /**
     * Fills [statTotals] and [passiveWanted] from what is in [inventory].
     *
     * Slot order, ascending, and that is the deduplication rule: the **lowest** slot holding an
     * item of a unique group is the one that contributes its passive. A rule is needed because two
     * items in one group may name different passives, and "whichever the iteration reached first"
     * would make a champion's stats depend on the order a shop happened to fill its slots. Slot
     * order is what [Inventory] already guarantees and what [ShopSystem] already fills densely.
     */
    private fun readInventory(inventory: Inventory) {
        java.util.Arrays.fill(statTotals, 0f)
        java.util.Arrays.fill(passiveWanted, 0)
        var slot = 0
        while (slot < Inventory.CAPACITY) {
            val entry = catalog.at(inventory, slot)
            if (entry != null) {
                addStats(entry)
                addPassive(entry, inventory, slot)
            }
            slot++
        }
    }

    private fun addStats(entry: ItemEntry) {
        for ((name, amount) in entry.item.stats) {
            val position = MobaEffects.ITEM_STATS.indexOfFirst { it.stat == name }
            // Loud rather than dropped, for `init`'s reason. Here rather than in `init` because
            // `stats` is a map and its keys are what an author gets wrong; a catalogue-wide sweep
            // would say the same thing, and this says it naming the champion that is carrying it.
            require(position >= 0) {
                "item '${entry.item.id}' grants '$name', which is not one of the stats this game " +
                    "can apply: ${MobaEffects.ITEM_STATS.map { it.stat }}"
            }
            statTotals[position] += amount
        }
    }

    /** Records [entry]'s passive unless a lower slot already holds its unique group. */
    private fun addPassive(entry: ItemEntry, inventory: Inventory, slot: Int) {
        val passive = entry.item.passive?.id?.value ?: return
        val unique = entry.item.unique?.value
        if (unique != null && groupHeldBelow(inventory, slot, unique)) return
        val position = MobaEffects.ITEM_PASSIVES.indexOfFirst { it.effect == passive }
        // `init` has already refused a catalogue that could reach this, so a miss here would be a
        // disagreement between the two - a defect in this file rather than a state a player can
        // get into, and it fails loudly rather than granting nothing.
        check(position >= 0) { "no ITEM_PASSIVES row for '$passive' after the catalogue check" }
        passiveWanted[position]++
    }

    /** Whether any slot below [slot] holds an item whose unique group is [unique]. */
    private fun groupHeldBelow(inventory: Inventory, slot: Int, unique: String): Boolean {
        var earlier = 0
        while (earlier < slot) {
            if (catalog.at(inventory, earlier)?.item?.unique?.value == unique) return true
            earlier++
        }
        return false
    }

    /**
     * Makes the applied `item/stat_*` effects say what [statTotals] says.
     *
     * A total of zero means no application at all rather than an application of nothing, so a
     * champion carrying no armour item holds no armour effect and an idle champion's effect list
     * stays short.
     */
    private fun reconcileStats(attributes: Attributes, applications: GameplayEffects) {
        var position = 0
        while (position < statTotals.size) {
            val total = statTotals[position]
            setCount(
                applications = applications,
                attributes = attributes,
                defIndex = statEffects[position],
                wanted = if (total == 0f) 0 else 1,
                magnitude = total,
            )
            position++
        }
    }

    /**
     * Makes the applied item passives say what [passiveWanted] says.
     *
     * Walks every row of [MobaEffects.ITEM_PASSIVES] rather than only the wanted ones, because a
     * passive that has fallen out of the inventory entirely has a wanted count of zero and would
     * otherwise never be reached - which is exactly the case a sale produces.
     */
    private fun reconcilePassives(attributes: Attributes, applications: GameplayEffects) {
        var position = 0
        while (position < passiveWanted.size) {
            setCount(
                applications = applications,
                attributes = attributes,
                defIndex = passiveEffects[position],
                wanted = passiveWanted[position],
                magnitude = MobaEffects.ITEM_PASSIVES[position].amount,
            )
            position++
        }
    }

    /**
     * Brings the applications of [defIndex] to exactly [wanted], each carrying [magnitude].
     *
     * A magnitude that has changed is a remove and a re-apply, because a staged magnitude is
     * written at application time and there is no API that rewrites one in place. The fresh handle
     * that produces is correct rather than merely harmless: the modifier sort key is
     * `(type, attribute, handle)`, so the order stays total and the recompute stays a pure
     * function of `(base, effect list)`.
     */
    private fun setCount(
        applications: GameplayEffects,
        attributes: Attributes,
        defIndex: Int,
        wanted: Int,
        magnitude: Float,
    ) {
        var slot = applications.count - 1
        var held = 0
        // Backwards: `removeAt` compacts, so walking forwards would skip whatever moved into the
        // slot just vacated.
        while (slot >= 0) {
            if (applications.defIndexAt(slot) == defIndex) {
                val stale = applications.magnitudeAt(slot, magnitudeTag) != magnitude
                if (held >= wanted || stale) {
                    applier.remove(applications, applications.handleAt(slot))
                    removed++
                } else {
                    held++
                }
            }
            slot--
        }
        while (held < wanted) {
            applier.begin(defIndex)
                .magnitude(magnitudeTag, magnitude)
                .applyTo(applications, attributes, tick)
            applied++
            held++
        }
    }

    override fun toString(): String = "ItemPassiveSystem(applied=$applied, removed=$removed)"
}

/**
 * Puts the actives of the items a champion is carrying onto its ability bar.
 *
 * ## The bar
 *
 * `UnitBlueprint.dress` gives every unit [UnitBlueprint.ABILITY_SLOTS] slots: two its kind fills,
 * and [UnitBlueprint.ITEM_SLOTS] above them that only this system writes. They are bound to
 * `MobaControls.ITEM_1` and `ITEM_2`, so an active is cast with a key press through the same
 * `IntentSource` seam the sword is - not a shop click, and not a second input path.
 *
 * Those slots share one cooldown ([UnitBlueprint.ITEM_COOLDOWN_SHARING]) that a champion's own two
 * are not part of, so firing an item does not spend a champion's abilities and firing an ability
 * does not spend the item. That is the whole of "shared item-cooldown slot".
 *
 * ## Why it grants through [AbilityActivation] rather than through [Abilities.grant]
 *
 * [Abilities.grant] resets the instance, which clears the cooldown handle on it. On a shared bar
 * that is a hole a player finds in one match: fire the first active, then *buy* a second, and the
 * new slot arrives with no cooldown on it at all. [AbilityActivation.grant] adopts whatever the
 * group is already serving, so an active bought into a cooling bar waits out the rest of it.
 *
 * ## Which items, in which slots
 *
 * Inventory order, ascending, filling item slots from [UnitBlueprint.ITEM_SLOT_FIRST]. A champion
 * carrying more actives than the bar has room for casts the ones in its lowest inventory slots and
 * the rest are inert until one is sold - stated rather than hidden, because the alternative is a
 * key that does nothing and no way to see why.
 *
 * ## Dying does not revoke
 *
 * A corpse keeps its granted actives and is refused by the `Debuffs.Dead` tag every ability in
 * `MobaAbilities` names in `blockedBy`, rather than by having its bar emptied. Revoking would
 * reset the instances, and a champion would then get a free item-cooldown reset every time it
 * died.
 */
public class ItemActiveSystem(
    /** Every item this build ships. */
    private val catalog: ItemCatalog,
    /** Grants, and gates activation. The same object every activation path in this game uses. */
    private val activation: AbilityActivation,
    /** Every ability definition, for turning an item's `grantedAbility` id into an index. */
    private val abilityTable: AbilityTable,
) : SimSystem() {

    private val champions: Family = world.family { all(Inventory, Abilities, GameplayEffects) }

    /** The ability index wanted in each item slot this tick, or `-1`. Scratch; never allocated. */
    private val wanted = IntArray(UnitBlueprint.ITEM_SLOTS)

    /** Actives granted since this world was built. A signal for a test, not state. */
    public var grants: Long = 0L
        private set

    /** Slots emptied because what filled them is no longer carried. */
    public var revocations: Long = 0L
        private set

    override fun onTick() {
        val entities = champions.entities
        var index = 0
        while (index < entities.size) {
            reconcile(entities[index])
            index++
        }
    }

    private fun reconcile(entity: Entity) = with(world) {
        val abilities = entity[Abilities]
        val effects = entity[GameplayEffects]
        readInventory(entity[Inventory])
        var position = 0
        while (position < wanted.size) {
            val slot = UnitBlueprint.ITEM_SLOT_FIRST + position
            if (slot < abilities.slotCount) apply(abilities, effects, slot, wanted[position])
            position++
        }
    }

    /** Fills [wanted] with the actives of the first carried items that declare one. */
    private fun readInventory(inventory: Inventory) {
        java.util.Arrays.fill(wanted, -1)
        var found = 0
        var slot = 0
        while (slot < Inventory.CAPACITY && found < wanted.size) {
            val ability = catalog.at(inventory, slot)?.item?.grantedAbility
            if (ability != null) {
                wanted[found] = abilityTable.indexOf(ability.id.value)
                found++
            }
            slot++
        }
    }

    /** Grants, revokes or leaves [slot] alone, so that it holds [abilityIndex]. */
    private fun apply(abilities: Abilities, effects: GameplayEffects, slot: Int, abilityIndex: Int) {
        val instance = abilities.instanceAt(slot)
        val held = if (instance.isGranted) instance.abilityIndex else -1
        if (held == abilityIndex) return
        // An activation in flight is left to finish. Taking the ability out from under a running
        // `onTick` would leave `AbilityActivation` advancing an instance whose definition had
        // changed underneath it, which is a cast that lands as somebody else's ability.
        if (instance.isActive) return
        if (abilityIndex < 0) {
            abilities.revoke(slot)
            revocations++
            return
        }
        activation.grant(abilities, effects, slot, abilityIndex, tick)
        grants++
    }

    override fun toString(): String = "ItemActiveSystem(granted=$grants, revoked=$revocations)"
}
