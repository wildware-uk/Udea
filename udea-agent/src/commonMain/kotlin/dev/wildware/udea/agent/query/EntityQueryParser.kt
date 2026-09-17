package dev.wildware.udea.agent.query

import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentToolException

/**
 * Turns the agent-facing text form of a query into an [EntityQuery].
 *
 * ## Why the engine owns the parsing and not the tool
 *
 * The tool that exposes `query_entities` owns its argument names and its description; it does
 * not get to own what `health.current<400` *means*, because `describe_entity`, a game tool and
 * a future `desync_report` all have to agree with it. One parser, here, beside the resolution
 * rules in [AgentComponentIndex].
 *
 * ## The grammar, in full
 *
 * ```
 * with   := name (',' name)*                       Champion,Health
 * where  := term (',' term)*                       team=1,health.current<400
 * term   := path op literal                        op is one of = != < <= > >=
 * near   := x ',' y ',' radius                     40,22,15
 * fields := key (',' key)*                         id,pos,health.current
 * ```
 *
 * That is the entire grammar and it is meant to stay that entire. A comma separates; nothing
 * escapes or quotes, so a literal containing a comma is not expressible - deliberately, because
 * the alternative is a quoting scheme, and a quoting scheme is the first half of an expression
 * language. A filter that needs one is a game tool.
 *
 * Every failure is an [AgentToolException] carrying a kind, so a mistyped field arrives as
 * `no_such_field` with the available names rather than as an empty result set. An empty result
 * set is the single most expensive wrong answer here: it looks like knowledge.
 */
public object EntityQueryParser {

    /**
     * Parses the text form. Every argument is optional; `null` and blank both mean "absent".
     *
     * @param with comma-separated component names.
     * @param where comma-separated field predicates.
     * @param near `x,y,radius` in world units.
     * @param fields comma-separated projections; `id` and `pos` are the two special keys.
     */
    public fun parse(
        index: AgentComponentIndex,
        with: String? = null,
        where: String? = null,
        near: String? = null,
        fields: String? = null,
        limit: Int = EntityQuery.DEFAULT_LIMIT,
        offset: Int = 0,
    ): EntityQuery {
        val components = parseWith(index, with)
        return EntityQuery(
            with = components,
            where = parseWhere(index, where, components),
            near = parseNear(near),
            fields = parseFields(index, fields, components),
            limit = limit.coerceAtMost(EntityQuery.MAX_LIMIT).coerceAtLeast(1),
            offset = offset.coerceAtLeast(0),
        )
    }

    /** The components named in [text]. */
    public fun parseWith(index: AgentComponentIndex, text: String?): List<AgentComponentType> =
        split(text).map { index.requireByName(it) }

    /** The predicates in [text], resolved against [scope]. */
    public fun parseWhere(
        index: AgentComponentIndex,
        text: String?,
        scope: List<AgentComponentType>,
    ): List<FieldPredicate> = split(text).map { term -> parseTerm(index, term, scope) }

    /** The projections in [text], resolved against [scope]. */
    public fun parseFields(
        index: AgentComponentIndex,
        text: String?,
        scope: List<AgentComponentType>,
    ): List<Projection> = split(text).map { key ->
        when (key.lowercase()) {
            Projection.Id.key -> Projection.Id
            Projection.Position.key -> {
                // Resolved eagerly so "this world has no positions" is reported by the call
                // that asked for one, not by an empty-looking result.
                index.requirePosition()
                Projection.Position
            }

            else -> Projection.Field(index.resolveField(key, scope))
        }
    }

    /** The circle in `x,y,radius` form, or `null`. */
    public fun parseNear(text: String?): ProximityFilter? {
        val parts = split(text)
        if (parts.isEmpty()) return null
        if (parts.size != NEAR_PARTS) {
            throw AgentToolException(
                AgentErrorKind.BAD_QUERY,
                "near takes x,y,radius - three numbers - and got ${parts.size}: $text",
            )
        }
        val x = number(parts[0], "near.x")
        val y = number(parts[1], "near.y")
        val radius = number(parts[2], "near.radius")
        if (radius < 0f) {
            throw AgentToolException(AgentErrorKind.BAD_QUERY, "near radius must not be negative, was $radius")
        }
        return ProximityFilter(x, y, radius)
    }

    private fun parseTerm(
        index: AgentComponentIndex,
        term: String,
        scope: List<AgentComponentType>,
    ): FieldPredicate {
        // Longest operators first: `<=` must not be read as `<` followed by a literal of `=400`.
        for (comparison in ORDERED_BY_LENGTH) {
            val at = term.indexOf(comparison.symbol)
            if (at <= 0) continue
            val path = term.substring(0, at).trim()
            val literal = term.substring(at + comparison.symbol.length).trim()
            if (literal.isEmpty()) {
                throw AgentToolException(
                    AgentErrorKind.BAD_QUERY,
                    "$term has no value after ${comparison.symbol}",
                )
            }
            return FieldPredicate(index.resolveField(path, scope), comparison, literal)
        }
        throw AgentToolException(
            AgentErrorKind.BAD_QUERY,
            "$term is not a predicate; write field=value, or one of " +
                Comparison.entries.joinToString { it.symbol },
        )
    }

    private fun number(text: String, what: String): Float =
        text.toFloatOrNull() ?: throw AgentToolException(
            AgentErrorKind.BAD_QUERY,
            "$what must be a number, was $text",
        )

    private fun split(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private const val NEAR_PARTS: Int = 3

    /**
     * Comparisons longest-symbol-first, so a two-character operator is found before the
     * one-character operator it starts with.
     */
    private val ORDERED_BY_LENGTH: List<Comparison> =
        Comparison.entries.sortedByDescending { it.symbol.length }
}
