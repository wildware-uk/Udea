package dev.wildware.udea.gas

import dev.wildware.udea.core.Tick

/**
 * Rebuilds `current` from `base` plus every active modifier, for one entity, with no allocation.
 *
 * ## The model, kept
 *
 * Reset to base and reapply every active modifier is the best decision in the old GAS and it
 * survives unchanged: because `current` is a pure function of `(base, active effects)`, a
 * snapshot need only carry base values and the effect list, and a rewind or a mispredicted
 * rollback cannot leave a corrupted stat behind. Everything below is about making that model
 * survive the determinism gate and the snapshot ring's cost budget.
 *
 * ## What changed, and why
 *
 * - **No per-tick list.** `AttributeSystem.kt:23` called `.sortedBy { }`, allocating a fresh list
 *   per entity per tick. Here a preallocated index buffer is reused across ticks *and* entities,
 *   sorted in place.
 * - **A total order.** The old sort key was the modifier type alone, and `sortedBy` is stable, so
 *   the result silently depended on the *slot order* of the effect list — a list a `removeIf` had
 *   compacted, or a restore had rebuilt, could reorder two same-type modifiers and change a stat
 *   with nothing gameplay-side having changed. The key here is
 *   `(ModifierType ordinal, AttributeId, EffectHandle)`, which is total: no two applications share
 *   a handle. That works only because handles are per-world and snapshot-restored (see
 *   [HandleAllocator]).
 *
 *   Be precise about what this does *not* buy. Two worlds that applied the same effects in a
 *   different sequence hold genuinely different state — the handles differ — so two `Override`s on
 *   one attribute still resolve differently, and no sort key could change that. What the key buys
 *   is that the result is a pure function of `(base, effect list)` and never of how that list came
 *   to be laid out, so a capture, a restore and a re-simulation all agree.
 * - **Ticks, not seconds.** `:26` and `:52` accumulated `gameScreen.delta` onto the spec. Periodic
 *   firing is driven by `nextPeriodTick` and expiry by `appliedTick + durationTicks`.
 * - **No cast.** `:55` cast the read-only list to `MutableList` to remove from it. Expiry here goes
 *   through [GameplayEffects.removeAt], the component's own API, and releases the handle.
 *
 * Insertion sort rather than a comparison sort with a comparator object: the list is a few dozen
 * entries at most and is *already ordered by handle*, so the sort is close to linear in practice —
 * and, unlike `sortedBy`, it allocates neither a list nor a comparator nor a boxed key.
 */
