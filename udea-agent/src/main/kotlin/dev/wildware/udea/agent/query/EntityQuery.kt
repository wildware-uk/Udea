package dev.wildware.udea.agent.query

import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentToolException

/**
 * What an agent is asking for. Deliberately a filter, not a language.
 *
 * No joins, no expressions, no ordering by a computed value, no `or`. Everything here reduces
 * to "walk the live entities once and keep the ones that match", which is what makes the cost
 * predictable enough to put on the simulation thread. Anything that needs more is a game tool,
 * where the game can answer it in one pass with knowledge this engine does not have.
 *
 * The five filters compose with `and`:
 *
 * - [with] - the entity carries every one of these components;
 * - [where] - every field predicate holds;
 * - [near] - the entity is inside a circle;
 * - [fields] - what the result carries per entity (`id` is always included);
 * - [offset] / [limit] - one page, alongside the unpaged [QuerySummary.total].
 */
public class EntityQuery(
    /** Components the entity must have. Also the scope a bare field name resolves against. */
    public val with: List<AgentComponentType> = emptyList(),
    /** Field predicates, all of which must hold. */
    public val where: List<FieldPredicate> = emptyList(),
    /** A circle the entity must be inside, or `null`. */
    public val near: ProximityFilter? = null,
    /** What to report per entity. Empty means the id alone. */
    public val fields: List<Projection> = emptyList(),
    /** Page size. Capped at [MAX_LIMIT]; the summary reports whether more matched. */
    public val limit: Int = DEFAULT_LIMIT,
    /** How many matches to skip. */
    public val offset: Int = 0,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "limit must be in 1..$MAX_LIMIT, was $limit" }
        require(offset >= 0) { "offset must not be negative, was $offset" }
    }

    override fun toString(): String =
        "EntityQuery(with=${with.joinToString { it.name }}, where=${where.size}, " +
            "near=${near != null}, fields=${fields.size}, limit=$limit, offset=$offset)"

    public companion object {
        /**
         * Entities returned when the caller does not say.
         *
         * Twenty is about 600 bytes of JSON: enough to see a pattern, small enough that an
         * agent that asked the wrong question has not spent its context window finding out.
         */
        public const val DEFAULT_LIMIT: Int = 20

        /**
         * The hard cap on one page.
         *
         * A cap and not a suggestion, because the failure it prevents is the one this whole
         * tier exists for: a `limit=100000` from an agent that has decided to read the world
         * would rebuild the 80KB document the digest was split up to avoid.
         */
        public const val MAX_LIMIT: Int = 200
    }
}

/** How a [FieldPredicate] compares. */
public enum class Comparison(
    /** The spelling an agent writes. */
    public val symbol: String,
    /** Whether the comparison needs a value it can order, rather than only compare. */
    public val ordered: Boolean,
) {
    Equal("=", false),
    NotEqual("!=", false),
    Less("<", true),
    LessOrEqual("<=", true),
    Greater(">", true),
    GreaterOrEqual(">=", true),
    ;

    /** Applies this comparison to the sign of a `compareTo`. */
    public fun holds(comparison: Int): Boolean = when (this) {
        Equal -> comparison == 0
        NotEqual -> comparison != 0
        Less -> comparison < 0
        LessOrEqual -> comparison <= 0
        Greater -> comparison > 0
        GreaterOrEqual -> comparison >= 0
    }
}

/**
 * One `field op value` test.
 *
 * The literal is parsed once, at construction, rather than per entity: a query over 500
 * entities would otherwise parse the same string 500 times, and the parse would be inside the
 * budget the query is measured against.
 */
public class FieldPredicate(
    /** The field being tested. */
    public val field: FieldRef,
    /** How it is compared. */
    public val comparison: Comparison,
    /** The right-hand side, as the agent wrote it. */
    public val literal: String,
) {
    private val numeric: Double? = literal.toDoubleOrNull()

    init {
        if (comparison.ordered && numeric == null) {
            throw AgentToolException(
                AgentErrorKind.BAD_QUERY,
                "$field ${comparison.symbol} $literal compares an order against something that " +
                    "is not a number; use = or != for text",
            )
        }
    }

    /**
     * Whether [value] - a field read through the generated `Replicator` - satisfies this test.
     *
     * A `null` value means the entity does not carry the component at all. That satisfies only
     * [Comparison.NotEqual]: an absent field is not equal to anything, and treating it as a
     * match for `=` would quietly widen every query over an optional component.
     */
    public fun matches(value: Any?): Boolean {
        if (value == null) return comparison == Comparison.NotEqual

        val asNumber = FieldValues.numericOrNull(value)
        if (asNumber != null && numeric != null) {
            return comparison.holds(asNumber.compareTo(numeric))
        }

        val text = FieldValues.textOf(value)
        return when (comparison) {
            Comparison.Equal -> text.equals(literal, ignoreCase = true)
            Comparison.NotEqual -> !text.equals(literal, ignoreCase = true)
            else -> throw AgentToolException(
                AgentErrorKind.BAD_QUERY,
                "$field holds $text, which cannot be ordered against $literal",
            )
        }
    }

    override fun toString(): String = "$field${comparison.symbol}$literal"
}

/**
 * A circle an entity must be inside.
 *
 * Compared by squared distance, so a proximity filter over 500 entities costs 500 multiplies
 * and no square roots.
 */
public class ProximityFilter(
    /** Circle centre, world units. */
    public val x: Float,
    /** Circle centre, world units. */
    public val y: Float,
    /** Radius, world units. */
    public val radius: Float,
) {
    init {
        require(radius >= 0f) { "radius must not be negative, was $radius" }
        require(!x.isNaN() && !y.isNaN() && !radius.isNaN()) { "a proximity filter cannot be NaN" }
    }

    private val radiusSquared: Float = radius * radius

    /** Whether the point is inside, boundary included. */
    public fun contains(px: Float, py: Float): Boolean {
        val dx = px - x
        val dy = py - y
        return dx * dx + dy * dy <= radiusSquared
    }

    override fun toString(): String = "ProximityFilter($x, $y, r=$radius)"
}

/**
 * One thing a result carries per entity.
 *
 * Sealed so the renderer handles every case or fails to compile, and so `pos` stays a
 * first-class projection rather than a magic string compared in three places.
 */
public sealed interface Projection {

    /** The key this projection writes in the result object. */
    public val key: String

    /**
     * The entity's id.
     *
     * Always rendered whether or not it is asked for - a result an agent cannot address is not
     * a result - so naming it here is a no-op that keeps `"fields":"id,pos"` from being an
     * error.
     */
    public object Id : Projection {
        override val key: String get() = "id"

        override fun toString(): String = key
    }

    /** The entity's position, as `[x, y]`. Needs a component carrying a lowered `position`. */
    public object Position : Projection {
        override val key: String get() = "pos"

        override fun toString(): String = key
    }

    /** One component field, keyed by the path the agent wrote. */
    public class Field(
        /** The resolved field. */
        public val ref: FieldRef,
    ) : Projection {
        override val key: String get() = ref.path

        override fun toString(): String = key
    }
}
