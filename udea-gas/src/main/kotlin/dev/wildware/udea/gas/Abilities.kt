package dev.wildware.udea.gas

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId

/** Where an activation has got to. Serializable, because it is a number on an instance. */
public enum class AbilityPhase {
    /** Not running. */
    Inactive,

    /**
     * Running, waiting for a target.
     *
     * Replaces the `awaitTarget` / `AwaitTargetTask` closure pair (`common/ability/util.kt`),
     * which kept the continuation in a captured lambda and the listener in a `@Transient`
     * `EventListener` — both lost on restore. The good half of that design survives: `find()`
     * still runs only where you have authority, and the consequence still runs everywhere. It is
     * just a phase the instance resumes from rather than a closure it cannot serialise.
     */
    AwaitingTarget,

    /** Running with a target settled. */
    Active,
}

/** What an [AbilityInstance]'s target is. */
public enum class AbilityTargetKind {
    /** No target. */
    None,

    /** One entity, by [NetId] — never a Fleks `Entity` (spec 5). */
    Single,

    /** Several entities. */
    Multi,

    /** A point in the world. */
    Location,
}

/**
 * Everything one activation of one ability knows: fully serializable, no closures, no `Entity`.
 *
 * ## What it replaces
 *
 * `AbilitySpec` kept per-activation state in fields marked `@Transient`: the owning `entity`
 * (`Ability.kt:73`), the `active` flag (`:84`), a closure-holding `EventListener` (`:89`) and an
 * `AbilityExec` instance built by reflection (`:76`). Every one of those was lost on restore, so
 * a snapshot taken mid-cast restored into a world where the cast had never happened — and clients
 * were never granted specs at all (`Abilities.kt:76` returns early unless `isServer`), which made
 * prediction structurally impossible rather than merely unimplemented.
 *
 * Everything here is a primitive or a [NetId], so a `Replicator` lowers it and a restore rebuilds
 * it exactly. Instances are preallocated per slot and reused, so granting and activating allocate
 * nothing.
 */
