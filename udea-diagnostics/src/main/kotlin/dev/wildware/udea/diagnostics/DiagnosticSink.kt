package dev.wildware.udea.diagnostics

/**
 * The finished output of a [DiagnosticSink]: what to show, and how much was hidden.
 */
public data class DiagnosticReport(
    /** Deduped, root-cause-collapsed, ranked and capped. */
    public val diagnostics: List<UdeaDiagnostic>,
    /**
     * How many reported diagnostics were accepted but not emitted, because they were
     * knock-on effects of a defect already in [diagnostics] or because the cap was hit.
     *
     * Exact `(ruleId, span)` duplicates are *not* counted: a duplicate carries no
     * information, so hiding it hides nothing.
     */
    public val suppressedCount: Int,
) {
    /** True when at least one [Severity.Error] survived to [diagnostics]. */
    public val hasErrors: Boolean get() = diagnostics.any { it.severity == Severity.Error }
}

/**
 * Collects diagnostics and turns a pile of them into something an agent can act on in one
 * turn (spec section 5).
 *
 * Three things happen in [build], in this order:
 *
 * 1. **Dedupe.** The same rule firing twice at the same [SourceSpan] is one defect. When a
 *    diagnostic has no span, its [UdeaDiagnostic.assetId] stands in for the location, so two
 *    unlocated diagnostics about different assets are not mistaken for each other.
 * 2. **Root-cause collapse.** A diagnostic with [UdeaDiagnostic.causedBy] set is a
 *    *consequence*. If the primary defect it names is itself reported, the consequence is
 *    dropped entirely. If it is not — the usual case, where five blueprints reference one
 *    asset id that simply does not exist — all the consequences sharing that `causedBy`
 *    collapse to the single highest-ranked one. Five referrers, one diagnostic.
 * 3. **Rank and cap.** By [Severity] first, then root causes before consequences, then by
 *    source location, then by rule id and message. Severity outranks root-cause-ness only
 *    because the cap is a truncation: ordering root causes first outright would let twenty-five
 *    [Severity.Info] root causes push a build-failing [Severity.Error] off the end of the list.
 *    Within one severity, root causes come first. The order is total and deterministic, so two
 *    producers given the same input emit byte-identical `diagnostics.json`. At most [cap]
 *    survive; the rest are counted in [DiagnosticReport.suppressedCount].
 *
 * A sink is not thread-safe. Producers that fan out (parallel FIR checkers, Gradle workers)
 * should collect per-thread and merge with [reportAll].
 */
public class DiagnosticSink(
    /** The maximum number of diagnostics [build] will emit. */
    public val cap: Int = MAX_DIAGNOSTICS,
) {
    init {
        require(cap > 0) { "DiagnosticSink.cap must be positive, was $cap" }
    }

    private val accepted = LinkedHashMap<DedupeKey, UdeaDiagnostic>()
    private var duplicates = 0

    /** How many times [report] has been called. */
    public val reportedCount: Int get() = accepted.size + duplicates

    /**
     * Records [diagnostic].
     *
     * Returns `false` if it was discarded immediately as a duplicate of one already held.
     * Collapsing and capping happen later, in [build].
     */
    public fun report(diagnostic: UdeaDiagnostic): Boolean {
        val key = DedupeKey(
            diagnostic.ruleId,
            diagnostic.span,
            if (diagnostic.span == null) diagnostic.assetId else null,
        )
        if (accepted.containsKey(key)) {
            duplicates++
            return false
        }
        accepted[key] = diagnostic
        return true
    }

    /** Records every diagnostic in [diagnostics]; see [report]. */
    public fun reportAll(diagnostics: Iterable<UdeaDiagnostic>) {
        diagnostics.forEach(::report)
    }

    /** Discards everything reported so far. */
    public fun clear() {
        accepted.clear()
        duplicates = 0
    }

    /** Applies collapse, ranking and the cap. Does not consume the sink. */
    public fun build(): DiagnosticReport {
        val ranked = accepted.values.sortedWith(RANK)

        // A defect is "primary" when it is reported about an asset and is not itself derived.
        val primaryDefects = ranked.asSequence()
            .filter { !it.isDerived && it.assetId != null }
            .map { requireNotNull(it.assetId) }
            .toSet()

        val kept = ArrayList<UdeaDiagnostic>(ranked.size)
        val collapsedCauses = HashSet<String>()
        var suppressed = 0
        for (diagnostic in ranked) {
            val cause = diagnostic.causedBy
            if (cause != null && (cause in primaryDefects || !collapsedCauses.add(cause))) {
                suppressed++
                continue
            }
            kept += diagnostic
        }

        if (kept.size <= cap) return DiagnosticReport(kept, suppressed)
        return DiagnosticReport(kept.subList(0, cap).toList(), suppressed + kept.size - cap)
    }

    private data class DedupeKey(
        val ruleId: String,
        val span: SourceSpan?,
        val assetIdWhenUnlocated: String?,
    )

    public companion object {
        /** Spec section 5: never show an agent more than this many diagnostics at once. */
        public const val MAX_DIAGNOSTICS: Int = 25

        /**
         * Severity, then root causes before consequences, then location, then id and message.
         *
         * Total and deterministic: two distinct diagnostics only compare equal when every
         * ranked field matches, and the sort is stable for that remainder.
         */
        private val RANK: Comparator<UdeaDiagnostic> =
            compareBy<UdeaDiagnostic> { it.severity.ordinal }
                .thenBy { if (it.isDerived) 1 else 0 }
                .thenBy { if (it.span == null) 1 else 0 }
                .thenBy { it.span?.path ?: "" }
                .thenBy { it.span?.startLine ?: 0 }
                .thenBy { it.span?.startColumn ?: 0 }
                .thenBy { it.span?.endLine ?: 0 }
                .thenBy { it.span?.endColumn ?: 0 }
                .thenBy { it.assetId ?: "" }
                .thenBy { it.ruleId }
                .thenBy { it.message }
    }
}