public class AttributeRecompute(
    private val effectTable: GameplayEffectTable,
    private val attributeTable: AttributeTable,
    /** Where expired applications return their handles. */
    private val handles: HandleAllocator,
) {

    /** Reused across ticks and entities. Grows only when an entity carries more effects than ever before. */
    private var order = IntArray(GameplayEffects.DEFAULT_CAPACITY)

    private val cursor = Cursor()

    /** Ids whose declaration carries an [AttributeClamp], so the final pass touches only those. */
    private val clampedIds: IntArray = buildList {
        for (index in 0 until attributeTable.count) {
            if (attributeTable.declOf(AttributeId(index)).clamp != null) add(index)
        }
    }.toIntArray()

    /**
     * Fires due permanent effects into `base`, rebuilds `current`, then sweeps expired effects.
     *
     * Idempotent within a tick: running it twice on the same tick produces the same `current` and
     * the same `base`, because a periodic application advances `nextPeriodTick` past `now` and an
     * instant application is swept in the same call. That is what stops a rollback re-simulation
     * double-applying damage.
     */
    public fun recompute(attributes: Attributes, effects: GameplayEffects, now: Tick) {
        val count = effects.count
        ensureOrderCapacity(count)
        buildOrder(effects, count)
        cursor.bind(attributes, effects)

        // The order is the behaviour, and each step depends on the one before:
        //
        // 1. permanent effects fire into `base` *before* anything is swept, so an instant effect
        //    applied this tick still lands — it expires the moment it fires;
        // 2. expired effects go *before* `current` is rebuilt, so an effect whose last tick was the
        //    previous one does not modify this tick's `current`. Sweeping afterwards left a
        //    30-tick haste boosting move speed on tick 31;
        // 3. `current` is then rebuilt from scratch, which is what keeps it derived.
        applyPermanent(attributes, effects, count, now)
        sweepExpired(effects, now)
        // The sweep compacts, so the index buffer built above no longer names the same slots.
        // Rebuilding it is an insertion sort over an already-ordered list, and it allocates nothing.
        buildOrder(effects, effects.count)
        System.arraycopy(attributes.base, 0, attributes.current, 0, attributes.base.size)
        applyModifiers(attributes, effects, effects.count)
        applyClamps(attributes)
    }

    // --- phases ------------------------------------------------------------------------------

    /** Instant and periodic effects write `base`: a permanent change, not a derived one. */
    private fun applyPermanent(attributes: Attributes, effects: GameplayEffects, count: Int, now: Tick) {
        var position = 0
        while (position < count) {
            val slot = order[position]
            val def = effectTable.defAt(effects.defIndexAt(slot))
            if (def.isPermanent && def.modifiesAttribute) {
                cursor.slot = slot
                if (def.periodTicks > 0) {
                    // Catch-up: more than one period may have elapsed inside a stepped range, and
                    // firing once would make step(60) disagree with sixty single steps.
                    while (now.value >= effects.nextPeriodTickAt(slot).value) {
                        writeBase(attributes, def, slot)
                        effects.advancePeriod(slot)
                    }
                } else {
                    writeBase(attributes, def, slot)
                }
            }
            position++
        }
    }

    private fun writeBase(attributes: Attributes, def: GameplayEffectDef, slot: Int) {
        val id = def.target
        val decl = attributeTable.declOf(id)
        val magnitude = def.magnitude.resolve(cursor)
        val combined = def.modifierType.apply(attributes.base[id.index], magnitude)
        attributes.base[id.index] = combined.coerceIn(decl.min.resolve(cursor), decl.max.resolve(cursor))
    }

    /** Duration effects contribute to `current` only, which is what keeps `current` derived. */
    private fun applyModifiers(attributes: Attributes, effects: GameplayEffects, count: Int) {
        var position = 0
        while (position < count) {
            val slot = order[position]
            val def = effectTable.defAt(effects.defIndexAt(slot))
            if (!def.isPermanent && def.modifiesAttribute) {
                cursor.slot = slot
                val id = def.target
                val decl = attributeTable.declOf(id)
                val magnitude = def.magnitude.resolve(cursor)
                val combined = def.modifierType.apply(attributes.current[id.index], magnitude)
                attributes.current[id.index] =
                    combined.coerceIn(decl.min.resolve(cursor), decl.max.resolve(cursor))
            }
            position++
        }
    }

    /**
     * The surviving half of `AttributeSet.preAttributeChanged`: a cross-attribute clamp, resolved
     * by [AttributeId] rather than by overriding a method on an attribute set.
     */
    private fun applyClamps(attributes: Attributes) {
        var index = 0
        while (index < clampedIds.size) {
            val id = AttributeId(clampedIds[index])
            val clamp = attributeTable.declOf(id).clamp
            if (clamp != null) {
                attributes.current[id.index] =
                    clamp.clamp(id, attributes.current[id.index], attributes.current)
            }
            index++
        }
    }

    /** Removes expired applications, newest first so earlier indices stay valid. */
    private fun sweepExpired(effects: GameplayEffects, now: Tick) {
        var slot = effects.count - 1
        while (slot >= 0) {
            if (hasExpired(now, effects.appliedTickAt(slot), effects.durationTicksAt(slot))) {
                val handle = effects.handleAt(slot)
                effects.removeAt(slot)
                handles.release(handle)
            }
            slot--
        }
    }

    // --- ordering ----------------------------------------------------------------------------

    private fun ensureOrderCapacity(count: Int) {
        if (order.size >= count) return
        var size = order.size
        while (size < count) size *= 2
        order = IntArray(size)
    }

    private fun buildOrder(effects: GameplayEffects, count: Int) {
        var index = 0
        while (index < count) {
            order[index] = index
            index++
        }
        var position = 1
        while (position < count) {
            val candidate = order[position]
            var scan = position - 1
            while (scan >= 0 && sortsAfter(effects, order[scan], candidate)) {
                order[scan + 1] = order[scan]
                scan--
            }
            order[scan + 1] = candidate
            position++
        }
    }

    /** True when slot [a] sorts strictly after slot [b] under `(type, attribute, handle)`. */
    private fun sortsAfter(effects: GameplayEffects, a: Int, b: Int): Boolean {
        val defA = effectTable.defAt(effects.defIndexAt(a))
        val defB = effectTable.defAt(effects.defIndexAt(b))
        val byType = defA.modifierType.ordinal - defB.modifierType.ordinal
        if (byType != 0) return byType > 0
        val byAttribute = defA.target.index - defB.target.index
        if (byAttribute != 0) return byAttribute > 0
        return effects.handleAt(a).raw > effects.handleAt(b).raw
    }

    /** Resolves magnitudes for one (entity, effect slot). One instance, rebound, never allocated. */
    private class Cursor : MagnitudeSource {

        private var attributes: Attributes? = null
        private var effects: GameplayEffects? = null

        var slot: Int = -1

        fun bind(attributes: Attributes, effects: GameplayEffects) {
            this.attributes = attributes
            this.effects = effects
            slot = -1
        }

        override fun attribute(id: AttributeId): Float =
            checkNotNull(attributes) { "recompute cursor is unbound" }.current[id.index]

        override fun setByCaller(tag: GameplayTag): Float {
            val effects = checkNotNull(effects) { "recompute cursor is unbound" }
            return if (slot < 0) 0f else effects.magnitudeAt(slot, tag)
        }
    }
}
