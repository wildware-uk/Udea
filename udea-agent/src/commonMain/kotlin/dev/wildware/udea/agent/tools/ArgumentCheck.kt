package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentToolArg

/**
 * Refuses a call that named an argument the tool does not accept.
 *
 * ## The failure this closes, and why it is the worst one on this surface
 *
 * Every generated tool publishes `"additionalProperties": false` in its `inputSchema` - see the
 * `inputSchema` constant on any `<Owner><Fn>Tool` - and nothing enforced it. `AgentCommand` is a
 * `Map<String, String>` of every query parameter the host did not reserve, and a generated
 * `invoke` reads the names it knows and never looks at the rest. So
 * `world.query_entities?limits=200` bound `limit` to its default of 50, silently ignored
 * `limits`, and answered `ok:true` with fifty rows.
 *
 * That is worse than a failure and worse than a wrong answer, because it is a **wrong answer
 * wearing a success**. An agent that mistypes an argument has no signal at all: it reads a
 * plausible page, concludes the world contains fifty matching entities, and spends the rest of
 * the session reasoning from it. Every other refusal on this surface is typed and names what to
 * do instead; this one was not a refusal.
 *
 * ## Why here rather than in the generated tool
 *
 * The check needs the *set* of declared names, and a generated `invoke` only ever mentions them
 * one at a time as it binds them. [AgentToolDef.args][dev.wildware.udea.agent.AgentToolDef.args]
 * is that set, already published, already the thing `/tools` serves and already what the schema
 * was rendered from - so checking against it is checking against the manifest itself rather than
 * against a second list that could drift from it. One check therefore covers the generated
 * tools, the hand-written engine tools and any tool a game writes, including the ones written
 * after this file.
 *
 * ## What it does not check
 *
 * Missing required arguments and unconvertible values, which are already
 * [dev.wildware.udea.agent.BadArgumentException] at the point of binding and already reach the
 * agent as `bad_argument` naming the argument. This adds the third case and nothing else.
 */
public object ArgumentCheck {

    /**
     * An argument the tool does not declare. Names the offender and what is accepted.
     *
     * Its own kind rather than `bad_argument`, because the fix is different in kind: a
     * `bad_argument` says the value is wrong and the name was right, and an agent handed that
     * for a typo would go looking at the value it sent.
     */
    public val UNKNOWN_ARGUMENT: AgentErrorKind = AgentErrorKind("unknown_argument")

    /**
     * The refusal for [supplied], or `null` when every name in it is declared.
     *
     * Reports the **first** offending name in the order the tool declares nothing about - a map's
     * iteration order - so the answer is stable for one call and not promised across calls; the
     * accepted list is in the answer either way, so an agent that sent two typos fixes both from
     * one reply.
     *
     * Runs once per tool call on the simulation thread, at a tick boundary, over a map of at most
     * a handful of entries. It allocates only on the path that is about to fail.
     */
    public fun reject(
        toolName: String,
        declared: List<AgentToolArg>,
        supplied: Set<String>,
    ): AgentResult.Failed? {
        if (supplied.isEmpty()) return null
        for (name in supplied) {
            if (declares(declared, name)) continue
            return AgentResult.failed(UNKNOWN_ARGUMENT, message(toolName, declared, name))
        }
        return null
    }

    /** Whether [name] is one of the declared argument names. A list scan over a handful of entries. */
    private fun declares(declared: List<AgentToolArg>, name: String): Boolean {
        for (position in declared.indices) {
            if (declared[position].name == name) return true
        }
        return false
    }

    /**
     * The refusal text: what was rejected, the nearest declared name, and the whole accepted set.
     *
     * All three, because each answers a different mistake. The nearest name fixes a typo without
     * a round trip; the full list fixes an argument invented from another tool's vocabulary; and
     * naming the offender is what stops an agent re-reading the arguments it got right.
     */
    private fun message(toolName: String, declared: List<AgentToolArg>, offender: String): String {
        if (declared.isEmpty()) {
            return "$toolName does not accept the argument $offender; it takes no arguments at all"
        }
        val accepted = declared.joinToString { it.name }
        val nearest = nearest(declared, offender)
        val suggestion = if (nearest == null) "" else " Did you mean $nearest?"
        return "$toolName does not accept the argument $offender - its schema declares " +
            "additionalProperties:false, so an argument it does not name is a mistake rather " +
            "than something to ignore.$suggestion It accepts $accepted"
    }

    /**
     * The declared name closest to [offender], or `null` when nothing is close enough to suggest.
     *
     * [MAX_SUGGESTION_DISTANCE] rather than always naming the nearest, because "did you mean
     * `fields`?" for an argument called `blueprint` is a suggestion that costs an agent a call to
     * disprove. Three edits is roughly a typo and roughly a plural.
     */
    private fun nearest(declared: List<AgentToolArg>, offender: String): String? {
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (position in declared.indices) {
            val candidate = declared[position].name
            val distance = editDistance(offender.lowercase(), candidate.lowercase())
            if (distance < bestDistance) {
                bestDistance = distance
                best = candidate
            }
        }
        return if (bestDistance <= MAX_SUGGESTION_DISTANCE) best else null
    }

    /**
     * Levenshtein distance, iterative and over one row.
     *
     * Argument names are short and a tool has a handful, so this runs once on a call that is
     * already failing and never on a hot path.
     */
    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    /** Edits beyond which a declared name is a different word rather than a misspelling. */
    private const val MAX_SUGGESTION_DISTANCE: Int = 3
}
