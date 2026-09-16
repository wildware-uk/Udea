package dev.wildware.udea.diagnostics

import kotlin.math.abs

/**
 * Levenshtein "did you mean" suggestions.
 *
 * Spec section 5 makes this mandatory rather than a nicety. A diagnostic that says
 * `unknown asset 'charater/orc'` costs an agent a whole turn spent listing the asset
 * directory; one that says `did you mean 'character/orc'?` lets it self-correct in the same
 * turn. Every producer that reports an unresolved name is expected to attach a suggestion
 * when one exists.
 */
public object DidYouMean {
    /**
     * The closest entry of [known] to [candidate] within [maxDistance] edits, or `null` if
     * nothing is close enough.
     *
     * Distance is computed case-insensitively, because a wrong-case identifier is exactly the
     * kind of typo this exists to catch; the returned string is the entry from [known], with
     * its original case. Ties break by natural ordering of the [known] entries, so the result
     * never depends on the iteration order of the caller's collection.
     */
    public fun suggest(
        candidate: String,
        known: Iterable<String>,
        maxDistance: Int = defaultMaxDistance(candidate),
    ): String? {
        require(maxDistance >= 0) { "maxDistance must not be negative, was $maxDistance" }
        val needle = candidate.lowercase()
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (entry in known) {
            // Cheap reject: strings this different in length cannot be within the budget.
            if (abs(entry.length - needle.length) > maxDistance) continue
            val distance = distance(needle, entry.lowercase())
            if (distance > maxDistance) continue
            val currentBest = best
            if (distance < bestDistance || (distance == bestDistance && currentBest != null && entry < currentBest)) {
                best = entry
                bestDistance = distance
            }
        }
        return best
    }

    /**
     * How many edits still counts as a typo, scaled to the length of [candidate].
     *
     * A flat budget is wrong at both ends: three edits turn `orc` into any other three-letter
     * word, while one edit is too mean for a long path like `character/orc/idle`.
     */
    public fun defaultMaxDistance(candidate: String): Int = when {
        candidate.length <= 4 -> 1
        candidate.length <= 8 -> 2
        else -> 3
    }

    /**
     * Case-sensitive Levenshtein edit distance: the number of single-character insertions,
     * deletions and substitutions that turn [a] into [b].
     */
    public fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            val aChar = a[i - 1]
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (aChar == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
