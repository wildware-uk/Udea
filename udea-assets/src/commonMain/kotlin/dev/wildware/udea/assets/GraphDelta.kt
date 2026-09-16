package dev.wildware.udea.assets

/**
 * One asset's new value in a hot reload, or its removal.
 *
 * [data] is `null` when the asset is gone from the recompiled graph. That case is not a variant
 * nobody will hit: deleting a `.udea.kts` declaration is one keystroke, and it is exactly the
 * shape-changing case the runtime must refuse to apply in place rather than quietly leaving a
 * dead slot behind.
 */
public data class ChangedAsset(
    public val id: AssetId,
    /** The recompiled value, or `null` if the asset was removed. */
    public val data: AssetData?,
) {
    init {
        require(data == null || data.id == id) {
            "ChangedAsset('$id') carries data declaring itself '${data?.id}'; a delta entry must " +
                "not rename the asset it changes"
        }
    }
}

/**
 * What the asset compiler produced for one recompile: every asset whose value differs from the
 * graph the runtime is holding.
 *
 * Ordered and deduplicated, because it is applied by index and reported to an agent: two entries
 * for one id would make "what changed" depend on which one was applied last.
 */
public data class GraphDelta(public val changed: List<ChangedAsset>) {

    init {
        require(changed.isNotEmpty()) { "a GraphDelta with no changes is not a delta" }
        val duplicates = changed.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "a GraphDelta names the same asset twice: $duplicates" }
    }

    /** The ids this delta touches, in delta order. */
    public val ids: List<AssetId> get() = changed.map { it.id }
}

/** Why a delta cannot be applied to a running graph in place. */
public enum class RestartReason(public val code: String) {

    /** The recompiled graph has an asset the running one does not, so the layout is different. */
    AssetAdded("asset_added"),

    /** The asset is gone. Its slot would have to stay occupied by something, and nothing fits. */
    AssetRemoved("asset_removed"),

    /**
     * The id still exists but is now a different kind of asset. Every [Ref] to it type-checked
     * against the old kind, and every one of those checks is now wrong.
     */
    KindChanged("asset_kind_changed"),
}

/** One reason one asset in a delta forces a restart. */
public data class ShapeChange(public val id: AssetId, public val reason: RestartReason)

/**
 * Whether a [GraphDelta] can be swapped into a live graph.
 *
 * The distinction spec 3.6 rests on. A [HotSwappable] delta changes values at fixed slots, so
 * every [AssetIndex] in the world - including the ones inside snapshots taken before the reload -
 * still names the right asset, and rewinding across the reload shows the *new* numbers. A
 * [RequiresRestart] delta changes the shape of the graph itself, at which point an index recorded
 * before it means nothing, and both the reload and any rewind across it must refuse.
 */
public sealed interface DeltaClassification {

    /** Values only. Apply in place; cached slots and snapshot indices stay valid. */
    public data object HotSwappable : DeltaClassification

    /** The graph's shape changes. Nothing is applied and the world is left alone. */
    public data class RequiresRestart(public val changes: List<ShapeChange>) : DeltaClassification {
        init {
            require(changes.isNotEmpty()) { "RequiresRestart must name at least one shape change" }
        }

        public companion object {
            /**
             * The stable code this classification carries into an MCP tool result - the same
             * string `TimeControl.rewind` refuses a cross-reload rewind with (issue #64), so the
             * daemon, the registry and the rewind path cannot drift into three spellings.
             */
            public const val CODE: String = "reload_requires_restart"
        }
    }
}

/** What [AssetRegistry.applyDelta] did. */
public sealed interface DeltaResult {

    /** The values are in the graph, and the graph is now at [version]. */
    public data class Applied(
        public val version: Int,
        public val changedIds: Set<AssetId>,
    ) : DeltaResult

    /**
     * Nothing was applied; the registry holds exactly what it held before the call.
     *
     * Whole-delta, not per-asset: applying the hot-swappable half of a delta that also adds an
     * asset would leave the running graph in a state no build ever produced.
     */
    public data class Refused(
        public val classification: DeltaClassification.RequiresRestart,
    ) : DeltaResult
}
