package dev.wildware.udea.gas

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId

/**
 * The effects currently applied to one entity, as parallel primitive arrays.
 *
 * ## Why not a list of objects
 *
 * The old component held `ArrayList<GameplayEffectSpec>` and the recompute loop turned it into
 * a *fresh sorted list* every tick for every entity (`AttributeSystem.kt:23`), then cast the
 * read-only view back to `MutableList` to remove from it (`:55`). Both are gone here: the state
 * is primitive columns, so nothing is allocated to read it, and removal is this component's own
 * method rather than a cast that defeats the read-only accessor it is reached through.
 *
 * Everything a slot holds is a primitive, which is also what makes the effect list snapshot
 * state rather than an object graph: a `Replicator` lowers it to `Int`, `Long` and `Float`
 * fields, and a restored world holds no reference to an asset that a hot reload may have swapped.
 *
 * ## Slots are compacted, and ordered by handle
 *
 * [removeAt] shifts the tail down rather than swapping the last slot in. Order is therefore
 * *ascending handle* at all times, because handles are issued monotonically and applications
 * append. That is what makes [indexOfHandle] a binary search rather than the linear
 * `_gameplayEffectSpecs.find { it.handle == handle }` at `Abilities.kt:88`, and it is why the
 * recompute's sort is stable in the only sense that matters — the input order is already a
 * total order.
 */
