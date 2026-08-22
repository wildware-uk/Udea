package dev.wildware.udea.diagnostics

/**
 * The one diagnostic type (spec section 5).
 *
 * Every producer — the K2 FIR checkers, the KSP processor, the build-time asset validator
 * and the runtime — emits this and nothing else, so that they cannot disagree about rule
 * ids, severities or the shape of `diagnostics.json`.
 */
public data class UdeaDiagnostic(
    public val severity: Severity,
    /** A stable id from [UdeaRules]; see [UdeaRule] for the stability contract. */
    public val ruleId: String,
    /** Human- and agent-readable. Not part of any stability contract; may be reworded. */
    public val message: String,
    /** Where the defect is, if it has a source location at all. */
    public val span: SourceSpan? = null,
    /** The asset this diagnostic is about, e.g. `character/orc`. */
    public val assetId: String? = null,
    /** A machine-applicable repair, when one is unambiguous. */
    public val fix: Fix? = null,
    /**
     * Set when this diagnostic is a *consequence* of another defect, carrying the id of that
     * primary defect (normally the [assetId] of the thing that is missing or broken).
     *
     * This is what makes root-cause ranking work: [DiagnosticSink] collapses everything
     * sharing a `causedBy` down to one diagnostic, so a missing `character/orc` referenced by
     * five blueprints is reported once instead of five times. A producer that knows it is
     * reporting a knock-on effect must set this, because the sink has no other way to tell.
     */
    public val causedBy: String? = null,
) {
    init {
        require(ruleId.isNotBlank()) { "UdeaDiagnostic.ruleId must not be blank" }
        require(message.isNotBlank()) { "UdeaDiagnostic.message must not be blank" }
    }

    /** True when this diagnostic is a knock-on effect of the defect named by [causedBy]. */
    public val isDerived: Boolean get() = causedBy != null

    override fun toString(): String =
        "${span?.toString() ?: "<no location>"}: ${severity.wireName}: [$ruleId] $message"
}
