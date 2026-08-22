package dev.wildware.udea.diagnostics

/**
 * One entry in the [UdeaRules] registry.
 *
 * ### Stability contract
 *
 * A rule [id] is permanent and public API.
 *
 * - An id, once released, **always means the same defect**. It is never renumbered, never
 *   reused for a different defect, and never recycled after a rule is retired.
 * - A retired rule's id is burned: the constant may be removed, but the number is never
 *   handed to a new rule.
 * - [defaultSeverity] and [description] may be refined between releases; a severity change is
 *   a behaviour change and belongs in release notes. Diagnostic *message* text is not part of
 *   this contract at all and may be reworded freely.
 * - Ids are what suppression files, CI filters and agent prompts key on, and they are the
 *   thing the K2 checkers and the asset validator must agree on, so they outlive any one
 *   producer's implementation.
 */
public data class UdeaRule(
    /** Stable id matching [UdeaRules.ID_FORMAT], e.g. `UDEA0001`. */
    public val id: String,
    public val defaultSeverity: Severity,
    /** One line, present tense, describing the defect the rule detects. */
    public val description: String,
) {
    init {
        require(UdeaRules.ID_FORMAT.matches(id)) {
            "rule id '$id' does not match ${UdeaRules.ID_FORMAT.pattern}"
        }
        require(description.isNotBlank()) { "UdeaRule.description must not be blank" }
    }

    /**
     * Builds a [UdeaDiagnostic] for this rule, taking the id and (by default) the severity
     * from the registry so that two producers of the same rule cannot drift apart.
     */
    public fun diagnostic(
        message: String,
        span: SourceSpan? = null,
        assetId: String? = null,
        fix: Fix? = null,
        causedBy: String? = null,
        severity: Severity = defaultSeverity,
    ): UdeaDiagnostic = UdeaDiagnostic(severity, id, message, span, assetId, fix, causedBy)
}

/**
 * The registry of stable Udea rule ids.
 *
 * Both sides of the spec section 5 "same rule ids" contract import this object: the K2 FIR
 * checkers in `udea-compiler-plugin` and the asset validator in `udea-assets-compiler`. A
 * rule that only one of them can raise still lives here, because the id space is shared.
 *
 * See [UdeaRule] for the stability contract that governs the ids.
 */
public object UdeaRules {
    /** The only legal shape for a rule id. */
    public val ID_FORMAT: Regex = Regex("UDEA[0-9]{4}")

    /**
     * The field-mask width that [COMPONENT_FIELD_LIMIT] enforces (spec section 3.2: one
     * `u64` mask per component).
     */
    public const val MAX_COMPONENT_FIELDS: Int = 64

    /**
     * `@Net` on a `val`. Replication is capture-and-diff (spec section 3.2) and a `val` can
     * never change, so annotating one is always a mistake rather than a no-op.
     */
    public val NET_ON_VAL: UdeaRule = UdeaRule(
        id = "UDEA0001",
        defaultSeverity = Severity.Error,
        description = "@Net annotates a val, which can never change and so can never replicate",
    )

    /** A component declares more fields than one field mask can address. */
    public val COMPONENT_FIELD_LIMIT: UdeaRule = UdeaRule(
        id = "UDEA0002",
        defaultSeverity = Severity.Error,
        description = "component declares more than $MAX_COMPONENT_FIELDS replicated or " +
            "snapshotted fields, which overflows the field mask",
    )

    /** `@Q` quantization applied to a property that is not a float. */
    public val QUANTIZED_NON_FLOAT: UdeaRule = UdeaRule(
        id = "UDEA0003",
        defaultSeverity = Severity.Error,
        description = "@Q annotates a non-float property; quantization is only defined for floats",
    )

    /** A `reference("...")` that names an asset id nothing declares. */
    public val UNRESOLVED_REFERENCE: UdeaRule = UdeaRule(
        id = "UDEA0004",
        defaultSeverity = Severity.Error,
        description = "reference(\"...\") names an asset id that no asset declares",
    )

    /** Every registered rule, in id order. */
    public val all: List<UdeaRule> = listOf(
        NET_ON_VAL,
        COMPONENT_FIELD_LIMIT,
        QUANTIZED_NON_FLOAT,
        UNRESOLVED_REFERENCE,
    ).sortedBy { it.id }

    private val byId: Map<String, UdeaRule> = all.associateBy { it.id }

    /** The rule with this id, or `null` if it is not registered. */
    public fun byId(id: String): UdeaRule? = byId[id]
}
