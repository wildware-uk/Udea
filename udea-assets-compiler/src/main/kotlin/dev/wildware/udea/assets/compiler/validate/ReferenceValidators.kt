package dev.wildware.udea.assets.compiler.validate

import dev.wildware.udea.diagnostics.Fix
import dev.wildware.udea.diagnostics.Replacement
import dev.wildware.udea.diagnostics.SourceSpan
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import dev.wildware.udea.diagnostics.UdeaRule
import dev.wildware.udea.diagnostics.UdeaRules

/**
 * `reference("...")` names an asset nothing declares.
 *
 * The check spec section 1 claim #2 is about: a bad reference is a compile error with a file, a
 * line and a did-you-mean, not a crash forty seconds into a match. What it replaces is
 * `Assets.find` throwing mid-frame with the whole asset map interpolated into the message.
 *
 * ### One defect, one diagnostic
 *
 * Every diagnostic carries [UdeaDiagnostic.causedBy] set to the **missing id**, not to the
 * referrer. That is what makes five blueprints pointing at one absent `character/orc` produce
 * one diagnostic instead of five: `DiagnosticSink` collapses everything sharing a `causedBy`
 * whose named defect is not itself reported. This validator does not dedupe — it reports every
 * site honestly, and would be wrong to do anything else, because the sink is also what decides
 * *which* of the five sites is the one worth showing.
 */
public object UnresolvedReferenceValidator : AssetValidator {

    override val rules: List<UdeaRule> = listOf(UdeaRules.UNRESOLVED_REFERENCE)

    override fun validate(context: ValidationContext): List<UdeaDiagnostic> =
        context.refSites.mapNotNull { site ->
            if (context.resolve(site.ref.id) != null) return@mapNotNull null
            val suggestion = context.didYouMean(site.ref.id)
            val span = context.spanFor(site.owner, site.ref.origin)
            UdeaRules.UNRESOLVED_REFERENCE.diagnostic(
                message = buildString {
                    append("`${site.owner.id}` references `${site.ref.id}` from its `${site.field}`, ")
                    append("but no asset declares that id")
                    if (suggestion != null) append("; did you mean `$suggestion`?")
                },
                span = span,
                assetId = site.owner.id,
                causedBy = site.ref.id,
                fix = suggestion?.let { replacementFix(site.ref.origin, it) },
            )
        }

    /**
     * A one-edit repair, and only when the span is genuinely the text of the id literal.
     *
     * Pass 1's `ReferenceSite.span` covers the whole string-literal expression, quotes
     * included, so replacing it with `"suggestion"` is exact. A span that came from pass 2's
     * *stack capture* instead is line-only (`column 0`, zero width) and replacing that would
     * insert the suggestion at the start of the line and corrupt the file — so a fix is offered
     * only for a span that spans some text. A missing fix costs a diagnostic nothing; a wrong
     * one costs an agent a broken file.
     */
    private fun replacementFix(span: SourceSpan?, suggestion: String): Fix? {
        if (span == null) return null
        val spansText = span.endLine > span.startLine || span.endColumn > span.startColumn
        if (!spansText || span.startColumn <= 0) return null
        return Fix(
            description = "change the referenced id to `$suggestion`",
            replacements = listOf(Replacement(span, "\"$suggestion\"")),
        )
    }
}

/**
 * `reference("...")` resolves, but to an asset of the wrong kind.
 *
 * `reference<Blueprint>("character/orc_idle")` pointing at a `SpriteSheet` is a defect here and
 * not a `ClassCastException` inside whatever code first read the resolved value — the failure
 * mode `Ref.expected` exists to delete on the runtime side, checked at build time so it never
 * gets that far.
 *
 * ### Where the expectation comes from
 *
 * An author writes `reference("id")` with no type argument, so the expected kind cannot come
 * from the call site. It comes from the **parameter the reference was passed to**: `AssetScope`
 * stamps [dev.wildware.udea.assets.compiler.Ref.expected] at each DSL signature. See that
 * property for why there is deliberately no table mapping DSL words to types.
 *
 * ### Where it stays silent, and why that is honest
 *
 * - The reference does not resolve at all: that is [UnresolvedReferenceValidator]'s
 *   `UDEA0004`, and reporting a kind mismatch against nothing would be a second diagnostic for
 *   one defect.
 * - The slot does not constrain a kind ([dev.wildware.udea.assets.compiler.Ref.expected] is
 *   null) — `gameConfig`'s `defaultCharacter` points at a `character`, which
 *   `AssetKind.Unpublishable` says has no runtime type.
 * - The **target** has no runtime kind (`DeclaredAsset.kindFqn` is null) for the same reason.
 *   There is no name to compare, and inventing one is exactly what `AssetKind` forbids.
 *
 * The last two are a real gap and not a rounding error: until `udea-assets` has a type behind
 * every DSL word, a reference into or out of `character` is unchecked. It is a gap that shows
 * up as *nothing reported*, which is why it is written down here rather than left to be
 * discovered.
 */
public object ReferenceTypeValidator : AssetValidator {

    override val rules: List<UdeaRule> = listOf(UdeaRules.REFERENCE_KIND_MISMATCH)

    override fun validate(context: ValidationContext): List<UdeaDiagnostic> =
        context.refSites.mapNotNull { site ->
            val expected = site.ref.expected ?: return@mapNotNull null
            val target = context.resolve(site.ref.id) ?: return@mapNotNull null
            val actual = target.kindFqn ?: return@mapNotNull null
            if (actual == expected) return@mapNotNull null

            val suggestion = context.didYouMeanOfKind(site.ref.id, expected)
            UdeaRules.REFERENCE_KIND_MISMATCH.diagnostic(
                message = buildString {
                    append("the `${site.field}` of `${site.owner.id}` must be a ")
                    append("${simpleName(expected)}, but `${site.ref.id}` is a ")
                    append("${simpleName(actual)} (declared by `${target.kind}`")
                    target.origin?.let { append(" at $it") }
                    append(")")
                    if (suggestion != null) append("; did you mean `$suggestion`?")
                },
                span = context.spanFor(site.owner, site.ref.origin),
                assetId = site.owner.id,
            )
        }

    /**
     * `SpriteSheet` from `dev.wildware.udea.assets.SpriteSheet`.
     *
     * The simple name is what an author reads, and the message is not the contract anyway — the
     * rule id is. The message stays unambiguous because both kinds in it are shortened the same
     * way and the declaring DSL word is printed beside the target.
     */
    private fun simpleName(fqn: String): String = fqn.substringAfterLast('.')
}
