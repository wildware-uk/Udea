package dev.wildware.udea.compiler.fir

import dev.wildware.udea.diagnostics.UdeaRules
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.types.coneType

/**
 * The `@Net`/`@Sim`/`@Q` property rules, reported at the property name.
 *
 * Reporting at the symbol is the whole reason this is a FIR checker and not a Gradle task
 * (spec 3.2). `udea-codegen` raises the same rule ids from the same `UdeaRules` constants,
 * but only once KSP runs and only at a task boundary; Phase 0's demo criterion is
 * `@Net val health` showing red **at the property name** in the editor, which is what
 * `SourceElementPositioningStrategies.DECLARATION_NAME` on each factory buys.
 *
 * It fires on any annotated property, not only on one inside a `@Replicated` class. `@Net` on
 * a property of a class KSP will never look at is a worse defect than `@Net` on a `val`, not
 * a lesser one - it is the silent-failure case section 1 of the engineering standards names -
 * so narrowing the checker to `@Replicated` classes would hide it.
 */
internal object UdeaReplicatedPropertyChecker : FirPropertyChecker(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirProperty) {
        val session = context.session
        val net = declaration.hasAnnotation(UdeaAnnotations.NET, session)
        val sim = declaration.hasAnnotation(UdeaAnnotations.SIM, session)
        val quantized = declaration.hasAnnotation(UdeaAnnotations.Q, session)
        if (!net && !sim && !quantized) return

        val type = declaration.returnTypeRef.coneType
        // An unresolved type already carries the compiler's own error. Piling a Udea rule id
        // on top would name the wrong defect and send the author looking in the wrong place.
        if (UdeaFieldTypes.isUnresolved(type)) return

        val name = declaration.symbol.callableId.asSingleFqName().asString()
        val source = declaration.returnTypeRef.source

        // Only a *directly stored* val is the defect: a composite `@Net val position: Vector2`
        // is legal, because `Replicator.apply` restores it by writing `position.x`/`position.y`
        // in place. Deciding that here would mean reproducing udea-codegen's lowering table;
        // see UdeaFieldTypes for why this plugin refuses to.
        if (!declaration.isVar && (net || sim) && UdeaFieldTypes.isDirectlyStored(type, session)) {
            // Replication is capture-and-diff (spec 3.2): capture reads the field every tick
            // and `Replicator.apply` assigns it back. A val can do neither, so the field would
            // occupy a mask bit that can never be set and never be restored. The two ids are
            // the same defect on the two masks, which is why udea-diagnostics registers both.
            val rule = if (net) UdeaRules.NET_ON_VAL else UdeaRules.SIM_ON_VAL
            val annotation = if (net) "@Net" else "@Sim"
            val consequence = if (net) "it can never replicate" else "it can never be snapshotted"
            UdeaDiagnostics.report(
                rule,
                source,
                "$annotation annotates the val $name. A val can never change, so $consequence, " +
                    "and Replicator.apply could not restore it. Make it a var or drop the annotation.",
            )
        }

        // `@Q` is decidable from the type alone in every case: udea-codegen rejects a
        // non-Float before it lowers anything, so there is no widening of the lowering table
        // that could turn this into a false positive.
        if (quantized && !UdeaFieldTypes.isFloat(type)) {
            UdeaDiagnostics.report(
                UdeaRules.QUANTIZED_NON_FLOAT,
                source,
                "@Q annotates $name, which is ${UdeaFieldTypes.describe(type)}, not Float. " +
                    "Quantization is only defined for floats.",
            )
        }
    }
}
