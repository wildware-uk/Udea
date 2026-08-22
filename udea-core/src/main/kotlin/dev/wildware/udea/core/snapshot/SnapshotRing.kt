package dev.wildware.udea.core.snapshot

import dev.wildware.udea.core.Log
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.loop.SnapshotInfo
import dev.wildware.udea.core.loop.SnapshotKind

/**
 * The two retention windows, as ticks.
 *
 * Both cadences come out of **one** ring. Spec 7 is explicit that one structure carries time
 * travel, replication baselines and rollback, and that if capture allocates then three
 * features degrade at once — so there is no second store for either window, and a slot in the
 * dense window is the same object that later becomes a sparse keyframe.
 */
public data class RingConfig(
    /**
     * How far back every tick is kept: the rollback window. 120 ticks is ~2s at 60Hz, which
     * is what Phase 4's prediction rollback restores from.
     */
    public val denseTicks: Int = DEFAULT_DENSE_TICKS,
    /**
     * How far back keyframes are kept: the agent's rewind window. 3600 ticks is ~60s at 60Hz,
     * the figure spec 1 promises an agent.
     */
    public val sparseWindowTicks: Int = DEFAULT_SPARSE_WINDOW_TICKS,
    /**
     * Keyframe spacing outside the dense window. Doubles on [SnapshotRing.degrade]; the
     * *window* never shrinks, only its density.
     */
    public val sparseInterval: Int = DEFAULT_SPARSE_INTERVAL,
    /** Hard ceiling on the ring (spec 7: under 64MB). See [SnapshotBudgets.RING_BYTES]. */
    public val budgetBytes: Long = SnapshotBudgets.RING_BYTES,
) {
    init {
        require(denseTicks > 0) { "denseTicks must be positive, was $denseTicks" }
        require(sparseInterval > 0) { "sparseInterval must be positive, was $sparseInterval" }
        require(sparseWindowTicks >= denseTicks) {
            "sparseWindowTicks ($sparseWindowTicks) must reach at least as far back as " +
                "denseTicks ($denseTicks); the sparse window is the outer one"
        }
        require(budgetBytes > 0) { "budgetBytes must be positive, was $budgetBytes" }
    }

    public companion object {
        /** ~2 seconds at 60Hz. */
        public const val DEFAULT_DENSE_TICKS: Int = 120

        /** ~60 seconds at 60Hz. */
        public const val DEFAULT_SPARSE_WINDOW_TICKS: Int = 3600

        /** ~10Hz keyframes at 60Hz. Doubles under budget pressure. */
        public const val DEFAULT_SPARSE_INTERVAL: Int = 6
    }
}

/**
 * The snapshot ring: two cadences, one pool of recycled slots.
 *
 * Nothing in the old engine buffered past world state at all —
 * `common/ecs/system/NetworkClientSystem.kt` kept only a queue of inbound packets — so a
 * rollback, a rewind and a replication baseline had nothing to read from. This is the one
 * structure all three read.
 *
 * ## Retention
 *
 * A tick inside the dense window is always kept. Once it falls out of it, the slot survives
 * only if `tick % sparseInterval == 0` and it is still inside the sparse window; otherwise it
 * goes straight back to the pool. That is a single interior removal per commit, at a known
 * position, which is why the held list is a plain `ArrayList` walked by index: the removal is
 * an `arraycopy` of a few hundred references and allocates nothing, whereas a structure with
 * O(1) interior removal would need a second index to maintain and a second thing to get wrong.
 *
 * ## Degradation, not failure
 *
 * When the byte budget is exceeded the ring [degrade]s: [sparseInterval] doubles, the slots
 * that no longer land on a keyframe are released, and the oldest sparse slots are evicted
 * until the ring fits. Spec 7 fixes this policy — "if the budget is missed, degrade the sparse
 * cadence rather than dropping the feature" — because rewind *precision* is preserved by
 * stepping forward from the keyframe, so halving the keyframe density costs a few extra bare
 * steps and nothing else. The sixty-second window is never shortened.
 *
 * Not thread-safe. One ring, one simulation, one thread.
 */
