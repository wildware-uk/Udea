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

    /**
     * `@Sim` on a `val`. The same defect as [NET_ON_VAL] on the snapshot side: capture reads
     * the field every tick and `Replicator.apply` writes it back, and neither is possible for
     * a `val`, so the field would occupy a mask bit that can never be restored.
     *
     * Only `udea-codegen`'s KSP processor raises this today; spec section 3.2 assigns the
     * `@Net` half to the K2 FIR checker and is silent on `@Sim`. It is registered here anyway,
     * because this object's own KDoc says the id space is shared and "a rule that only one of
     * them can raise still lives here" -- an id minted locally by a producer would not be an
     * id at all.
     */
    public val SIM_ON_VAL: UdeaRule = UdeaRule(
        id = "UDEA0005",
        defaultSeverity = Severity.Error,
        description = "@Sim annotates a val, which can never change and so can never be snapshotted",
    )

    /**
     * A `@Net`/`@Sim` property whose type no generator can store in a field store. Today that
     * means anything outside `Int`, `Long`, `Float`, `Boolean` and enums, since a field codec
     * (`@NetCodecFor`) is not implemented.
     *
     * Registered for the same reason as [SIM_ON_VAL]: it is a build-failing error an author
     * hits, and it sat beside three id-carrying errors without one of its own.
     */
    public val UNSUPPORTED_FIELD_TYPE: UdeaRule = UdeaRule(
        id = "UDEA0006",
        defaultSeverity = Severity.Error,
        description = "a @Net/@Sim property has a type udea-codegen cannot replicate",
    )

    /**
     * A `@Q` whose *arguments* cannot form a quantisation, as distinct from
     * [QUANTIZED_NON_FLOAT], which is about the annotated property's type.
     *
     * Three shapes, one defect — "these three numbers do not describe a mapping":
     *
     * - `bits` outside `1..32`, the width `BitWriter.writeBits` accepts;
     * - `min`/`max` inverted or non-finite, so every value clamps to one output;
     * - any of the three not a compile-time constant, since all three are folded into the
     *   generated codec as literals and there is nothing to read at run time.
     *
     * They share an id because they share a fix (correct the annotation) and a consequence:
     * unchecked, each one produces a file that *compiles* and then throws from `write` on the
     * first tick that field changes — on a server, in front of players.
     *
     * Registered rather than left to a bare `logger.error` for the reason this object exists:
     * both `udea-codegen`'s KSP builder and `udea-compiler-plugin`'s FIR checkers declined to
     * mint an id locally, and a producer-local id is not an id. `udea-codegen` is the only
     * producer today — the FIR checker would have to constant-evaluate the annotation
     * arguments, and the id is here first precisely so that when it does, the developer does
     * not learn a second number for a defect they have already seen once.
     */
    public val MALFORMED_QUANTIZATION: UdeaRule = UdeaRule(
        id = "UDEA0007",
        defaultSeverity = Severity.Error,
        description = "@Q arguments do not describe a quantisation: bits outside 1..32, an " +
            "inverted or non-finite range, or an argument that is not a compile-time constant",
    )

    /**
     * The shortest description [AGENT_TOOL_DESCRIPTION] accepts.
     *
     * Not a style preference. The description is the only text a model has when it decides
     * whether to reach for a tool, so a tool named well and described badly is worse than no
     * tool: it gets called for the wrong reason and its result is trusted. Twenty characters
     * is roughly "what it does, and when" and is short enough that no honest description
     * fails it.
     */
    public const val MIN_TOOL_DESCRIPTION: Int = 20

    /**
     * An `@AgentTool` whose description is blank or shorter than [MIN_TOOL_DESCRIPTION].
     *
     * Spec section 6 makes description quality a Phase 1 exit criterion, and the reference
     * implementation this generalises asserted it in a *test* that regex-parsed Kotlin source
     * (`FruitGameKTX`'s `DebugManifestTest`). Here it is an error at the symbol, so the tool
     * cannot reach an agent undescribed in the first place.
     */
    public val AGENT_TOOL_DESCRIPTION: UdeaRule = UdeaRule(
        id = "UDEA0008",
        defaultSeverity = Severity.Error,
        description = "@AgentTool has no description, or one shorter than " +
            "$MIN_TOOL_DESCRIPTION characters; the description is what the model reasons over",
    )

    /**
     * A parameter of an `@AgentTool` function with no `@Arg` description.
     *
     * Same defect as [AGENT_TOOL_DESCRIPTION] one level down: the argument's description is
     * the only thing telling a model what to put in it, and a JSON Schema property with no
     * `description` is a guess the model has to make.
     */
    public val AGENT_ARG_DESCRIPTION: UdeaRule = UdeaRule(
        id = "UDEA0009",
        defaultSeverity = Severity.Error,
        description = "an @AgentTool parameter carries no @Arg description, so its JSON Schema " +
            "property tells the model nothing",
    )

    /**
     * An `@AgentTool` parameter whose type has no JSON Schema mapping and no coercion from
     * the query string a tool call arrives as.
     *
     * The mapping is closed on purpose. The generator this replaces answered an unrecognised
     * type with a blind serialisation fallback, which turned an unsupported parameter into a
     * runtime failure instead of a build failure.
     */
    public val AGENT_TOOL_UNSUPPORTED_TYPE: UdeaRule = UdeaRule(
        id = "UDEA0010",
        defaultSeverity = Severity.Error,
        description = "an @AgentTool parameter has a type with no JSON Schema mapping",
    )

    /**
     * `@AgentState` on a property that is not a scalar.
     *
     * The bridge contract for `GET /state` says of the `game` block: "scalar fields are
     * included in the digest. Nested objects and arrays are not." So a non-scalar here is not
     * a value that renders oddly, it is a value that vanishes from every digest an agent ever
     * reads, with nothing anywhere reporting it. Scalars-only has to hold by construction.
     */
    public val AGENT_STATE_NON_SCALAR: UdeaRule = UdeaRule(
        id = "UDEA0011",
        defaultSeverity = Severity.Error,
        description = "@AgentState annotates a non-scalar property, which the digest's game " +
            "block silently drops",
    )

    /**
     * Two `@AgentTool`s, or two `@AgentState` properties, resolving to one effective name.
     *
     * A name is what a caller addresses, so a collision is not a merge: one of the two
     * declarations becomes unreachable and which one depends on iteration order. Reported
     * rather than resolved, naming both declarations.
     */
    public val AGENT_NAME_COLLISION: UdeaRule = UdeaRule(
        id = "UDEA0012",
        defaultSeverity = Severity.Error,
        description = "two agent declarations resolve to the same effective name, so one of " +
            "them is unreachable",
    )

    /**
     * A `reference<T>("...")` that resolves to a real asset of the wrong kind.
     *
     * Separate from [UNRESOLVED_REFERENCE] because the two have different fixes and different
     * did-you-means: an unresolved id wants a spelling correction, a mismatched kind wants a
     * different id or a different type argument, and a suggestion list built from the whole
     * catalog would be noise for the second. Both the K2 checker in `udea-compiler-plugin` and
     * the asset validator in `udea-assets-compiler` raise this id, which is the whole point of
     * it being here rather than in either of them.
     */
    public val REFERENCE_KIND_MISMATCH: UdeaRule = UdeaRule(
        id = "UDEA0013",
        defaultSeverity = Severity.Error,
        description = "reference(\"...\") names an asset whose kind is not the referenced type",
    )

    /**
     * The compile-time asset catalog on the classpath declares a format version this build
     * cannot read.
     *
     * Loud rather than silent, and this is the rule that makes it so. The tempting behaviour —
     * treat an unreadable catalog as an empty one — is precisely wrong: an empty catalog is
     * *defined* to be silent (a module with no assets must compile), so a version bump would
     * turn every `reference("...")` in the project into an unvalidated string with nothing
     * anywhere saying so. That is the silent-failure shape section 1 of the engineering
     * standards forbids, so it gets an id and a message naming both versions.
     */
    public val ASSET_INDEX_FORMAT: UdeaRule = UdeaRule(
        id = "UDEA0014",
        defaultSeverity = Severity.Error,
        description = "the asset index on the classpath is written in a format version this " +
            "build cannot read, so no reference is validated",
    )

    /** Every registered rule, in id order. */
    public val all: List<UdeaRule> = listOf(
        NET_ON_VAL,
        COMPONENT_FIELD_LIMIT,
        QUANTIZED_NON_FLOAT,
        UNRESOLVED_REFERENCE,
        SIM_ON_VAL,
        UNSUPPORTED_FIELD_TYPE,
        MALFORMED_QUANTIZATION,
        AGENT_TOOL_DESCRIPTION,
        AGENT_ARG_DESCRIPTION,
        AGENT_TOOL_UNSUPPORTED_TYPE,
        AGENT_STATE_NON_SCALAR,
        AGENT_NAME_COLLISION,
        REFERENCE_KIND_MISMATCH,
        ASSET_INDEX_FORMAT,
    ).sortedBy { it.id }

    private val byId: Map<String, UdeaRule> = all.associateBy { it.id }

    /** The rule with this id, or `null` if it is not registered. */
    public fun byId(id: String): UdeaRule? = byId[id]
}
