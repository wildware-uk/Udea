package dev.wildware.udea.agent.assets

import com.github.quillraven.fleks.World
import dev.wildware.udea.assets.AssetChangeSet
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.AssetRegistry
import dev.wildware.udea.assets.DeltaResult
import dev.wildware.udea.assets.GraphDelta
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SimClock
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.loop.AssetGraphHistory
import dev.wildware.udea.core.loop.BarrierAction
import dev.wildware.udea.core.loop.SimBarrier

/** What one push of a [GraphDelta] into a running game did. */
public sealed interface PushOutcome {

    /** The delta is in the graph, applied at the top of tick [tick]. */
    public data class Applied(
        public val tick: Tick,
        public val changedIds: Set<AssetId>,
        public val version: Int,
    ) : PushOutcome

    /**
     * The registry refused the whole delta.
     *
     * The daemon classifies before it pushes, so reaching this means the two disagreed - worth an
     * explicit outcome rather than a log line, because it is the symptom of a daemon whose
     * last-good graph has drifted from the running game's.
     */
    public data class Refused(public val codes: List<String>) : PushOutcome

    /** Queued; the tick it will land on has not happened yet. */
    public data object Pending : PushOutcome
}

/**
 * A [GraphDelta] on its way into a running world, and the record of every one that landed.
 *
 * ## Why the delta goes through the barrier
 *
 * `AssetRegistry.applyDelta` rewrites the array a system may be halfway through reading. Spec 3.3
 * gives exactly one answer to that - [SimBarrier], drained at the top of `Simulation.step` before
 * any system runs - and this class is why the asset epic does not get a second queue. The daemon
 * decides on its own thread, calls [push], and the swap happens at a tick boundary or not at all.
 *
 * ## Why it also records
 *
 * Spec 3.6's rewind interaction needs a *tick-indexed* history: `rewind(600)` has to answer "did
 * the asset graph change since the tick I am restoring". [AssetRegistry] records versions, not
 * ticks, so something has to hold the join, and this is the object that already knows both - it is
 * the one applying the delta, on the tick the clock is reporting. [history] is the
 * [AssetGraphHistory] a `SnapshotTimeTravel` is built with.
 *
 * ## What this does not do, and what that costs
 *
 * `RewindResult.Rewound.assetGraphChangedSince` is a `Boolean`, because [AssetGraphHistory] is
 * `(Tick) -> Boolean`. So a rewind can say *that* the graph moved and not *which ids* moved, and
 * it cannot refuse a rewind on that channel. Widening that interface is `udea-core`'s change, not
 * this module's. Two things stand in meanwhile, and neither is a stub:
 *
 * - [changedSince] answers with the ids and `assets.changed_since` publishes it, so an agent that
 *   rewinds and then asks gets the full answer in one extra call;
 * - a shape-changing reload is **never applied here at all**. `AssetDaemon` refuses to produce a
 *   delta for one and `AssetRegistry.applyDelta` refuses to apply one, so a running registry
 *   cannot be shape-changed in place and there is no such rewind to refuse. A host that answers a
 *   shape change by building a *new* registry hands the old
 *   [dev.wildware.udea.assets.AssetGraphLog] to it, and `changesSince` then reports
 *   `requiresRestart` - see that class.
 */
public class AssetHotReload(
    private val registry: AssetRegistry,
    private val barrier: SimBarrier,
    private val clock: SimClock,
) {

    /** Tick each id last changed on, in arrival order. Small: only ids that ever reloaded. */
    private val changedAt = LinkedHashMap<AssetId, Long>()

    /** Deltas that landed. The counter `assets.changed_since` publishes. */
    public var applied: Int = 0
        private set

    /** The tick the most recent delta landed on, or `null` before the first one. */
    public var lastAppliedTick: Tick? = null
        private set

    /**
     * Whether anything changed after a tick. The [AssetGraphHistory] a `SnapshotTimeTravel` takes.
     *
     * A property rather than a method reference so a host writes
     * `snapshotTimeTravel(..., assetGraph = hotReload.history)` and cannot accidentally pass a
     * lambda closing over a registry that has since been replaced.
     */
    public val history: AssetGraphHistory = AssetGraphHistory { since -> changedSince(since).isNotEmpty() }

    /**
     * Queues [delta] for the top of the next `Simulation.step`.
     *
     * Returns [PushOutcome.Pending] rather than the result of applying it, because applying it has
     * not happened yet and will not until the simulation reaches a tick boundary. A method that
     * returned `Applied` here would be claiming a swap that a paused game will not perform for as
     * long as it stays paused. [onLanded] is how a caller that needs the answer gets it.
     */
    public fun push(delta: GraphDelta, onLanded: (PushOutcome) -> Unit = {}): PushOutcome {
        barrier.submit(Reload(delta, onLanded))
        return PushOutcome.Pending
    }

    /** Ids that changed strictly after [since], oldest change first. */
    public fun changedSince(since: Tick): List<AssetId> =
        changedAt.entries.filter { it.value > since.value }.sortedBy { it.value }.map { it.key }

    /**
     * The change set for everything after [since], in the shape [AssetRegistry] reports.
     *
     * `requiresRestart` is always false, and that is a fact rather than a simplification: nothing
     * shape-changing can reach this class, for the reason the class KDoc gives.
     */
    public fun changeSetSince(since: Tick): AssetChangeSet =
        AssetChangeSet(changedSince(since).toSet(), requiresRestart = false)

    /**
     * The mutation itself.
     *
     * A named [BarrierAction] and not a lambda: when an agent asks why the world changed between
     * tick 412 and 413, [label] is the only thing that answers, and "asset reload of 3 assets" is
     * an answer.
     */
    private inner class Reload(
        private val delta: GraphDelta,
        private val onLanded: (PushOutcome) -> Unit,
    ) : BarrierAction {

        override val label: String = "asset reload of ${delta.changed.size} assets"

        override fun apply(world: World, ctx: GameContext) {
            val outcome = when (val result = registry.applyDelta(delta)) {
                is DeltaResult.Applied -> {
                    val tick = clock.tick
                    result.changedIds.forEach { changedAt[it] = tick.value }
                    applied++
                    lastAppliedTick = tick
                    PushOutcome.Applied(tick, result.changedIds, result.version)
                }

                is DeltaResult.Refused ->
                    PushOutcome.Refused(result.classification.changes.map { it.reason.code })
            }
            onLanded(outcome)
        }
    }
}