public class AbilityInstance internal constructor(
    /** Which slot of the owning component this is. Fixed for the component's life. */
    public val slot: Int,
) {

    /** The [AbilityTable] index granted here, or `-1` for an empty slot. */
    public var abilityIndex: Int = -1
        internal set

    /** Increments per activation, so a stale reference to a finished cast is detectable. */
    public var instanceId: Int = 0
        internal set

    /** Where this activation has got to. */
    public var phase: AbilityPhase = AbilityPhase.Inactive
        internal set

    /** The tick [phase] left [AbilityPhase.Inactive]. */
    public var activatedTick: Tick = Tick.ZERO
        internal set

    /** The cooldown application this activation made, or [EffectHandle.INVALID]. */
    public var cooldownHandle: EffectHandle = EffectHandle.INVALID
        internal set

    /**
     * The client prediction key this activation ran under, or `0`.
     *
     * Reserved: nothing allocates one yet. It is a field rather than a later addition because the
     * cue queue already de-duplicates on it, and because adding a field to a replicated instance
     * later is a wire-format change.
     */
    public var predictionKey: Int = 0
        internal set

    /** What kind of target this activation has. */
    public var targetKind: AbilityTargetKind = AbilityTargetKind.None
        internal set

    /** The single target, when [targetKind] is [AbilityTargetKind.Single]. */
    public var targetId: NetId = NetId.NONE
        internal set

    /** Target x, when [targetKind] is [AbilityTargetKind.Location]. */
    public var targetX: Float = 0f
        internal set

    /** Target y, when [targetKind] is [AbilityTargetKind.Location]. */
    public var targetY: Float = 0f
        internal set

    private val multiTargets = IntArray(MAX_MULTI_TARGETS) { NetId.NONE.raw }

    /** How many entries of the multi-target list are meaningful. */
    public var multiTargetCount: Int = 0
        internal set

    /**
     * Typed scratch an exec uses in place of a captured variable.
     *
     * Fixed size and preallocated: a closure cannot be snapshotted, and an exec that stored a
     * `Float` in a field would be storing it on a singleton shared by every entity running the
     * ability. Four of each covers "how far through the spin am I, which target did I pick" — an
     * ability needing more wants a component of its own.
     */
    public val scratchFloats: FloatArray = FloatArray(SCRATCH_SLOTS)

    /** Integer scratch. See [scratchFloats]. */
    public val scratchInts: IntArray = IntArray(SCRATCH_SLOTS)

    /** True when this slot holds a granted ability. */
    public val isGranted: Boolean get() = abilityIndex >= 0

    /** True when an activation is in flight. */
    public val isActive: Boolean get() = phase != AbilityPhase.Inactive

    /** The multi-target at [index]. */
    public fun multiTargetAt(index: Int): NetId {
        require(index in 0 until multiTargetCount) { "no multi-target at $index; $multiTargetCount set" }
        return NetId.ofRaw(multiTargets[index])
    }

    /** Sets the target to a single entity. */
    public fun targetSingle(id: NetId) {
        targetKind = AbilityTargetKind.Single
        targetId = id
        multiTargetCount = 0
    }

    /** Sets the target to a point. */
    public fun targetLocation(x: Float, y: Float) {
        targetKind = AbilityTargetKind.Location
        targetX = x
        targetY = y
        multiTargetCount = 0
    }

    /** Appends one entity to the multi-target list. */
    public fun addMultiTarget(id: NetId) {
        check(multiTargetCount < MAX_MULTI_TARGETS) {
            "an ability instance holds at most $MAX_MULTI_TARGETS targets"
        }
        targetKind = AbilityTargetKind.Multi
        multiTargets[multiTargetCount] = id.raw
        multiTargetCount++
    }

    /** Clears the target back to [AbilityTargetKind.None]. */
    public fun clearTarget() {
        targetKind = AbilityTargetKind.None
        targetId = NetId.NONE
        targetX = 0f
        targetY = 0f
        multiTargetCount = 0
    }

    internal fun reset() {
        phase = AbilityPhase.Inactive
        cooldownHandle = EffectHandle.INVALID
        predictionKey = 0
        clearTarget()
        java.util.Arrays.fill(scratchFloats, 0f)
        java.util.Arrays.fill(scratchInts, 0)
    }

    override fun toString(): String = "AbilityInstance(slot=$slot, ability=$abilityIndex, $phase)"

    public companion object {
        /** How many entities one activation may target. */
        public const val MAX_MULTI_TARGETS: Int = 8

        /** Scratch slots of each type. See [scratchFloats]. */
        public const val SCRATCH_SLOTS: Int = 4
    }
}

/**
 * The abilities granted to one entity, in a dense slot array.
 *
 * `findAbilityById` was `abilities.first { it.id == abilityId }` with a `TODO can we do array
 * lookup?` beside it (`common/.../Abilities.kt:92`); a slot **is** the id here, so it is an array
 * index. Slots are preallocated, so granting allocates nothing and an ungranted slot is simply an
 * instance with `abilityIndex == -1`.
 */
public class Abilities(
    /** How many ability slots this entity has. */
    slotCount: Int = DEFAULT_SLOTS,
) : Component<Abilities> {

    init {
        require(slotCount > 0) { "slotCount must be positive, was $slotCount" }
    }

    private val instances: Array<AbilityInstance> = Array(slotCount) { AbilityInstance(it) }

    /** Increments per activation across every slot, giving each activation a distinct id. */
    private var nextInstanceId: Int = 1

    /** How many slots exist. */
    public val slotCount: Int get() = instances.size

    override fun type(): ComponentType<Abilities> = Abilities

    /** The instance in [slot]. O(1): the slot is the index. */
    public fun instanceAt(slot: Int): AbilityInstance {
        require(slot in instances.indices) { "no ability slot $slot; this entity has $slotCount" }
        return instances[slot]
    }

    /** Grants [abilityIndex] into [slot], replacing whatever was there. */
    public fun grant(slot: Int, abilityIndex: Int) {
        val instance = instanceAt(slot)
        instance.abilityIndex = abilityIndex
        instance.reset()
    }

    /** Empties [slot]. */
    public fun revoke(slot: Int) {
        val instance = instanceAt(slot)
        instance.abilityIndex = -1
        instance.reset()
    }

    /** The first granted slot whose ability carries [tag], or `-1`. */
    public fun findSlotByTag(table: AbilityTable, tag: GameplayTag): Int {
        var slot = 0
        while (slot < instances.size) {
            val instance = instances[slot]
            if (instance.isGranted && tag in table.defAt(instance.abilityIndex).tags) return slot
            slot++
        }
        return -1
    }

    /** Issues the next activation id. */
    internal fun nextInstanceId(): Int = nextInstanceId++

    /** The activation counter, so a snapshot can carry it. */
    public val instanceCounter: Int get() = nextInstanceId

    /** Restores the activation counter after a snapshot restore. */
    public fun restoreInstanceCounter(value: Int) {
        require(value >= 1) { "an activation counter starts at 1, was $value" }
        nextInstanceId = value
    }

    override fun toString(): String = "Abilities($slotCount slots)"

    public companion object : ComponentType<Abilities>() {
        /** Slots a champion gets: four abilities plus two item actives. */
        public const val DEFAULT_SLOTS: Int = 6
    }
}

