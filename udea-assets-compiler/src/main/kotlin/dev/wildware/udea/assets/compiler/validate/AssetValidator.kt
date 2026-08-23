package dev.wildware.udea.assets.compiler.validate

import dev.wildware.udea.diagnostics.DiagnosticReport
import dev.wildware.udea.diagnostics.DiagnosticSink
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import dev.wildware.udea.diagnostics.UdeaRule

/**
 * One check over a validated asset graph.
 *
 * A validator **collects**; it never aborts and it never decides what an author sees. It hands
 * back every defect it found and [AssetValidatorPipeline] feeds them to the
 * [DiagnosticSink], which is the only thing in the repository that ranks, collapses and caps.
 * That split is why one unresolved id referenced from five places is reported once: the
 * validator honestly reports five, tags each with [UdeaDiagnostic.causedBy], and the sink
 * collapses them.
 */
public interface AssetValidator {

    /** A name for the validator, used when it fails. Its simple class name by default. */
    public val name: String get() = javaClass.simpleName

    /**
     * Every rule this validator can raise.
     *
     * Declared rather than discovered so `RegisteredRuleIdsTest` can assert that each one is in
     * a registry — the ids are what suppression files, CI filters and agent prompts key on, and
     * an id minted at a call site is not an id.
     */
    public val rules: List<UdeaRule>

    /** Every defect [context] holds that this validator is responsible for. */
    public fun validate(context: ValidationContext): List<UdeaDiagnostic>
}

/**
 * Pass 3 of spec 3.6: run every validator, collect everything, rank and cap once.
 *
 * ### It does not stop at the first defect
 *
 * Every validator sees the whole graph, and a validator that throws is turned into an
 * [AssetValidationRules.VALIDATOR_FAILED] diagnostic instead of taking the run down. An
 * author with five distinct defects gets told about five, not one. This is not a convenience:
 * the loop it replaces is an agent burning one build per defect.
 *
 * ### It does not invent ranking
 *
 * Dedupe, root-cause collapse, ordering and the twenty-five cap are all [DiagnosticSink]'s, in
 * `udea-diagnostics`, shared with the K2 checkers. Reimplementing any of it here is how two
 * producers come to disagree about what `diagnostics.json` says.
 */
public class AssetValidatorPipeline(
    /** In declaration order; the sink re-orders, so this only affects nothing but readability. */
    public val validators: List<AssetValidator> = DEFAULT,
    /** Spec section 5's cap. Overridable only so a test can prove the truncation happens. */
    private val cap: Int = DiagnosticSink.MAX_DIAGNOSTICS,
) {

    /** Every defect in [context], ranked root-cause-first and capped. */
    public fun validate(context: ValidationContext): DiagnosticReport {
        val sink = DiagnosticSink(cap)
        for (validator in validators) {
            val found = try {
                validator.validate(context)
            } catch (failure: Throwable) {
                listOf(
                    AssetValidationRules.VALIDATOR_FAILED.diagnostic(
                        message = "${validator.name} threw ${failure.javaClass.simpleName}: " +
                            "${failure.message}. This is a defect in the asset validator itself; " +
                            "the assets it was checking may or may not be valid.",
                    ),
                )
            }
            sink.reportAll(found)
        }
        return sink.build()
    }

    public companion object {
        /**
         * Every validator pass 3 runs, in a fixed order.
         *
         * Root-cause checks first as a readability convention only — the sink's ranking is what
         * actually decides what an author sees first, and it is a total order over the
         * diagnostics rather than over the validators that produced them.
         */
        public val DEFAULT: List<AssetValidator> = listOf(
            DuplicateIdValidator,
            UnresolvedReferenceValidator,
            ReferenceTypeValidator,
            BlueprintCycleValidator,
            MissingFileValidator,
            SpriteSheetGeometryValidator,
            AnimationNotifyValidator,
            DeterminismValidator,
        )
    }
}
