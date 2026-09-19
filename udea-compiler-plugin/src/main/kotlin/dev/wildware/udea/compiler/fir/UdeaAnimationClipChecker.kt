package dev.wildware.udea.compiler.fir

import dev.wildware.udea.diagnostics.DidYouMean
import dev.wildware.udea.diagnostics.UdeaRules
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.references.FirErrorNamedReference
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.scopes.FirContainingNamesAwareScope
import org.jetbrains.kotlin.fir.scopes.impl.declaredMemberScope
import org.jetbrains.kotlin.fir.scopes.processAllProperties
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/**
 * `Fox.Clips.Rnu` names the clip the author meant (issue #241).
 *
 * The asset pipeline generates one object of typed clips per model, read out of the model's own
 * file, so a clip the file does not have is not a member and the code does not compile. Kotlin's
 * error for that says the name is unresolved and stops. This checker adds the did-you-mean that
 * spec section 5 makes mandatory for every unresolved name, under a stable rule id:
 * `UDEA0016: Fox.Clips has no clip 'Rnu'. Did you mean 'Run'?`.
 *
 * ### How it knows an object holds clips
 *
 * By its members: a receiver whose class declares at least one property typed
 * `dev.wildware.udea.core.spatial.AnimationClip`. No marker annotation is generated for it,
 * because the property types already say it and a marker is one more thing that can disagree.
 * Every other unresolved name is left to Kotlin alone - naming a clip rule over `Settings.Volumee`
 * would send the author looking for a model that does not exist.
 *
 * The rule only ever *adds* to a compile error that happens anyway. With the plugin switched off
 * the typo still fails the build, without the suggestion - spec 7's degrade path.
 */
internal object UdeaAnimationClipChecker : FirExpressionChecker<FirPropertyAccessExpression>(MppCheckerKind.Common) {

    /** `udea-core`'s `AnimationClip`, which is what a generated clip property is typed as. */
    private val ANIMATION_CLIP: ClassId =
        ClassId.topLevel(FqName("dev.wildware.udea.core.spatial.AnimationClip"))

    /**
     * The fewest edits a suggestion may be away, whatever the name's length.
     *
     * `DidYouMean`'s default allows one edit below five characters, which is right against a
     * whole asset catalog and wrong here: two swapped letters are two edits, so `Rnu` would find
     * no `Run`. A model has a handful of clips rather than hundreds of ids, so two edits cannot
     * reach far, and a name nothing is near still gets the full list instead.
     */
    private const val MIN_BUDGET: Int = 2

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirPropertyAccessExpression) {
        val reference = expression.calleeReference as? FirErrorNamedReference ?: return
        val receiver = expression.explicitReceiver ?: return
        val holder = receiver.resolvedType.toRegularClassSymbol(context.session) ?: return
        val clips = clipNames(holder.declaredMemberScope(context.session, FirResolvePhase.STATUS))
        if (clips.isEmpty()) return
        if (context.suppressedDiagnostics.any { it.equals(UdeaRules.UNRESOLVED_ANIMATION_CLIP.id, ignoreCase = true) }) {
            return
        }

        val asked = reference.name.asString()
        val owner = holder.classId.relativeClassName.asString()
        val head = "$owner has no clip '$asked'"
        val suggestion = DidYouMean.suggest(asked, clips, maxOf(DidYouMean.defaultMaxDistance(asked), MIN_BUDGET))
        val detail = if (suggestion != null) {
            "$head. Did you mean '$suggestion'?"
        } else {
            "$head. Its clips are ${clips.sorted().joinToString()}, read from the model's file."
        }
        UdeaDiagnostics.report(UdeaRules.UNRESOLVED_ANIMATION_CLIP, reference.source ?: expression.source, detail)
    }

    /** The names of the properties in [scope] typed `AnimationClip`: the clips, and nothing else. */
    private fun clipNames(scope: FirContainingNamesAwareScope): List<String> {
        val names = mutableListOf<String>()
        scope.processAllProperties { symbol ->
            if (symbol is FirPropertySymbol && symbol.resolvedReturnType.classId == ANIMATION_CLIP) {
                names += symbol.name.asString()
            }
        }
        return names
    }
}
