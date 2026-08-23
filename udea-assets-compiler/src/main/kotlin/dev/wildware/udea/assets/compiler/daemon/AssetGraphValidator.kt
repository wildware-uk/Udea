package dev.wildware.udea.assets.compiler.daemon

import dev.wildware.udea.assets.compiler.AssetGraph
import dev.wildware.udea.assets.compiler.DeclaredAsset
import dev.wildware.udea.assets.compiler.Ref
import dev.wildware.udea.diagnostics.DidYouMean
import dev.wildware.udea.diagnostics.Fix
import dev.wildware.udea.diagnostics.Replacement
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import dev.wildware.udea.diagnostics.UdeaRules

/**
 * Does every `reference("...")` in a graph name something the graph declares?
 *
 * ## Root cause, once
 *
 * Five assets referencing one misspelled id is **one** defect, and reporting it five times spends
 * five of the twenty-five diagnostics spec 5 allows an agent on a single typo. So this groups by
 * the *unresolved target*: one diagnostic per missing id, carrying the first referrer's span - the
 * place an author edits - and naming the rest in the message. Issue #92's acceptance ("a fixture
 * with one unresolved reference and five referrers returns exactly one diagnostic") is this
 * grouping, not a cap applied afterwards to a longer list.
 *
 * ## Its scope, and the seam
 *
 * Reference **existence** only. It is deliberately not a validator suite:
 *
 * - a reference's expected *kind* is [Ref.expected]'s, and the checker for it is
 *   `validate/ReferenceTypeValidator`, landing under issue #88 concurrently with this;
 * - a repeated id is `AssetValidationRules.DUPLICATE_ID`, in the same place. This file minted no
 *   id for it precisely so that there is one id for one defect when the two meet.
 *
 * When #88's suite lands, the daemon calls it and this object is deleted: the daemon calls
 * [validate] through one line in [AssetDaemon], and `AssetGraphValidatorTest` pins the behaviour
 * the replacement has to keep. Existence is here at all because a daemon that cannot tell an agent
 * "you typed `charater/orc`, did you mean `character/orc`?" is a daemon an agent cannot use, and
 * that is the entire premise of issue #92's tool surface.
 */
public object AssetGraphValidator {

    /**
     * Every unresolved reference in [graph], one per missing id, in missing-id order.
     *
     * The suggestion comes from [DidYouMean] against the graph's own ids - the same function
     * [dev.wildware.udea.assets.AssetRegistry] uses for a runtime miss. Spec 5 requires the
     * build-time and the run-time miss to suggest the same thing, and two implementations of an
     * edit-distance threshold is how they come to disagree.
     */
    public fun validate(graph: AssetGraph): List<UdeaDiagnostic> {
        val declared = graph.ids
        val referrers = LinkedHashMap<String, MutableList<Pair<DeclaredAsset, Ref>>>()
        for (asset in graph.assets.values) {
            for (ref in asset.refs()) {
                if (ref.id in declared) continue
                referrers.getOrPut(ref.id) { mutableListOf() } += asset to ref
            }
        }
        return referrers.entries.sortedBy { it.key }.map { (missing, sites) ->
            val (firstAsset, firstRef) = sites.first()
            val suggestion = DidYouMean.suggest(missing, declared)
            UdeaRules.UNRESOLVED_REFERENCE.diagnostic(
                message = buildString {
                    append("reference(\"").append(missing).append("\") names an asset nothing declares")
                    if (sites.size > 1) {
                        append("; referenced by ").append(sites.size).append(" assets (")
                        append(sites.map { it.first.id }.distinct().sorted().joinToString(", "))
                        append(')')
                    }
                    if (suggestion != null) append(". Did you mean \"").append(suggestion).append("\"?")
                },
                span = firstRef.origin ?: firstAsset.origin,
                assetId = firstAsset.id,
                fix = suggestion?.let { fixFor(firstRef, it) },
            )
        }
    }

    /**
     * The suggestion as an applicable edit, when the reference's span is wide enough to rewrite.
     *
     * Only when the origin covers a column range on one line. An origin recovered from a stack
     * frame is line-only (`startColumn == endColumn == 0`), and a replacement over a zero-width
     * span at column 0 inserts the suggestion at the start of the line instead of replacing the
     * string. A `Fix` that corrupts the file is worse than no `Fix`, so this returns null and the
     * message carries the suggestion in prose regardless.
     */
    private fun fixFor(ref: Ref, suggestion: String): Fix? {
        val span = ref.origin ?: return null
        if (span.startLine != span.endLine || span.endColumn <= span.startColumn) return null
        return Fix(
            description = "rename the reference to \"$suggestion\"",
            replacements = listOf(Replacement(span, "\"$suggestion\"")),
        )
    }

    /** Every [Ref] anywhere in an asset's field values, in declaration order. */
    private fun DeclaredAsset.refs(): List<Ref> = fields.values.flatMap(::refsIn)

    private fun refsIn(value: Any?): List<Ref> = when (value) {
        is Ref -> listOf(value)
        is Iterable<*> -> value.flatMap(::refsIn)
        is Map<*, *> -> value.values.flatMap(::refsIn)
        else -> emptyList()
    }
}
