package dev.wildware.udea.assets

/**
 * An asset's slot in the packed graph: the pack-time interned integer of Trello #32.
 *
 * The index — not the [AssetId], and never the [AssetData] — is what a component field holds
 * and therefore what reaches a snapshot (spec 3.6). Three properties follow from that, and all
 * three are why this is an int:
 *
 * - **O(1) resolution.** `registry[ref]` is an array read. The old tree's `AssetRefImpl.value`
 *   was a `by lazy` hash lookup into a global map, on paths that run per entity per tick.
 * - **Cheap in a field store.** One `IntArray` column, no object header, no pointer to chase
 *   while diffing.
 * - **Stable across a hot reload.** A shape-compatible reload swaps values *at the same index*
 *   ([AssetRegistry.applyDelta]), so a snapshot taken before the reload still names the right
 *   asset afterwards — it simply reads the new value, which is the tuning loop issue #64 exists
 *   to protect.
 *
 * A value class rather than a bare `Int` because an asset index, an entity id and a component
 * id are all ints and none of them means anything in the others' place (standards section 1).
 * [Ref] keeps its cached slot as a raw `Int` field instead, so that the unresolved sentinel is
 * a plain `-1` on the hot path; that is the one place in the module allowed to.
 */
@JvmInline
public value class AssetIndex(public val value: Int) {

    init {
        require(value >= 0) { "an AssetIndex is a slot in the packed graph and cannot be negative: $value" }
    }

    override fun toString(): String = value.toString()
}
