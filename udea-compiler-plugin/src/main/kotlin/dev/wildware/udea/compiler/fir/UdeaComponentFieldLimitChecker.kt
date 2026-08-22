package dev.wildware.udea.compiler.fir

import dev.wildware.udea.diagnostics.UdeaRules
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirRegularClassChecker
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.hasAnnotation

/**
 * The one-`Long`-mask ceiling, reported on the component rather than on a field.
 *
 * Spec 7 keeps a future `LongArray` mask non-breaking at the `Replicator<T>` API, so this is
 * **not** a hard format limit and the message must never say it is: the fix a developer can
 * take today is to split the component, which spec 7 itself calls "better ECS design anyway".
 *
 * The count is of `@Net` **and** `@Sim` properties together, because both occupy a bit of the
 * same `ALL_MASK` (spec 3.1). Counting only `@Net` would let a component with 40 of each
 * through and fail at generation time instead.
 *
 * ### It counts declarations, and `udea-codegen` counts fields
 *
 * A composite property lowers to several fields (`position` becomes `position.x` and
 * `position.y`), so the number counted here is a **lower bound** on the number of mask bits
 * the component will actually need. That asymmetry is deliberate and only ever errs towards
 * silence: 65 declared properties is at least 65 fields, so this checker cannot fire on a
 * component the generator would accept, while a component of 40 vectors trips KSP's count
 * and not this one. See [UdeaFieldTypes] for why the plugin refuses to reproduce lowering.
 */
@OptIn(DirectDeclarationsAccess::class)
internal object UdeaComponentFieldLimitChecker : FirRegularClassChecker(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirRegularClass) {
        val session = context.session
        if (!declaration.hasAnnotation(UdeaAnnotations.REPLICATED, session)) return

        val fields = declaration.declarations
            .filterIsInstance<FirProperty>()
            .count {
                it.hasAnnotation(UdeaAnnotations.NET, session) ||
                    it.hasAnnotation(UdeaAnnotations.SIM, session)
            }
        if (fields <= UdeaRules.MAX_COMPONENT_FIELDS) return

        val qualifiedName = declaration.symbol.classId.asFqNameString()
        UdeaDiagnostics.report(
            UdeaRules.COMPONENT_FIELD_LIMIT,
            declaration.source,
            "$qualifiedName declares $fields @Net/@Sim fields, but a field mask addresses at " +
                "most ${UdeaRules.MAX_COMPONENT_FIELDS}. SPLIT the component into two or more " +
                "components of at most ${UdeaRules.MAX_COMPONENT_FIELDS} fields each; there is " +
                "no way to widen the mask for one component.",
        )
    }
}
