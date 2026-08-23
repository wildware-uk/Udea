package dev.wildware.udea.gas

import dev.wildware.udea.core.Tick

/**
 * How a modifier combines with the value it is applied to.
 *
 * The `ordinal` is load-bearing: it is the first component of the modifier sort key, so
 * every additive modifier applies before every multiplicative one, on every machine. Inserting
 * a constant in the middle changes the arithmetic for every existing effect — do it
 * deliberately.
 *
 * Deliberately **not** carrying a `(Float, Float) -> Float` the way
 * `common/ability/GameplayEffectSpec.kt:89` did. That field is a `Function2<Float, Float, Float>`,
 * so every application boxed both arguments and the result: three allocations per modifier per
 * entity per tick, on the exact path issue #97 gates at zero bytes. [apply] is a `when` instead.
 */
public enum class ModifierType {
    /** `value + magnitude`. */
    Additive,

    /** `value * magnitude`. */
    Multiplicative,

    /** `magnitude`, discarding what was there. */
    Override,
    ;

    /** Combines [value] with [magnitude]. No boxing: a `when`, not a stored lambda. */
    public fun apply(value: Float, magnitude: Float): Float = when (this) {
        Additive -> value + magnitude
        Multiplicative -> value * magnitude
        Override -> magnitude
    }
}

/**
 * How long an applied effect lasts, denominated in ticks.
 *
 * The old type compared an accumulated `Float` of seconds against another `Float`
 * (`GameplayEffectSpec.kt:117`), where the accumulator was summed from a frame delta
 * (`AttributeSystem.kt:52`). Two machines running the same number of ticks disagreed about
 * whether an effect had expired, and a rewind could not restore the accumulator. Here expiry is
 * `now >= appliedTick + durationTicks` — a pure function of two ticks, so it is identical
 * whether the simulation stepped one tick at a time or thirty in one call.
 */
public sealed class GameplayEffectDuration {

    /**
     * How many ticks this lasts for [source], or [INFINITE].
     *
     * @param source resolves a [SetByCaller] duration against the spec that carries it.
     */
    public abstract fun durationTicks(source: MagnitudeSource): Long

    /** Applies once and is gone the same tick. Writes `base`. */
    public data object Instant : GameplayEffectDuration() {
        override fun durationTicks(source: MagnitudeSource): Long = 0L
    }

    /** Never expires on its own. Removed only by an explicit call. */
    public data object Infinite : GameplayEffectDuration() {
        override fun durationTicks(source: MagnitudeSource): Long = INFINITE
    }

    /** A fixed number of ticks. Authoring converts seconds at asset-compile time; see [ticksFromSeconds]. */
    public data class Ticks(public val count: Int) : GameplayEffectDuration() {
        init {
            require(count > 0) { "a Ticks duration must be positive, was $count; use Instant for zero" }
        }

        override fun durationTicks(source: MagnitudeSource): Long = count.toLong()
    }

    /**
     * A duration the caller set on the spec, in ticks.
     *
     * The magnitude is a `Float` because that is what the set-by-caller table holds, and it is
     * truncated toward zero rather than rounded: a caller that means 90 ticks passes `90f`, and
     * a fractional tick is not a thing the simulation can represent.
     */
    public data class SetByCaller(public val tag: GameplayTag) : GameplayEffectDuration() {
        override fun durationTicks(source: MagnitudeSource): Long =
            source.setByCaller(tag).toLong().coerceAtLeast(0L)
    }

    public companion object {
        /** The sentinel [durationTicks] returns for [Infinite]. Never compared as a count. */
        public const val INFINITE: Long = -1L
    }
}

/**
 * The one deterministic seconds-to-ticks rule.
 *
 * Authoring reads in seconds — `duration(1.5f)` is what a designer writes — and everything
 * downstream is ticks. Two builds of the same asset must agree exactly, so the rule is fixed
 * here and stated: **round half up**, `floor(seconds * tickRate + 0.5)`, evaluated in `Double`
 * so a `Float` asset value does not lose a tick at large durations.
 *
 * Half-up rather than Kotlin's `roundToInt` (half-away-from-zero) or `Math.rint` (half-to-even)
 * because it is the rule a designer predicts: 0.5 ticks rounds up, always, in both signs of an
 * offset. Negative input is refused rather than rounded, because a negative duration is a typo.
 *
 * This runs at asset-compile time. Nothing in the simulation calls it — the `udeaVerifyGasTime`
 * gate exists partly to keep it that way.
 */
public fun ticksFromSeconds(seconds: Float, tickRate: Int): Int {
    require(seconds >= 0f) { "a duration in seconds must not be negative, was $seconds" }
    require(tickRate > 0) { "tickRate must be positive, was $tickRate" }
    val ticks = Math.floor(seconds.toDouble() * tickRate + 0.5)
    require(ticks <= Int.MAX_VALUE) { "$seconds seconds at ${tickRate}Hz does not fit in an Int tick count" }
    return ticks.toInt()
}

