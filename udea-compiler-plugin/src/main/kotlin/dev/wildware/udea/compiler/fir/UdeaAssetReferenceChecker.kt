package dev.wildware.udea.compiler.fir

import dev.wildware.udea.compiler.assets.AssetCatalogProblem
import dev.wildware.udea.compiler.assets.AssetCatalogSource
import dev.wildware.udea.diagnostics.UdeaRule
import dev.wildware.udea.diagnostics.UdeaRules
import dev.wildware.udea.diagnostics.assets.AssetCatalog
import dev.wildware.udea.diagnostics.assets.AssetCatalogEntry
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirEvaluatorResult
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirExpressionEvaluator
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirNamedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirStringConcatenationCall
import org.jetbrains.kotlin.fir.expressions.PrivateConstantEvaluatorAPI
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.isSubtypeOf
import org.jetbrains.kotlin.fir.types.renderReadable
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.StandardClassIds
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `reference("charater/orc")` is red at the literal, before the build runs.
 *
 * This is the headline capability of the K2 half of the codegen story (spec 3.2): the asset
 * validator can only speak at the `udeaValidateAssets` task boundary, so it cannot make a typo
 * red in an editor, and KSP structurally cannot see the *inside* of a function body at all. A
 * FIR expression checker can do both.
 *
 * ### The same ids as the asset validator
 *
 * Spec section 5 is explicit that a developer must never see two ids for one problem, so this
 * checker mints nothing: `UdeaRules.UNRESOLVED_REFERENCE` and `UdeaRules.REFERENCE_KIND_MISMATCH`
 * are the same constants `udea-assets-compiler` reports `.udea.kts` defects under.
 * `UdeaRuleParityTest` asserts every id this class can raise is in that registry.
 *
 * ### Silence is the default, everywhere
 *
 * A false red squiggle on `reference(someVariable)` would be worse than the status quo, which
 * is why every ambiguous case returns rather than guesses:
 *
 * - argument is not a compile-time constant string -> silent;
 * - the classpath carries no asset index -> silent (a module with no assets must compile, and
 *   so must the whole tree with `-Pudea.compilerPlugin.enabled=false`, where this class is
 *   never loaded at all);
 * - the id resolves but its kind is not on this compilation's classpath -> silent, because the
 *   subtype question cannot be asked, never mind answered;
 * - the call's type argument is still a type variable -> silent.
 *
 * The one thing that is deliberately *loud* is an index this build cannot read: see
 * [reportIndexProblems].
 */