public class SnapshotRing(
    /** The component types every slot is laid out for. */
    public val registry: ComponentRegistry,
    config: RingConfig = RingConfig(),
    /** Where degradation is reported. A ring that degrades silently is a budget nobody sees. */
    private val log: Log = Log.NoOp,
) {

    /** How far back every tick is kept. Fixed: rollback correctness depends on it. */
    public val denseTicks: Int = config.denseTicks

    /** How far back keyframes are kept. Fixed: this is the reach [degrade] must never spend. */
    public val sparseWindowTicks: Int = config.sparseWindowTicks

    /** The ceiling that triggers [degrade] and then eviction. */
    public val budgetBytes: Long = config.budgetBytes

    /** Current keyframe spacing. Doubles on [degrade] and never shrinks again. */
    public var sparseInterval: Int = config.sparseInterval
        private set

    /** How many times [degrade] has fired. Non-zero means this machine missed the budget. */
    public var degradeCount: Int = 0
        private set

    /** Held slots, ascending by tick. */
    private val held = ArrayList<WorldSnapshot>(DEFAULT_HELD_CAPACITY)

    /** Slots not currently holding a capture. Recycled, never freed. */
    private val pool = ArrayList<WorldSnapshot>(DEFAULT_HELD_CAPACITY)

    /** Every slot ever built, held or pooled. Bounds how much the ring can ever cost. */
    public var slotCount: Int = 0
        private set

    /** Slots waiting in the pool. `clear()` returns every held slot to it. */
    public val pooledCount: Int get() = pool.size

    /** How many snapshots the ring is holding. */
    public val size: Int get() = held.size

    /** Bytes across every held slot. Recomputed on commit, so it is never stale. */
    public var totalBytes: Long = 0L
        private set

    /**
     * Takes a slot to capture into, from the pool if one is free.
     *
     * The slot is reset and **not** yet in the ring: a capture that throws half way leaves the
     * ring exactly as it was, and the caller releases the slot. Pair every call with either
     * [commit] or [release].
     */
    public fun acquire(): WorldSnapshot {
        val slot = if (pool.isEmpty()) {
            slotCount++
            WorldSnapshot(registry)
        } else {
            pool.removeAt(pool.size - 1)
        }
        slot.reset()
        return slot
    }

    /** Returns a slot taken by [acquire] without storing it. */
    public fun release(slot: WorldSnapshot) {
        slot.reset()
        pool.add(slot)
    }

    /**
     * Stores a filled slot and applies retention and the budget.
     *
     * Returns nothing on purpose. This runs on every captured tick, and a [SnapshotInfo] here
     * would be one object per capture on the path spec 7 budgets at zero allocation — for a
     * value only an agent tool call ever reads. [infoOf] builds one on demand instead.
     *
     * @throws IllegalArgumentException if [slot] is not newer than the newest held snapshot.
     *   The ring is a history, and a history that went backwards would make
     *   [nearestAtOrBefore] return a slot from a future that has been unwound.
     */
    public fun commit(slot: WorldSnapshot) {
        require(slot.isFilled) { "cannot commit an empty snapshot slot" }
        // Not `newestTick()`: a nullable `Tick` is a *boxed* `Tick`, and one box per captured
        // tick is 24 bytes a tick on the path spec 7 budgets at zero. `RingAllocationTest`
        // caught exactly that.
        if (held.isNotEmpty()) {
            val newest = held[held.size - 1].tick
            require(slot.tick > newest) {
                "snapshot ${slot.tick} is not newer than the ring's newest, $newest"
            }
        }

        held.add(slot)
        evictOutsideWindows(slot.tick)
        enforceBudget(slot.tick)
        recomputeTotalBytes()
    }

    /** What the ring holds at [tick], or `null` when it holds nothing there. Allocates. */
    public fun infoOf(tick: Tick): SnapshotInfo? {
        if (held.isEmpty()) return null
        val slot = held.firstOrNull { it.tick == tick } ?: return null
        return infoFor(slot, held[held.size - 1].tick)
    }

    /**
     * The newest snapshot at or before [target], or `null` when the ring does not reach that
     * far back.
     *
     * Null is the honest answer, not an error: an agent that asked to rewind further than the
     * ring goes gets `tick_out_of_ring` and can ask for less.
     */
    public fun nearestAtOrBefore(target: Tick): WorldSnapshot? {
        var low = 0
        var high = held.size - 1
        var best: WorldSnapshot? = null
        while (low <= high) {
            val mid = (low + high) ushr 1
            val candidate = held[mid]
            if (candidate.tick <= target) {
                best = candidate
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }

    /** Every held snapshot, oldest first. Allocates: this is an agent tool result, not a tick path. */
    public fun listSnapshots(): List<SnapshotInfo> {
        if (held.isEmpty()) return emptyList()
        val newest = held[held.size - 1].tick
        return held.map { infoFor(it, newest) }
    }

    /** The newest tick the ring holds, or `null` when it is empty. */
    public fun newestTick(): Tick? = if (held.isEmpty()) null else held[held.size - 1].tick

    /** The oldest tick the ring holds, or `null` when it is empty. */
    public fun oldestTick(): Tick? = if (held.isEmpty()) null else held[0].tick

    /**
     * Halves the keyframe density: [sparseInterval] doubles and the slots that no longer land
     * on one are released.
     *
     * The documented remedy when a machine misses the capture or ring budget
     * ([SnapshotBudgets]). Never loosen a budget instead — the numbers are Phase 0 exit
     * criteria, and a budget that moves when it is missed measures nothing.
     *
     * The rewind *window* is preserved: its oldest keyframe moves forward by at most one new
     * interval, because the previous oldest may no longer land on one. That is the whole trade
     * spec 7 names — reach is kept, density is spent, and precision is restored by stepping
     * forward from the keyframe.
     *
     * @return false when the interval has already reached [denseTicks] and there is nothing
     *   left to give up: past that point every keyframe outside the dense window is more than
     *   a dense window apart, and halving again would start dropping the rewind window itself.
     */
    public fun degrade(): Boolean {
        if (sparseInterval >= denseTicks) {
            log.warn(
                "snapshot ring is over its ${budgetBytes}-byte budget at $totalBytes bytes and " +
                    "cannot degrade further: sparseInterval is already $sparseInterval",
            )
            return false
        }
        sparseInterval *= 2
        degradeCount++
        log.warn(
            "snapshot ring exceeded its ${budgetBytes}-byte budget at $totalBytes bytes; " +
                "sparse cadence degraded to every ${sparseInterval}th tick, " +
                "the ${sparseWindowTicks}-tick rewind window is preserved",
        )
        if (held.isNotEmpty()) evictOutsideWindows(held[held.size - 1].tick)
        recomputeTotalBytes()
        return true
    }

    /**
     * Releases every snapshot strictly newer than [tick].
     *
     * Called after a restore. Those slots record a future that has just been unwound: leaving
     * them would let [nearestAtOrBefore] hand back a world that now never happens, and would
     * make the next [commit] fail its "newer than the newest" check for the rest of the
     * session. Rolling forward re-captures them.
     *
     * @return how many slots were released.
     */
    public fun dropAfter(tick: Tick): Int {
        var dropped = 0
        while (held.isNotEmpty() && held[held.size - 1].tick > tick) {
            release(held.removeAt(held.size - 1))
            dropped++
        }
        if (dropped > 0) recomputeTotalBytes()
        return dropped
    }

    /** Returns every held slot to the pool. Called on a scene swap: ids from one scene are not another's. */
    public fun clear() {
        for (index in held.indices) release(held[index])
        held.clear()
        totalBytes = 0L
    }

    private fun infoFor(slot: WorldSnapshot, newest: Tick): SnapshotInfo = SnapshotInfo(
        tick = slot.tick,
        kind = if (isDense(slot.tick, newest)) SnapshotKind.Dense else SnapshotKind.Sparse,
        sizeBytes = slot.sizeBytes(),
    )

    private fun isDense(tick: Tick, newest: Tick): Boolean =
        newest.ticksSince(tick) < denseTicks

    /**
     * Drops every slot that neither the dense nor the sparse window still wants.
     *
     * Walks from the oldest end, because the two windows are nested: everything the sparse
     * window has dropped is older than everything it still holds, and the one slot that just
     * fell out of the dense window sits at a single interior position.
     */
    private fun evictOutsideWindows(newest: Tick) {
        var index = 0
        while (index < held.size) {
            val slot = held[index]
            val age = newest.ticksSince(slot.tick)
            val keep = when {
                age < denseTicks -> true
                age > sparseWindowTicks -> false
                else -> slot.tick.value % sparseInterval == 0L
            }
            if (keep) {
                index++
            } else {
                release(held.removeAt(index))
            }
        }
    }

    /**
     * Degrades, then evicts oldest-first, until the ring is inside its budget.
     *
     * Eviction is the last resort and it takes the oldest sparse slot first, because losing
     * the far end of the rewind window costs an agent reach, whereas losing the dense window
     * would cost prediction rollback correctness.
     */
    private fun enforceBudget(newest: Tick) {
        recomputeTotalBytes()
        if (totalBytes <= budgetBytes) return

        if (degrade()) {
            if (totalBytes <= budgetBytes) return
        }

        while (held.size > 1 && totalBytes > budgetBytes) {
            val oldest = held[0]
            if (isDense(oldest.tick, newest)) break
            release(held.removeAt(0))
            recomputeTotalBytes()
        }
    }

    private fun recomputeTotalBytes() {
        var total = 0L
        for (index in held.indices) total += held[index].sizeBytes()
        totalBytes = total
    }

    override fun toString(): String =
        "SnapshotRing(${held.size} held, ${pool.size} pooled, $totalBytes/$budgetBytes bytes, " +
            "sparseInterval=$sparseInterval)"

    private companion object {
        /** A full dense window plus a full sparse window at the default cadence, without a regrow. */
        const val DEFAULT_HELD_CAPACITY: Int = 768
    }
}
