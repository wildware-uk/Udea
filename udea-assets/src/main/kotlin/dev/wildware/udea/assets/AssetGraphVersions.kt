package dev.wildware.udea.assets

/**
 * What changed in the asset graph between two versions, and whether the graph's *shape* changed.
 *
 * The answer `TimeControl.rewind` needs (issue #64): a rewind across a value-only reload succeeds
 * and reports [changedIds], so an agent knows the fireball it is watching now does 40 damage
 * instead of 30, while a rewind across a shape change is refused, because the [AssetIndex] ints
 * in the snapshot no longer name the same assets.
 */
public data class AssetChangeSet(
    /** Ids changed since the version asked about, sorted, so two runs report the same order. */
    public val changedIds: Set<AssetId>,
    /** True when a shape-changing reload happened in the interval. See [DeltaClassification]. */
    public val requiresRestart: Boolean,
) {
    /** True when anything at all happened in the interval. */
    public val changed: Boolean get() = requiresRestart || changedIds.isNotEmpty()

    public companion object {
        /** Nothing happened. What a graph with no hot reload answers, forever. */
        public val None: AssetChangeSet = AssetChangeSet(emptySet(), requiresRestart = false)
    }
}

/**
 * A monotonic version counter over the asset graph, and what changed since any past version.
 *
 * Deliberately narrow, and deliberately not [AssetRegistry] itself: `udea-core` has to record a
 * version on every snapshot and compare two of them at rewind time without depending on the asset
 * daemon or on this module's whole model. Two ints and a change set is the entire dependency, and
 * it is faked in a test in four lines - which is what lets issue #64 land before the daemon does.
 */
public interface AssetGraphVersions {

    /** The graph's current version. `0` before anything has been reloaded, and never decreases. */
    public fun current(): Int

    /**
     * Everything that changed after [version].
     *
     * `changesSince(current())` is always [AssetChangeSet.None]. A version this counter has never
     * issued is a programming error - a snapshot from a different graph - and throws rather than
     * guessing.
     */
    public fun changesSince(version: Int): AssetChangeSet

    public companion object {
        /**
         * A graph that never changes: a build-time-only asset pipeline, a dedicated server, and
         * every test that is not about hot reload. Complete rather than a stub - `current()` is
         * genuinely always `0` when nothing can reload.
         */
        public val Static: AssetGraphVersions = object : AssetGraphVersions {
            override fun current(): Int = 0

            override fun changesSince(version: Int): AssetChangeSet {
                require(version == 0) {
                    "version $version was never issued by a static asset graph, whose only " +
                        "version is 0"
                }
                return AssetChangeSet.None
            }
        }
    }
}

/**
 * The recording [AssetGraphVersions]: one int per asset that ever changed, plus one for the last
 * shape change.
 *
 * ## Why not a log of deltas
 *
 * A dev session hot-reloads for hours, and a rewind may ask about a version from the start of it.
 * A ring of recent deltas answers recent questions exactly and old ones not at all, which is the
 * kind of "mostly right" a rewind cannot use. Recording *when each id last changed* answers any
 * interval exactly, forever, in memory proportional to the number of assets that ever changed
 * rather than to the number of reloads.
 *
 * ## Why it outlives the registry
 *
 * A shape-changing reload replaces the whole [AssetRegistry] rather than mutating it, and the fact
 * that it happened has to survive that - otherwise the new registry reports version 0 and a rewind
 * into the old graph looks safe. So the host owns the log and hands it to each registry it builds;
 * [recordReplacement] is what a swap calls.
 *
 * Not thread-safe: reloads are applied on the `SimBarrier` between ticks (spec 3.4), which is a
 * single thread by construction.
 */
public class AssetGraphLog : AssetGraphVersions {

    private var version: Int = 0
    private val lastChangedAt = LinkedHashMap<AssetId, Int>()
    private var lastShapeChangeAt: Int = NEVER

    override fun current(): Int = version

    override fun changesSince(version: Int): AssetChangeSet {
        require(version in 0..this.version) {
            "version $version was never issued by this graph (current is ${this.version}); a " +
                "snapshot taken against a different asset graph cannot be compared with this one"
        }
        if (version == this.version) return AssetChangeSet.None
        val changed = lastChangedAt.asSequence()
            .filter { it.value > version }
            .map { it.key }
            .sortedBy { it.value }
            .toCollection(LinkedHashSet())
        return AssetChangeSet(changed, requiresRestart = lastShapeChangeAt > version)
    }

    /**
     * Records that [ids] now hold new values, and returns the new [current] version.
     *
     * Called by [AssetRegistry.applyDelta] after the values are in the array, so a listener that
     * reads [current] during notification sees the version the change produced.
     */
    public fun record(ids: Collection<AssetId>): Int {
        require(ids.isNotEmpty()) { "record() with no ids would burn a version for nothing" }
        version++
        ids.forEach { lastChangedAt[it] = version }
        return version
    }

    /**
     * Records that the graph was *replaced* - a shape-changing reload - so that every version at
     * or before the returned one answers [AssetChangeSet.requiresRestart] `true`.
     */
    public fun recordReplacement(ids: Collection<AssetId>): Int {
        val recorded = record(ids)
        lastShapeChangeAt = recorded
        return recorded
    }

    private companion object {
        /** No shape change has happened; `> version` is false for every issued version. */
        const val NEVER = -1
    }
}