/**
 * What an [AbilityExec] is handed. Rebound per call, never allocated per activation.
 *
 * Explicit `(world, instance)` rather than the old `context(world, spec)` receiver pair: the exec
 * is a singleton, so the instance has to arrive as data. Everything reachable through it is
 * simulation state or a write-only sink — there is no renderer, no audio and no clock.
 */
public class AbilityContext internal constructor(
    /** Every effect definition. */
    public val effects: GameplayEffectTable,
    /** Every ability definition. */
    public val abilities: AbilityTable,
    /** Applies effects and emits their cues. */
    public val applier: EffectApplier,
    /** Where an exec's own cues go. */
    public val cues: GasCueQueue,
) {

    /** The activation this call is about. */
    public var instance: AbilityInstance = EMPTY
        internal set

    /** The entity running it. */
    public var self: NetId = NetId.NONE
        internal set

    /** Its attributes. */
    public var attributes: Attributes? = null
        internal set

    /** Its applied effects. */
    public var appliedEffects: GameplayEffects? = null
        internal set

    /** The tick being simulated. */
    public var tick: Tick = Tick.ZERO
        internal set

    /** The definition of the ability being run. */
    public val def: AbilityDef get() = abilities.defAt(instance.abilityIndex)

    /**
     * Ticks since this activation started. What an exec times its windup and its end against.
     *
     * A subtraction of two [Tick]s and never a stored accumulator: an exec that counted frames
     * into a field of its own would be counting on a singleton shared by every entity running the
     * ability, and a rewind would not restore the count.
     */
    public val elapsedTicks: Long get() = tick.ticksSince(instance.activatedTick)

    /**
     * Whether an exec has asked for this activation to end.
     *
     * Read and cleared by [AbilityActivation] the moment the exec call returns. Deliberately not
     * an immediate `end()`: `onEnd` runs through this same context object, so ending from inside
     * `onActivate` would re-enter the bind and hand `onEnd` a half-built activation.
     */
    internal var endRequested: Boolean = false

    /**
     * Ends this activation at the end of the current exec call.
     *
     * The public replacement for `AbilityExec.endAbility()`, which the old `AbilityExec` had as a
     * method on itself because state lived there. It exists because [AbilityInstance.phase] has an
     * `internal` setter: without it a game module - which is every real consumer of this API - can
     * start an ability and has no way to say it has finished. That is not a limitation an exec can
     * work around; the ability simply never ends, blocks its own next activation forever
     * ([ActivationResult.AlreadyActive]) and holds the slot for the life of the entity.
     */
    public fun endAbility() {
        endRequested = true
    }

    /**
     * Parks this activation in [AbilityPhase.AwaitingTarget].
     *
     * The phase an exec sits in while it waits for the thing it cannot decide itself - a cursor,
     * a server confirmation. It still ticks; it is a label on what the tick is for.
     */
    public fun awaitTarget() {
        instance.phase = AbilityPhase.AwaitingTarget
    }

    /** Moves a parked activation back to [AbilityPhase.Active], its target settled. */
    public fun resumeActive() {
        instance.phase = AbilityPhase.Active
    }

    internal fun bind(
        instance: AbilityInstance,
        self: NetId,
        attributes: Attributes,
        effects: GameplayEffects,
        tick: Tick,
    ) {
        this.instance = instance
        this.self = self
        this.attributes = attributes
        this.appliedEffects = effects
        this.tick = tick
    }

    private companion object {
        val EMPTY = AbilityInstance(-1)
    }
}
