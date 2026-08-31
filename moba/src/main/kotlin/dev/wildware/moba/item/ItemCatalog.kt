package dev.wildware.moba.item

import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.AssetIndex
import dev.wildware.udea.assets.AssetRegistry
import dev.wildware.udea.assets.Item

/**
 * One item, with everything the shop needs about it already resolved.
 *
 * [componentIndices] is the recipe as slot numbers rather than as `Ref`s, because that is the
 * form an [Inventory] holds: a purchase compares a component against what is in a slot, and
 * comparing an id string per slot per component per purchase would be the "linear scans as
 * lookups" smell with strings in it.
 *
 * It is resolved once, when the catalogue is read, and never again. Resolving at purchase time
 * would work too and would be wrong for a different reason: `Ref.resolvedIndex` is null until
 * something resolves it, so a shop that read it directly would be correct off a `.udeapak` and
 * silently broken against a hand-built graph, which is exactly the kind of "works in the game,
 * not in the test" gap this engine's fixtures exist to close.
 */
public class ItemEntry internal constructor(
    /** The authored item. */
    public val item: Item,
    /** Where it sits in the packed graph. What an [Inventory] slot stores. */
    public val index: AssetIndex,
    /** Its direct components' slots, in recipe order. Empty for a basic item. */
    internal val componentIndices: IntArray,
) {
    /** How many direct components it is built from. */
    public val componentCount: Int get() = componentIndices.size

    override fun toString(): String = "ItemEntry(${item.id}, ${item.cost}g, $componentCount parts)"
}

/**
 * Every shop item this build ships, indexed by the slot an inventory stores.
 *
 * ## Why it is read out of the bundle and not written out in Kotlin
 *
 * `MobaEffects` and `MobaAbilities` build their tables in Kotlin and `MobaAuthoredContentTest`
 * compares them against the authored files, because a `GameplayEffectDef` holds an interned
 * `AttributeId`, a `TagSet` and cue ids that only a running game can produce. An [Item] has none
 * of that: every field on it is a number, a name or a `Ref`, so it decodes out of the `.udeapak`
 * complete. A second copy in Kotlin would re-create the unchecked duplication that test exists to
 * close, and there would be nothing left for the copy to add.
 *
 * ## The index is the identity
 *
 * An [Inventory] slot holds an [AssetIndex] value, because that is the only asset identity a
 * snapshot may carry (`Ref.resolvedIndex`). So the lookup a shop and a rewind both need is index
 * to item, and it is an array read: [byIndex] is dense over the packed graph with a null at every
 * slot that is not an item.
 *
 * The array is as long as the whole graph rather than as long as the item list, which trades a
 * few hundred null references for the absence of a second numbering. A compact table would need
 * an index of its own, and an inventory would then hold a number that means nothing to
 * `AssetRegistry` - so a rewind across a hot reload would restore an item by a position in a list
 * the reload had rebuilt.
 */
public class ItemCatalog private constructor(
    private val byIndex: Array<ItemEntry?>,
    /** Every item, in asset-index order. Deterministic: a shop, a bot and a test all walk it. */
    public val entries: List<ItemEntry>,
) {

    /** How many items this build ships. */
    public val size: Int get() = entries.size

    /** The entry at [index], or null when that slot is not an item. O(1). */
    public fun at(index: AssetIndex): ItemEntry? =
        if (index.value in byIndex.indices) byIndex[index.value] else null

    /** The entry for the raw slot value an [Inventory] holds, or null. */
    public fun atRaw(raw: Int): ItemEntry? =
        if (raw in byIndex.indices) byIndex[raw] else null

    /** The entry in [slot] of [inventory], or null when the slot is empty. */
    public fun at(inventory: Inventory, slot: Int): ItemEntry? = atRaw(inventory.rawAt(slot))

    /**
     * The entry [id] names, or null when nothing of that id is an item.
     *
     * The lookup an *order* uses, because a bot, a tool or a test names an item rather than
     * numbering it. Deliberately not on a per-tick path: this runs once per purchase, and
     * everything after it works in indices.
     */
    public fun find(id: AssetId): ItemEntry? = byId[id.value]

    private val byId: Map<String, ItemEntry> = entries.associateBy { it.item.id.value }

    override fun toString(): String = "ItemCatalog(${entries.size} items)"

    public companion object {

        /** A catalogue with nothing in it: what a definition assembled without an item tree gets. */
        public val EMPTY: ItemCatalog = ItemCatalog(emptyArray(), emptyList())

        /**
         * Reads every [Item] out of [registry].
         *
         * Walks `registry.ids`, which is slot order, so [entries] is deterministic. A graph with
         * no items yields [EMPTY] rather than a failure: a definition assembled without an item
         * tree is a legitimate build, and the shop then refuses every order by naming the item it
         * could not find rather than by failing to start.
         */
        public fun read(registry: AssetRegistry): ItemCatalog {
            val byIndex = arrayOfNulls<ItemEntry>(registry.size)
            val entries = ArrayList<ItemEntry>()
            for (id in registry.ids) {
                val item = registry.find(id) as? Item ?: continue
                val index = registry.indexOf(id)
                val components = IntArray(item.components.size) { registry.indexOf(item.components[it].id).value }
                val entry = ItemEntry(item, index, components)
                byIndex[index.value] = entry
                entries += entry
            }
            return ItemCatalog(byIndex, entries)
        }
    }
}
