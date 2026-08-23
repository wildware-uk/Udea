package dev.wildware.udea.assets

/**
 * The id-to-slot assignment of one packed graph: the interning space an [AssetIndex] lives in.
 *
 * Separate from [AssetRegistry] because the *values* in a registry change on a hot reload while
 * the layout does not - that is the whole distinction between a reload that can be applied in
 * place and one that cannot ([AssetRegistry.classify]). A [Ref] caches the layout it interned
 * against, by identity, so:
 *
 * - a shape-compatible reload keeps the same layout instance, and every cached slot in every
 *   component in the world stays valid, which is what makes an [AssetIndex] inside a snapshot
 *   still meaningful after a reload (spec 3.6);
 * - a new pack means a new layout instance, so every cached slot misses and re-resolves instead of
 *   reading a stale slot in a graph that no longer has the same shape.
 *
 * Identity, not a content hash: a hash collision here would be a silently wrong asset. Two
 * registries built from the same bytes therefore get two layouts and do not share cached slots,
 * which costs one hash lookup per reference per registry and buys the guarantee outright.
 */
internal class AssetLayout(values: Array<AssetData>) {

    /**
     * Keyed by the raw string: [AssetId] is a value class, so a `HashMap<AssetId, Int>` would box
     * every key. Built once per pack, read on every unresolved lookup.
     */
    private val slotById: HashMap<String, Int> = HashMap(values.size * 2)

    /** Every id in slot order. The did-you-mean candidate list, and what `AssetRegistry.ids` is. */
    val ids: List<AssetId> = values.map { it.id }

    init {
        values.forEachIndexed { slot, asset ->
            val previous = slotById.put(asset.id.value, slot)
            if (previous != null) throw DuplicateAssetIdException(asset.id, previous, slot)
        }
    }

    /** The slot [id] occupies, or [NO_SLOT]. */
    fun slotOf(id: AssetId): Int = slotById[id.value] ?: NO_SLOT

    val size: Int get() = ids.size

    companion object {
        /** No asset by that id. Not an [AssetIndex]: there is no negative index. */
        const val NO_SLOT: Int = -1
    }
}
