package dev.wildware.udea.gas

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId

/**
 * Applies effect definitions to entities: allocates the handle, resolves the duration, fills the
 * slot and emits the application cues.
 *
 * One responsibility, and it is the one place an [EffectHandle] enters the world. That matters
 * because the handle has to come from the per-world [HandleAllocator] rather than a static
 * counter — `Abilities.applyGameplayEffect` (`common/.../Abilities.kt:52`) took an already-built
 * spec whose handle had been allocated from `EffectHandle.Companion.nextId` at construction, so
 * the allocation happened wherever a spec happened to be constructed and no world owned it.
 *
 * ## The set-by-caller two-step
 *
 * A `SetByCaller` duration cannot be resolved until the caller's magnitudes are known, and the
 * magnitudes live on the application. So an application is opened with [begin], given magnitudes
 * with [magnitude], and committed with [applyTo]. The scratch it stages them in is a fixed pair
 * of arrays on this object, reused for every application — no map, no allocation.
 *
 * Not thread-safe, and deliberately not re-entrant: an [applyTo] closes the application it
 * commits, so a caller that forgets [begin] fails loudly instead of inheriting the last caller's
 * magnitudes.
 */
public class EffectApplier(
    /** Every effect definition in this game. */
    public val effects: GameplayEffectTable,
    /** The per-world handle source. */
    public val handles: HandleAllocator,
    /** Where application cues go. */
    public val cues: GasCueQueue,
) {

    private val stagedTags = IntArray(GameplayEffects.SET_BY_CALLER_SLOTS) { GameplayTag.NONE.id }
    private val stagedValues = FloatArray(GameplayEffects.SET_BY_CALLER_SLOTS)
    private var stagedCount = 0
    private var openDefIndex = -1

    /** Resolves magnitudes against the staged values and the target's current attributes. */
    private val cursor = StagedMagnitudeSource()

    /** Opens an application of the effect at [defIndex]. */
    public fun begin(defIndex: Int): EffectApplier {
        openDefIndex = defIndex
        stagedCount = 0
        java.util.Arrays.fill(stagedTags, GameplayTag.NONE.id)
        java.util.Arrays.fill(stagedValues, 0f)
        return this
    }

    /** Opens an application of the effect named [name]. */
    public fun begin(name: String): EffectApplier = begin(effects.indexOf(name))

    /** Stages a set-by-caller magnitude for the open application. */
    public fun magnitude(tag: GameplayTag, value: Float): EffectApplier {
        check(openDefIndex >= 0) { "no application is open; call begin() first" }
        if (stagedCount == stagedTags.size) throw SetByCallerOverflowException(-1, stagedTags.size)
        stagedTags[stagedCount] = tag.id
        stagedValues[stagedCount] = value
        stagedCount++
        return this
    }

    /**
     * Commits the open application onto [target], returning its handle.
     *
     * Cues are emitted **every** time, which is a behaviour change and a bug fix:
     * `Abilities.applyGameplayEffect` computed `alreadyApplied` *before* inserting the spec
     * (`:56`) and then consulted it *after* (`:60`), so the second and every later application of
     * the same effect played no cue at all.
     */
    public fun applyTo(
        target: GameplayEffects,
        attributes: Attributes,
        now: Tick,
        targetId: NetId = NetId.NONE,
        source: NetId = NetId.NONE,
        stacks: Int = 1,
    ): EffectHandle {
        val defIndex = openDefIndex
        check(defIndex >= 0) { "no application is open; call begin() first" }
        openDefIndex = -1

        val def = effects.defAt(defIndex)
        cursor.bind(attributes)
        val durationTicks = def.duration.durationTicks(cursor)

        val handle = handles.allocate()
        val slot = target.add(
            handle = handle,
            defIndex = defIndex,
            appliedTick = now,
            durationTicks = durationTicks,
            periodTicks = def.periodTicks,
            source = source,
            stacks = stacks,
        )
        var staged = 0
        while (staged < stagedCount) {
            target.setMagnitude(slot, GameplayTag(stagedTags[staged]), stagedValues[staged])
            staged++
        }

        var cue = 0
        while (cue < def.cueIds.size) {
            cues.emit(
                cueId = def.cueIds[cue],
                tick = now,
                source = source,
                target = targetId,
                effectHandle = handle,
            )
            cue++
        }
        return handle
    }

    /**
     * Removes the application [handle] names from [target], releasing the handle.
     *
     * @return true when it was applied.
     */
    public fun remove(target: GameplayEffects, handle: EffectHandle): Boolean {
        val slot = target.indexOfHandle(handle)
        if (slot < 0) return false
        target.removeAt(slot)
        handles.release(handle)
        return true
    }

    /** Reads staged magnitudes and the target's current attributes; reused, never allocated. */
    private inner class StagedMagnitudeSource : MagnitudeSource {

        private var attributes: Attributes? = null

        fun bind(attributes: Attributes) {
            this.attributes = attributes
        }

        override fun attribute(id: AttributeId): Float =
            checkNotNull(attributes) { "no attributes bound" }.current(id)

        override fun setByCaller(tag: GameplayTag): Float {
            var index = 0
            while (index < stagedCount) {
                if (stagedTags[index] == tag.id) return stagedValues[index]
                index++
            }
            return 0f
        }
    }
}
