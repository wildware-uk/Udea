package dev.wildware.udea.agent.query

import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentToolException

/**
 * Every component an agent can see, and the rules for naming a field of one.
 *
 * ## Addressing
 *
 * A field path is resolved by exactly two rules, and they are stated here because an agent has
 * to be able to predict them from the target shape in the design (`"where":"team=1,
 * health.current<400"`):
 *
 * 1. **`Component.field`** - if the first dotted segment names a registered component
 *    (case-insensitively), the rest of the path is that component's field name. So
 *    `health.current` is `current` on `Health`, and `transform.position.x` is `position.x` on
 *    `Transform` - the remainder is matched whole, which is what makes a lowered composite
 *    field (`position.x`) addressable at all.
 * 2. **a bare field name** - matched against the query's `with` set first, and against every
 *    registered component if the `with` set does not have it. Either way it must match exactly
 *    one: two matches is a typed error naming both, because guessing which `current` the agent
 *    meant is worse than asking. The fallback exists because the design's own example filters
 *    on `team=1` while naming only `Champion,Health` in `with`, and an agent should not have to
 *    list a component merely to mention one of its fields.
 *
 * Ambiguity and absence both fail loudly, with the candidates listed. An agent that mistypes a
 * field is the common case, not the exception, and a silently empty result set is the answer
 * that costs it the most time.
 *
 * ## Position
 *
 * `near` and the `pos` projection need to know where an entity is, and no interface in the
 * engine says "this component is the position". The convention is the one the `Replicator`
 * contract already fixes: a composite value is lowered to one field per primitive with a dotted
 * path, so the position is the component carrying **`position.x`** and **`position.y`**. It is
 * resolved once, here, at construction; a query that needs it when no component has it gets a
 * typed error naming the convention rather than an empty result.
 *
 * When *two* components carry it - a `Transform` beside an interpolation or previous-frame
 * transform, both lowered by the same convention - nothing is guessed. A host settles it with
 * `positionComponent`; a host that has not gets the same typed `bad_query` naming both, which
 * is the rule this class already applies to an ambiguous field name.
 */