internal class UdeaAssetReferenceChecker(
    private val catalog: AssetCatalogSource,
) : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {

    /**
     * Whether the "this index is unreadable" diagnostic has already been raised.
     *
     * One instance of this checker exists per FIR session, so this is once per compilation.
     * Without it a broken index would produce one identical error per `reference(...)` in the
     * project, which is the "one diagnostic per referrer for one missing thing" that spec
     * section 5's ranking rule forbids.
     */
    private val indexProblemsReported = AtomicBoolean(false)

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val session = context.session
        val assetRefArguments = assetRefArguments(expression, session)
        if (assetRefArguments.isEmpty()) return

        val scan = catalog.scan()
        reportIndexProblems(scan.problems, expression.source)
        // Empty index -> silent. Checked after the problem report and not before it, because an
        // index this build cannot read decodes to *no* entries, and going quiet on that is
        // exactly the silent failure the loud diagnostic exists to prevent.
        if (scan.catalog.isEmpty) return

        for ((argument, _) in assetRefArguments) {
            val literal = constantString(argument, session) ?: continue
            checkOneReference(literal, argument, expression, scan.catalog, session)
        }
    }

    /**
     * The arguments of [expression] that hold an asset id.
     *
     * Two ways in, for the reason [UdeaAssetReferences] documents: the `@AssetRef` marker when
     * it survived to the class file, and the callable id when it did not.
     */
    private fun assetRefArguments(
        expression: FirFunctionCall,
        session: FirSession,
    ): List<Map.Entry<FirExpression, FirValueParameter>> {
        val callee = expression.calleeReference.toResolvedCallableSymbol() ?: return emptyList()
        val mapping = expression.resolvedArgumentMapping ?: return emptyList()
        val byCallableId = callee.callableId == UdeaAssetReferences.REFERENCE
        return mapping.entries.filter { (_, parameter) ->
            parameter.isString() &&
                (byCallableId || parameter.hasAnnotation(UdeaAssetReferences.ASSET_REF, session))
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkOneReference(
        id: String,
        argument: FirExpression,
        call: FirFunctionCall,
        catalog: AssetCatalog,
        session: FirSession,
    ) {
        // The literal's own source, not the call's: issue #41 requires the squiggle to sit on
        // "charater/orc" rather than on `reference(...)`, so that a click lands on the typo.
        val source = argument.unwrapped().source ?: argument.source

        val entry = catalog.resolve(id)
        if (entry == null) {
            report(UdeaRules.UNRESOLVED_REFERENCE, source, unresolvedMessage(id, catalog))
            return
        }
        val expected = expectedKind(call) ?: return
        val actual = kindType(entry, session) ?: return
        if (!actual.isSubtypeOf(expected, session)) {
            report(
                UdeaRules.REFERENCE_KIND_MISMATCH,
                source,
                "the asset '$id' is a ${entry.kindFqn}, which is not a " +
                    "${expected.renderReadable()}. Reference an asset of the expected kind, or " +
                    "change the type argument.",
            )
        }
    }

    /**
     * `no asset declares '<id>'`, plus up to [AssetCatalog.MAX_SUGGESTIONS] candidates.
     *
     * The did-you-mean is not decoration. Spec section 5 makes it mandatory because it is what
     * lets an agent fix a typo in the same turn instead of spending one listing the asset tree,
     * and it is why this checker earns its keep over a grep.
     */
    private fun unresolvedMessage(id: String, catalog: AssetCatalog): String {
        val suggestions = catalog.nearest(id)
        val head = "no asset declares the id '$id'"
        return when {
            suggestions.size == 1 -> "$head. Did you mean '${suggestions.single()}'?"
            suggestions.size > 1 ->
                "$head. Did you mean one of ${suggestions.joinToString { "'$it'" }}?"
            // No near miss at all: say how big the haystack is, so "the index is stale" and
            // "I typed something unlike anything that exists" are distinguishable.
            else -> "$head. The asset index on the classpath holds ${catalog.ids.size} ids, " +
                "none of them close to this one."
        }
    }

    /**
     * The call's type argument `T`, or `null` when the kind question cannot be asked.
     *
     * `null` for an unresolved projection and for a type that is still a type variable — an
     * inferred `T` that resolution left open is not a claim about anything, and reporting a
     * mismatch against it would be inventing an expectation the author never wrote.
     */
    private fun expectedKind(call: FirFunctionCall): ConeKotlinType? {
        val projection = call.typeArguments.firstOrNull() as? FirTypeProjectionWithVariance ?: return null
        val type = projection.typeRef.coneTypeOrNull ?: return null
        return type.takeUnless { it is ConeTypeParameterType }
    }

    /**
     * The indexed kind as a type, or `null` when this compilation cannot see that class.
     *
     * Silent rather than an error: an index merged from an upstream module can perfectly well
     * name a class that is not on *this* module's classpath, and that is a fact about the
     * module graph, not a defect in the author's code.
     *
     * The name is read as a **top-level** class - last segment the class, the rest the package.
     * A nested asset kind therefore does not resolve and the kind check goes quiet on it,
     * which is the right way round: the alternative is guessing where the package stops, and a
     * wrong guess produces a `UDEA0013` naming a class that does not exist.
     */
    private fun kindType(entry: AssetCatalogEntry, session: FirSession): ConeKotlinType? {
        val classId = ClassId.topLevel(FqName(entry.kindFqn))
        val symbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) ?: return null
        return symbol.defaultType()
    }

    /**
     * Raises `UdeaRules.ASSET_INDEX_FORMAT` once per compilation if any index was unreadable.
     *
     * Reported at the first asset reference the compilation contains rather than at the file or
     * the module, and that is the honest place: an unreadable index only *matters* where a
     * reference would have been validated against it. A project with no `reference(...)` calls
     * has nothing mis-validated, so it stays quiet.
     */
    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun reportIndexProblems(problems: List<AssetCatalogProblem>, source: KtSourceElement?) {
        if (problems.isEmpty()) return
        if (!indexProblemsReported.compareAndSet(false, true)) return
        val detail = problems.joinToString("; ") { problem ->
            when (problem) {
                is AssetCatalogProblem.UnknownVersion ->
                    "${problem.origin} declares asset-index format version ${problem.found}, " +
                        "but this build reads version ${problem.expected}"

                is AssetCatalogProblem.Malformed ->
                    "${problem.origin} carries an unreadable asset index: ${problem.reason}"
            }
        }
        report(
            UdeaRules.ASSET_INDEX_FORMAT,
            source,
            "$detail. Until that is fixed, no reference(\"...\") in this module is validated.",
        )
    }

    /**
     * Reports [rule] unless the author suppressed it by id.
     *
     * `@Suppress("UDEA0004")` rather than `@Suppress("UDEA_REFERENCE_UNRESOLVED")`: the id is
     * the thing spec section 5 makes stable and the thing a developer actually sees, so it must
     * also be the thing they can suppress. FIR has already accumulated every `@Suppress` from
     * the file, the containing declaration and the property by the time a checker runs, so
     * reading the set covers all three levels without walking parents by hand. Compared
     * case-insensitively because Kotlin's own suppression is.
     */
    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun report(rule: UdeaRule, source: KtSourceElement?, detail: String) {
        if (context.suppressedDiagnostics.any { it.equals(rule.id, ignoreCase = true) }) return
        UdeaDiagnostics.report(rule, source, detail)
    }

    /**
     * The compile-time constant string [argument] denotes, or `null`.
     *
     * Three layers, cheapest first:
     *
     * 1. a plain literal — the case that matters and the case that is free;
     * 2. a string template whose every part is itself constant, so `"$FOLDER/orc"` is caught;
     * 3. the compiler's own constant evaluator, which is what resolves a `const val` and
     *    `"charater" + "/orc"`.
     *
     * Layer 3 is "where it is free" in issue #41's sense: it is the evaluator the compiler
     * already runs for annotation arguments, not a reimplementation of one. It is also the
     * layer most likely to move between Kotlin versions, so a failure inside it degrades to
     * silence rather than taking the compilation down — a checker that throws turns a typo
     * into "the Kotlin compiler crashed", which is strictly worse than the status quo it
     * replaces.
     */
    private fun constantString(argument: FirExpression, session: FirSession): String? =
        when (val unwrapped = argument.unwrapped()) {
            is FirLiteralExpression -> unwrapped.value as? String

            is FirStringConcatenationCall -> {
                val parts = unwrapped.argumentList.arguments.map { constantString(it, session) }
                if (parts.any { it == null }) null else parts.joinToString("")
            }

            else -> evaluated(unwrapped, session)
        }

    /**
     * Layer 3 of [constantString]: the compiler's evaluator, with every failure swallowed.
     *
     * ### The opt-in, stated rather than hidden
     *
     * `evaluateExpression` is marked `@PrivateConstantEvaluatorAPI` — "can be changed or
     * dropped anytime". The two suggested public alternatives, `evaluatePropertyInitializer`
     * and `evaluateAnnotationArguments`, answer different questions: neither can evaluate an
     * arbitrary *argument expression*, which is the only thing a call-site checker has.
     *
     * The opt-in is taken because issue #41 asks for `const val` resolution specifically, and
     * the cost of it breaking is bounded and already paid for by spec 7's version policy: this
     * plugin is pinned to one exact Kotlin version, its suite must pass before any Kotlin
     * upgrade merges, and if a release removes this API the plugin fails to *compile* — the
     * loudest failure available — rather than quietly validating less than it claims.
     */
    @OptIn(PrivateConstantEvaluatorAPI::class)
    private fun evaluated(expression: FirExpression, session: FirSession): String? = try {
        val result = FirExpressionEvaluator.evaluateExpression(expression, session)
        ((result as? FirEvaluatorResult.Evaluated)?.result as? FirLiteralExpression)?.value as? String
    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
        null
    }

    /** `name = "..."` is a wrapper around the expression that carries the source we want. */
    private fun FirExpression.unwrapped(): FirExpression =
        if (this is FirNamedArgumentExpression) expression else this

    private fun FirValueParameter.isString(): Boolean =
        (returnTypeRef.coneTypeOrNull as? ConeClassLikeType)?.lookupTag?.classId == StandardClassIds.String
}
