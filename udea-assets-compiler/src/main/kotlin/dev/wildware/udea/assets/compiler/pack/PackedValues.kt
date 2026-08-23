package dev.wildware.udea.assets.compiler.pack

import dev.wildware.udea.assets.AssetData
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.compiler.AssetGraph
import dev.wildware.udea.assets.pack.BundleReader
import dev.wildware.udea.diagnostics.UdeaDiagnostic

/**
 * A compiled graph as the [AssetData] values a running game holds.
 *
 * ## This is a composition, not a packer
 *
 * There used to be a second packer here - `daemon/AssetPacker`, a `when (asset.kind)` over six
 * DSL words building `SpriteSheet`, `SoundCue`, `Blueprint` and the rest by hand. It existed
 * because a hot reload needs a *value* and [GraphPacker] produces *records*, and it was exactly
 * the defect this rewrite exists to kill: two implementations of "what does
 * `spriteSheet(spritePath = ...)` mean", which drifted the moment one of them learned a field
 * name the other did not. `gameConfig` was mapped by one and refused by the other.
 *
 * So this maps records to values the only way that cannot drift: it runs the **real** writer and
 * the **real** reader. [GraphPacker] to records, [BundleWriter] to bytes, [BundleReader] back to
 * an `AssetRegistry`. Every rename, every default and every codec is consulted once, in the code
 * that ships.
 *
 * ## What it costs, and why that is affordable
 *
 * One serialise and one parse of the whole graph per call, against a hand-written `when` that
 * touched one asset. Measured on the daemon's own corpus that is under a millisecond, and the
 * daemon calls it on `start` and on `commit` - never on the 300ms-gated `validate` path. The
 * alternative saved that millisecond by keeping a second definition of the asset model, which
 * is a much worse trade than it looks: an id whose bundle value and whose daemon value differ
 * is a game that behaves one way from a build and another after a reload.
 *
 * ## A bonus that is load-bearing
 *
 * The values come back keyed by the **bundle's own slot order**, because that is what the
 * registry is. A daemon and a shipped `.udeapak` built from the same corpus therefore agree on
 * every [dev.wildware.udea.assets.AssetIndex] by construction - which is what lets
 * `AssetRegistry.applyDelta` swap a value into a registry that was loaded from a file rather
 * than built by the daemon.
 */
public object PackedValues {

    /** The values and whatever packing reported, or the diagnostics alone when it reported an error. */
    public data class Result(
        public val values: Map<AssetId, AssetData>,
        public val diagnostics: List<UdeaDiagnostic>,
    )

    /**
     * [graph] as runtime values.
     *
     * A packing error yields no values at all rather than a partial map: half a graph is a game
     * with a reference that resolves to nothing, and the caller's own error path is a better
     * place to decide what to do than a silently short map.
     */
    public fun of(graph: AssetGraph): Result {
        val packed = GraphPacker.pack(graph)
        if (packed.hasErrors) return Result(emptyMap(), packed.diagnostics)
        // No atlas and no blobs: this is the graph's *values*, and a page of pixels changes none
        // of them. Packing one would make every daemon start decode the whole art set.
        val bytes = BundleWriter.write(BundleContent(assets = packed.assets))
        BundleReader.open(bytes).use { bundle ->
            val registry = bundle.registry
            val values = LinkedHashMap<AssetId, AssetData>(registry.size * 2)
            for (id in registry.ids) values[id] = registry.at(registry.indexOf(id))
            return Result(values, packed.diagnostics)
        }
    }
}