public class AgentComponentIndex(
    types: List<AgentComponentType>,
    /**
     * The component that carries the authoritative position, when more than one does.
     *
     * Two components lowered by the same `Replicator` convention - a `Transform` beside an
     * interpolation or previous-frame transform - both carry `position.x` and `position.y`, and
     * there is nothing in either to say which one a `near` filter means. Naming one here is how
     * a host settles it; leaving it null when only one component matches is the normal case.
     */
    positionComponent: String? = null,
) {

    private val ordered: List<AgentComponentType> = types.sortedBy { it.name }

    init {
        require(ordered.isNotEmpty()) { "an index with no components can answer no query" }
        val duplicate = ordered.groupBy { it.name.lowercase() }.entries.firstOrNull { it.value.size > 1 }
        require(duplicate == null) {
            "two components are registered as ${duplicate?.key}; component names are how an " +
                "agent addresses them and must be distinct"
        }
    }

    /** How many component types are registered. */
    public val size: Int get() = ordered.size

    /** The component at [index], in name order. */
    public fun typeAt(index: Int): AgentComponentType = ordered[index]

    /** Every registered component, in name order. */
    public fun all(): List<AgentComponentType> = ordered

    /** The component named [name], case-insensitively, or `null`. */
    public fun findByName(name: String): AgentComponentType? =
        ordered.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * The component named [name].
     *
     * @throws AgentToolException `no_such_field` naming what is registered. The agent is
     *   choosing from a list it may not have read; the list is the useful half of the answer.
     */
    public fun requireByName(name: String): AgentComponentType =
        findByName(name) ?: throw AgentToolException(
            AgentErrorKind.NO_SUCH_FIELD,
            "no component named $name; registered: ${ordered.joinToString { it.name }}",
        )

    /**
     * Where an entity is, or `null` when nothing registered carries a lowered `position`.
     *
     * Resolved once at construction rather than per query: the answer cannot change, and a
     * per-query scan would put a string comparison per component on the path a proximity filter
     * runs 500 times.
     */
    public val position: PositionRef? = resolvePosition(ordered, positionComponent)

    /**
     * Every component carrying a lowered position, when there is more than one and no host
     * nomination settled it. Empty otherwise. Named in [requirePosition]'s refusal.
     */
    private val positionCandidates: List<String> =
        if (position != null) emptyList() else candidatePositions(ordered).map { it.component.name }

    /**
     * [position], or a typed failure.
     *
     * @throws AgentToolException `bad_query` explaining the convention, because "near returned
     *   nothing" and "this world has no positions" are different problems with different fixes.
     */
    public fun requirePosition(): PositionRef {
        position?.let { return it }
        // Two answers is a different problem from none, and it is the one that used to be
        // silent: picking the alphabetically first of `Transform` and `PreviousTransform` gave
        // every `near` filter and every `pos` projection whichever sorted first, with no
        // diagnostic - the exact opposite of the rule this class enforces for an ambiguous
        // field name ten lines up.
        if (positionCandidates.size > 1) {
            throw AgentToolException(
                AgentErrorKind.BAD_QUERY,
                "${positionCandidates.joinToString()} all carry $POSITION_X and $POSITION_Y, so " +
                    "there is no one place this world keeps a position; the host must nominate " +
                    "one when it builds the component index",
            )
        }
        throw AgentToolException(
            AgentErrorKind.BAD_QUERY,
            "no registered component has the lowered fields $POSITION_X and $POSITION_Y, so " +
                "proximity and the pos projection have nothing to measure against",
        )
    }

    /**
     * Resolves [path] against [scope] by the two rules in the class KDoc.
     *
     * [scope] is the query's `with` set. An empty scope means every registered component, which
     * is what `describe_entity`-shaped callers want.
     */
    public fun resolveField(path: String, scope: List<AgentComponentType>): FieldRef {
        require(path.isNotBlank()) { "a field path cannot be blank" }

        val separator = path.indexOf('.')
        if (separator > 0) {
            val owner = findByName(path.substring(0, separator))
            if (owner != null) {
                val fieldName = path.substring(separator + 1)
                val fieldIndex = owner.fieldIndexOf(fieldName)
                if (fieldIndex < 0) {
                    throw AgentToolException(
                        AgentErrorKind.NO_SUCH_FIELD,
                        "${owner.name} has no field $fieldName; it has " +
                            owner.fieldNames.joinToString(),
                    )
                }
                return FieldRef(owner, fieldIndex, path)
            }
        }

        // The query's own component set first, so a name it also declared elsewhere means the
        // one the query is about. Then everything registered, because the design's own example
        // filters on `team=1` while naming only `Champion,Health` in `with` - an agent should
        // not have to list a component merely to mention one of its fields.
        return findUnique(path, scope)
            ?: findUnique(path, ordered)
            ?: throw AgentToolException(
                AgentErrorKind.NO_SUCH_FIELD,
                "no field $path on ${ordered.joinToString { it.name }}",
            )
    }

    private fun findUnique(path: String, candidates: List<AgentComponentType>): FieldRef? {
        var found: FieldRef? = null
        for (component in candidates) {
            val fieldIndex = component.fieldIndexOf(path)
            if (fieldIndex < 0) continue
            if (found != null) {
                throw AgentToolException(
                    AgentErrorKind.BAD_QUERY,
                    "$path is ambiguous: both ${found.component.name} and ${component.name} " +
                        "have it; qualify it as ${component.name}.$path",
                )
            }
            found = FieldRef(component, fieldIndex, path)
        }
        return found
    }

    override fun toString(): String = "AgentComponentIndex(${ordered.joinToString { it.name }})"

    private companion object {
        const val POSITION_X: String = "position.x"
        const val POSITION_Y: String = "position.y"

        fun candidatePositions(types: List<AgentComponentType>): List<PositionRef> {
            var found: MutableList<PositionRef>? = null
            for (component in types) {
                val x = component.fieldIndexOf(POSITION_X)
                val y = component.fieldIndexOf(POSITION_Y)
                if (x < 0 || y < 0) continue
                val into = found ?: ArrayList<PositionRef>(1).also { found = it }
                into.add(PositionRef(component, x, y))
            }
            return found ?: emptyList()
        }

        /**
         * The one position component, or null when there is none - or when there is more than
         * one and [nominated] did not settle it. Never a guess: [requirePosition] turns the
         * ambiguous case into a typed refusal naming every candidate.
         */
        fun resolvePosition(types: List<AgentComponentType>, nominated: String?): PositionRef? {
            val candidates = candidatePositions(types)
            if (nominated != null) {
                return candidates.firstOrNull { it.component.name.equals(nominated, ignoreCase = true) }
                    ?: throw IllegalArgumentException(
                        "the nominated position component $nominated does not carry both " +
                            "$POSITION_X and $POSITION_Y; candidates: " +
                            candidates.joinToString { it.component.name }.ifEmpty { "none" },
                    )
            }
            return candidates.singleOrNull()
        }
    }
}

/** One addressable field: which component it is on, and which index. */
public class FieldRef(
    /** The component carrying the field. */
    public val component: AgentComponentType,
    /** Index into `component.fieldNames`, the matching mask bit, and the store column. */
    public val fieldIndex: Int,
    /** The path the caller wrote, used verbatim as the key in a projected result. */
    public val path: String,
) {
    /** The field's declared name, which may differ from [path] when the path was qualified. */
    public val name: String get() = component.fieldNames[fieldIndex]

    override fun toString(): String = "${component.name}.$name"
}

/** The two field indices that hold an entity's position. */
public class PositionRef(
    /** The component carrying the lowered position. */
    public val component: AgentComponentType,
    /** Field index of `position.x`. */
    public val xIndex: Int,
    /** Field index of `position.y`. */
    public val yIndex: Int,
) {
    override fun toString(): String = "PositionRef(${component.name})"
}
