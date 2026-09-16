package dev.wildware.udea.gas

/**
 * What a [ValueResolver] is allowed to read.
 *
 * A resolver used to take `(Entity, GameplayEffectSpec)` and reach through a world for the
 * attribute set (`common/ability/Attributes.kt:44`). That made every magnitude read a component
 * lookup, and it put a Fleks `Entity` inside a value that assets hold. Narrowing it to these
 * two reads means the recompute loop can implement it on one reusable cursor object — so
 * resolving a magnitude allocates nothing, and a resolver cannot reach anything the loop has
 * not already established is safe to read this tick.
 */
public interface MagnitudeSource {

    /** The *current* value of [id] on the entity being recomputed. */
    public fun attribute(id: AttributeId): Float

    /** The set-by-caller magnitude for [tag] on the effect being applied, or `0`. */
    public fun setByCaller(tag: GameplayTag): Float
}

/**
 * How a magnitude, a bound or a duration gets its number.
 *
 * Ported forward from `common/ability/Attributes.kt` unchanged in spirit — a constant, another
 * attribute's current value, or a value the caller set on the spec — with two changes:
 *
 * - it resolves against a [MagnitudeSource] rather than reaching into a world;
 * - [MIN] is `-Float.MAX_VALUE`, not `Float.MIN_VALUE`.
 *
 * That second one was a live defect. `Float.MIN_VALUE` is the smallest *positive* float
 * (~1.4e-45), so `common/ability/Attributes.kt:59` gave every attribute a default lower bound
 * just above zero and silently clamped every negative value — a debuff that should have driven
 * an attribute below zero could not, and nothing reported it.
 */
public sealed class ValueResolver {

    /** The value this resolver yields for [source]. Must not allocate: it runs per tick. */
    public abstract fun resolve(source: MagnitudeSource): Float

    /** A fixed number. */
    public class Constant(public val value: Float) : ValueResolver() {
        override fun resolve(source: MagnitudeSource): Float = value

        override fun toString(): String = "Constant($value)"
    }

    /** Another attribute's current value — `max = value(::maxHealth)` in the old DSL. */
    public class Attribute(public val id: AttributeId) : ValueResolver() {
        override fun resolve(source: MagnitudeSource): Float = source.attribute(id)

        override fun toString(): String = "Attribute($id)"
    }

    /** A magnitude the caller set on the spec, keyed by tag. */
    public class SetByCaller(public val tag: GameplayTag) : ValueResolver() {
        override fun resolve(source: MagnitudeSource): Float = source.setByCaller(tag)

        override fun toString(): String = "SetByCaller($tag)"
    }

    public companion object {
        /** Zero. */
        public val ZERO: ValueResolver = Constant(0f)

        /**
         * The default lower bound: the most negative finite float.
         *
         * Not `Float.MIN_VALUE`, which is positive — see this class's KDoc.
         */
        public val MIN: ValueResolver = Constant(-Float.MAX_VALUE)

        /** The default upper bound. */
        public val MAX: ValueResolver = Constant(Float.MAX_VALUE)
    }
}

/** A constant magnitude. The authoring spelling. */
public fun value(constant: Float): ValueResolver = ValueResolver.Constant(constant)

/** Another attribute's current value. The authoring spelling. */
public fun value(attribute: AttributeId): ValueResolver = ValueResolver.Attribute(attribute)

/** A caller-supplied magnitude. The authoring spelling. */
public fun value(tag: GameplayTag): ValueResolver = ValueResolver.SetByCaller(tag)
