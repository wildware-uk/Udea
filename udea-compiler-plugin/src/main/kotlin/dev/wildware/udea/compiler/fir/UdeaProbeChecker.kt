package dev.wildware.udea.compiler.fir

import dev.wildware.udea.compiler.UdeaCompilerPlugin
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirRegularClass

/**
 * The scaffold's one checker.
 *
 * It answers a single question — "did this compilation actually load the Udea plugin?" —
 * and it answers it at the exact symbol, which is the whole reason a FIR checker exists
 * rather than a Gradle task (spec 3.2). It fires only on the reserved probe class name,
 * so applying the plugin to real modules produces no output.
 *
 * The real checkers (`@Net` on a `val`, >64 fields, `@Q` on non-float,
 * `reference("typo")`) are separate issues and land beside this one.
 */
internal object UdeaProbeChecker : FirDeclarationChecker<FirRegularClass>(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirRegularClass) {
        if (declaration.name.asString() != UdeaCompilerPlugin.PROBE_CLASS_NAME) return
        reporter.reportOn(declaration.source, UdeaDiagnostics.UDEA_PLUGIN_LOADED)
    }
}