public class GameplayEffects(
    initialCapacity: Int = DEFAULT_CAPACITY,
) : Component<GameplayEffects> {

    init {
        require(initialCapacity > 0) { "initialCapacity must be positive, was $initialCapacity" }
    }

    private var handles = IntArray(initialCapacity)
    private var defIndices = IntArray(initialCapacity)
    private var appliedTicks = LongArray(initialCapacity)
    private var durations = LongArray(initialCapacity)
    private var nextPeriodTicks = LongArray(initialCapacity)
    private var periodTicks = IntArray(initialCapacity)
    private var stacks = IntArray(initialCapacity)
    private var sources = IntArray(initialCapacity)
    private var setByCallerTags = IntArray(initialCapacity * SET_BY_CALLER_SLOTS)
    private var setByCallerValues = FloatArray(initialCapacity * SET_BY_CALLER_SLOTS)

    /** How many effects are applied. Valid slots are `0 until count`. */
    public var count: Int = 0
        private set

    /** How many slots are allocated. Grows by doubling, never inside a steady-state tick. */
    public val capacity: Int get() = handles.size

    override fun type(): ComponentType<GameplayEffects> = GameplayEffects

    // --- reads -------------------------------------------------------------------------------

    /** The handle of the application in [slot]. */
    public fun handleAt(slot: Int): EffectHandle = EffectHandle(handles[checked(slot)])

    /** The [GameplayEffectTable] index of the definition applied in [slot]. */
    public fun defIndexAt(slot: Int): Int = defIndices[checked(slot)]

    /** The tick [slot] was applied on. */
    public fun appliedTickAt(slot: Int): Tick = Tick(appliedTicks[checked(slot)])

    /** [slot]'s resolved duration in ticks, or [GameplayEffectDuration.INFINITE]. */
    public fun durationTicksAt(slot: Int): Long = durations[checked(slot)]

    /** The next tick [slot] fires its periodic application on. Meaningless when not periodic. */
    public fun nextPeriodTickAt(slot: Int): Tick = Tick(nextPeriodTicks[checked(slot)])

    /** [slot]'s period in ticks, or `0`. */
    public fun periodTicksAt(slot: Int): Int = periodTicks[checked(slot)]

    /** How many stacks [slot] carries. */
    public fun stacksAt(slot: Int): Int = stacks[checked(slot)]

    /** Who applied [slot]. A [NetId], never a Fleks `Entity` (spec 5). */
    public fun sourceAt(slot: Int): NetId = NetId.ofRaw(sources[checked(slot)])

    /**
     * The slot holding [handle], or `-1`.
     *
     * Binary search over the handle column, which is sorted because handles are monotonic and
     * [removeAt] compacts. `O(log n)` on a list that is a few dozen entries at worst, against
     * the old `O(n)` scan with a lambda per call — and, unlike the old one, it allocates nothing.
     */
    public fun indexOfHandle(handle: EffectHandle): Int {
        var low = 0
        var high = count - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = handles[mid]
            when {
                value < handle.raw -> low = mid + 1
                value > handle.raw -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** True when [handle] is still applied. What an ability's cooldown check asks. */
    public operator fun contains(handle: EffectHandle): Boolean = indexOfHandle(handle) >= 0

    /**
     * The tag of [slot]'s set-by-caller entry [offset], or [GameplayTag.NONE].
     *
     * Positional rather than keyed, because a `Replicator` lowers these to a fixed pair of columns
     * per entry and has to read them by index — the same reason there are a fixed number of them.
     */
    public fun magnitudeTagAt(slot: Int, offset: Int): GameplayTag =
        GameplayTag(setByCallerTags[checked(slot) * SET_BY_CALLER_SLOTS + checkedOffset(offset)])

    /** The value of [slot]'s set-by-caller entry [offset]. */
    public fun magnitudeValueAt(slot: Int, offset: Int): Float =
        setByCallerValues[checked(slot) * SET_BY_CALLER_SLOTS + checkedOffset(offset)]

    /** The magnitude set on [slot] for [tag], or `0`. */
    public fun magnitudeAt(slot: Int, tag: GameplayTag): Float {
        val base = checked(slot) * SET_BY_CALLER_SLOTS
        var offset = 0
        while (offset < SET_BY_CALLER_SLOTS) {
            if (setByCallerTags[base + offset] == tag.id) return setByCallerValues[base + offset]
            offset++
        }
        return 0f
    }

    // --- writes ------------------------------------------------------------------------------

    /**
     * Applies an effect, returning its slot.
     *
     * @param durationTicks already resolved, so a `SetByCaller` duration is fixed at application
     *   rather than re-read every tick. Re-reading would let a later magnitude change retroactively
     *   extend an effect already running, which no caller means and which a rewind would not reproduce.
     */
    public fun add(
        handle: EffectHandle,
        defIndex: Int,
        appliedTick: Tick,
        durationTicks: Long,
        periodTicks: Int,
        source: NetId = NetId.NONE,
        stacks: Int = 1,
    ): Int {
        require(!handle.isInvalid) { "cannot apply an effect with EffectHandle.INVALID" }
        require(count == 0 || handles[count - 1] < handle.raw) {
            "effect handles must be applied in ascending order to keep the list searchable; " +
                "$handle is not above ${EffectHandle(handles[count - 1])}"
        }
        if (count == capacity) grow()
        val slot = count
        handles[slot] = handle.raw
        defIndices[slot] = defIndex
        appliedTicks[slot] = appliedTick.value
        durations[slot] = durationTicks
        this.periodTicks[slot] = periodTicks
        nextPeriodTicks[slot] = if (periodTicks > 0) appliedTick.value + periodTicks else Long.MAX_VALUE
        this.stacks[slot] = stacks
        sources[slot] = source.raw
        val base = slot * SET_BY_CALLER_SLOTS
        java.util.Arrays.fill(setByCallerTags, base, base + SET_BY_CALLER_SLOTS, GameplayTag.NONE.id)
        java.util.Arrays.fill(setByCallerValues, base, base + SET_BY_CALLER_SLOTS, 0f)
        count++
        return slot
    }

    /**
     * Sets [slot]'s magnitude for [tag].
     *
     * @throws SetByCallerOverflowException when the slot's fixed magnitude slots are full. A
     *   fixed count rather than a map because a map on an effect is a per-application allocation
     *   and an object in a snapshot; [SET_BY_CALLER_SLOTS] is the ceiling and it is checked
     *   rather than silently dropped.
     */
    public fun setMagnitude(slot: Int, tag: GameplayTag, value: Float) {
        val base = checked(slot) * SET_BY_CALLER_SLOTS
        var offset = 0
        var free = -1
        while (offset < SET_BY_CALLER_SLOTS) {
            val existing = setByCallerTags[base + offset]
            if (existing == tag.id) {
                setByCallerValues[base + offset] = value
                return
            }
            if (existing == GameplayTag.NONE.id && free < 0) free = offset
            offset++
        }
        if (free < 0) throw SetByCallerOverflowException(slot, SET_BY_CALLER_SLOTS)
        setByCallerTags[base + free] = tag.id
        setByCallerValues[base + free] = value
    }

    /** Advances [slot]'s next periodic tick by one period. */
    internal fun advancePeriod(slot: Int) {
        nextPeriodTicks[checked(slot)] += periodTicks[slot]
    }

    /**
     * Removes [slot], shifting the tail down so the list stays sorted by handle.
     *
     * The engine's own mutation point: there is no `MutableList` to reach for and no cast that
     * would produce one, which is the shape issue #97 asks for.
     */
    public fun removeAt(slot: Int) {
        val index = checked(slot)
        val moved = count - index - 1
        if (moved > 0) {
            System.arraycopy(handles, index + 1, handles, index, moved)
            System.arraycopy(defIndices, index + 1, defIndices, index, moved)
            System.arraycopy(appliedTicks, index + 1, appliedTicks, index, moved)
            System.arraycopy(durations, index + 1, durations, index, moved)
            System.arraycopy(nextPeriodTicks, index + 1, nextPeriodTicks, index, moved)
            System.arraycopy(periodTicks, index + 1, periodTicks, index, moved)
            System.arraycopy(stacks, index + 1, stacks, index, moved)
            System.arraycopy(sources, index + 1, sources, index, moved)
            System.arraycopy(
                setByCallerTags,
                (index + 1) * SET_BY_CALLER_SLOTS,
                setByCallerTags,
                index * SET_BY_CALLER_SLOTS,
                moved * SET_BY_CALLER_SLOTS,
            )
            System.arraycopy(
                setByCallerValues,
                (index + 1) * SET_BY_CALLER_SLOTS,
                setByCallerValues,
                index * SET_BY_CALLER_SLOTS,
                moved * SET_BY_CALLER_SLOTS,
            )
        }
        count--
    }

    /** Removes every applied effect. What a restore does before re-filling. */
    public fun clear() {
        count = 0
    }

    /**
     * Gathers the tags of every applied effect into [into].
     *
     * [into] is the caller's reusable set, so a blocking-tag check costs no allocation. The
     * old spelling — `_gameplayEffectSpecs.any { it.hasTag(tag) }` — allocated a lambda per
     * question and scanned once per question; this scans once per tick and answers with a bit test.
     */
    public fun collectTags(table: GameplayEffectTable, into: TagSet) {
        into.clear()
        var slot = 0
        while (slot < count) {
            into.addAll(table.defAt(defIndices[slot]).tags)
            slot++
        }
    }

    private fun grow() {
        val next = capacity * 2
        handles = handles.copyOf(next)
        defIndices = defIndices.copyOf(next)
        appliedTicks = appliedTicks.copyOf(next)
        durations = durations.copyOf(next)
        nextPeriodTicks = nextPeriodTicks.copyOf(next)
        periodTicks = periodTicks.copyOf(next)
        stacks = stacks.copyOf(next)
        sources = sources.copyOf(next)
        setByCallerTags = setByCallerTags.copyOf(next * SET_BY_CALLER_SLOTS)
        setByCallerValues = setByCallerValues.copyOf(next * SET_BY_CALLER_SLOTS)
    }

    private fun checked(slot: Int): Int {
        require(slot in 0 until count) { "no effect in slot $slot; $count applied" }
        return slot
    }

    private fun checkedOffset(offset: Int): Int {
        require(offset in 0 until SET_BY_CALLER_SLOTS) {
            "no set-by-caller entry $offset; an application holds $SET_BY_CALLER_SLOTS"
        }
        return offset
    }

    override fun toString(): String = "GameplayEffects($count/$capacity)"

    public companion object : ComponentType<GameplayEffects>() {

        /**
         * Slots a fresh component starts with.
         *
         * Sixteen: a MOBA champion mid-fight carries a handful of buffs, a couple of debuffs, a
         * cooldown per ability and a cost effect or two. Beyond it the arrays double, which is
         * amortised and — because it happens on application rather than on the recompute — off
         * the path the allocation gate measures.
         */
        public const val DEFAULT_CAPACITY: Int = 16

        /**
         * How many set-by-caller magnitudes one application may carry.
         *
         * Four, because the old assets set at most two (`Data.Cooldown`, `Data.Damage`) and a
         * fixed count is what keeps an application a fixed number of primitive fields instead of
         * a `Map<GameplayTag, Float>`. Overflow throws rather than dropping.
         */
        public const val SET_BY_CALLER_SLOTS: Int = 4
    }
}

/** More set-by-caller magnitudes than an application can hold. */
public class SetByCallerOverflowException(
    public val slot: Int,
    public val limit: Int,
) : IllegalStateException(
    "effect slot $slot already carries $limit set-by-caller magnitudes, the fixed per-application " +
        "limit; an effect needing more wants splitting rather than a map",
)