/**
 * The immutable definition of an effect: what an asset declares, shared by every application.
 *
 * The definition/instance split is carried forward from the old code deliberately — it is why
 * an applied effect costs about twenty bytes of snapshot rather than a whole object graph. What
 * changed is that an *instance* now holds an `Int` index into a [GameplayEffectTable] rather
 * than an `AssetReference`, so the effect list on a component is pure primitives and a snapshot
 * of it pins no asset. That matters beyond size: a `GraphDelta` hot reload can swap the
 * underlying asset while a snapshot of the world still exists (spec 3.6).
 */
public class GameplayEffectDef(
    /** Asset name, for diagnostics and agent output. */
    public val name: String,
    /** Which attribute this modifies, or [AttributeId.NONE] for a tag-only effect. */
    public val target: AttributeId = AttributeId.NONE,
    /** How the magnitude combines. Ignored when [target] is [AttributeId.NONE]. */
    public val modifierType: ModifierType = ModifierType.Additive,
    /** The magnitude. Ignored when [target] is [AttributeId.NONE]. */
    public val magnitude: ValueResolver = ValueResolver.ZERO,
    /** How long an application lasts. */
    public val duration: GameplayEffectDuration = GameplayEffectDuration.Instant,
    /**
     * Ticks between periodic applications, or `0` for none.
     *
     * Replaces `GameplayEffect.period: kotlin.time.Duration?`. A `kotlin.time.Duration` in
     * simulation state is a seconds-denominated value pretending to be exact; the conversion
     * happens once, at asset-compile time, through [ticksFromSeconds].
     */
    public val periodTicks: Int = 0,
    /** Tags this effect carries while applied. Blocking checks read these. */
    public val tags: TagSet,
    /** Cue ids emitted on application. An `IntArray`, so a spec never holds a cue object. */
    public val cueIds: IntArray = EMPTY_CUES,
) {

    init {
        require(name.isNotEmpty()) { "a gameplay effect name must not be empty" }
        require(periodTicks >= 0) { "periodTicks must not be negative, was $periodTicks" }
        require(!(duration is GameplayEffectDuration.Instant && periodTicks > 0)) {
            "'$name' is Instant and periodic at once; an instant effect has no second application"
        }
    }

    /**
     * True when this effect writes `base` rather than contributing to `current`.
     *
     * Instant and periodic effects are permanent changes — damage taken stays taken. Duration
     * effects are derived: they contribute to `current` only, which is what makes `current` a
     * pure function of `(base, active effects)` and therefore what makes a rewind incapable of
     * leaving a corrupted stat behind.
     */
    public val isPermanent: Boolean
        get() = duration is GameplayEffectDuration.Instant || periodTicks > 0

    /** True when this effect actually modifies an attribute. */
    public val modifiesAttribute: Boolean get() = target.index >= 0

    override fun toString(): String = "GameplayEffectDef($name)"

    public companion object {
        /** Shared empty cue list, so a cue-less definition allocates nothing. */
        public val EMPTY_CUES: IntArray = IntArray(0)
    }
}

/**
 * Every effect definition in one game, addressed by dense index.
 *
 * The index is what lives on a component and in a snapshot. It comes from a sorted name list
 * for the same reason attribute ids do: two builds must agree about which index is which.
 */
public class GameplayEffectTable private constructor(
    private val defs: Array<GameplayEffectDef>,
    private val indexByName: Map<String, Int>,
) {

    /** How many effects exist. Indices are `0 until size`. */
    public val size: Int get() = defs.size

    /** The definition at [index]. */
    public fun defAt(index: Int): GameplayEffectDef {
        require(index in defs.indices) { "no gameplay effect at index $index; the table holds $size" }
        return defs[index]
    }

    /** The index of the effect named [name]. */
    public fun indexOf(name: String): Int =
        indexByName[name] ?: throw NoSuchEffectException(name, defs.map { it.name })

    override fun toString(): String = "GameplayEffectTable($size effects)"

    public companion object {
        /** Builds a table, assigning indices by ascending name. */
        public fun of(defs: List<GameplayEffectDef>): GameplayEffectTable {
            val sorted = defs.sortedBy { it.name }
            val byName = HashMap<String, Int>(sorted.size * 2)
            sorted.forEachIndexed { index, def ->
                require(byName.put(def.name, index) == null) {
                    "two gameplay effects are named '${def.name}'; effect indices come from " +
                        "sorted names, so a duplicate makes them ambiguous"
                }
            }
            return GameplayEffectTable(sorted.toTypedArray(), byName)
        }
    }
}

/** An effect name no [GameplayEffectTable] knows. */
public class NoSuchEffectException(
    public val name: String,
    public val known: List<String>,
) : IllegalArgumentException(
    "no gameplay effect named '$name'; the table holds ${known.size}: ${known.joinToString(limit = 16)}",
)

/**
 * When an application made at [appliedTick] with [durationTicks] expires.
 *
 * Free function rather than a method so both the recompute loop and a test can state the rule
 * without an instance: expiry is `now >= appliedTick + durationTicks`, and an [GameplayEffectDuration.INFINITE]
 * duration never expires.
 */
internal fun hasExpired(now: Tick, appliedTick: Tick, durationTicks: Long): Boolean =
    when (durationTicks) {
        GameplayEffectDuration.INFINITE -> false
        else -> now.value >= appliedTick.value + durationTicks
    }
