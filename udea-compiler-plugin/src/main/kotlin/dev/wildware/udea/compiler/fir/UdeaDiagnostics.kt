package dev.wildware.udea.compiler.fir

import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.UdeaRule
import dev.wildware.udea.diagnostics.UdeaRules
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.diagnostics.warning0
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext

/**
 * The plugin's diagnostics, one factory per [UdeaRule] it can raise.
 *
 * ### Why the id is in the message
 *
 * Spec 5 requires the K2 checkers, `udea-codegen`'s KSP errors and the asset validator to
 * speak the same stable rule ids. KSP has no diagnostic-factory concept at all - it prints a
 * string - so `ComponentModelBuilder` prefixes its errors with `"${rule.id}: "`. This object
 * does the same, through [report], so the id a developer sees is identical whichever tool
 * caught the defect. [factories] is the single mapping from rule to factory, and
 * `UdeaRuleParityTest` asserts it is total in both directions: a checker cannot raise a rule
 * that `udea-diagnostics` has not registered, and a registered rule this plugin claims to
 * raise cannot lose its factory.
 *
 * ### Why every factory takes one `String`
 *
 * The compiler renders a diagnostic from a format string and typed parameters. Splitting a
 * message into typed parameters here would put half of it in this file and half in the
 * checker, and would make "assert the message verbatim" - which issue #38 requires for the
 * 64-field ceiling - a test of the renderer rather than of the checker. One `String`
 * parameter keeps every message in the checker that decides to raise it.
 */
internal object UdeaDiagnostics {

    /**
     * Fires on a class named [dev.wildware.udea.compiler.UdeaCompilerPlugin.PROBE_CLASS_NAME]
     * and nothing else. It is the plugin's proof of life: if this warning appears, the plugin
     * was loaded, its options were parsed and its FIR checkers ran. It carries no rule id
     * because it reports no defect.
     */
    val UDEA_PLUGIN_LOADED: KtDiagnosticFactory0 by warning0<PsiElement>(
        SourceElementPositioningStrategies.DECLARATION_NAME,
    )

    /** [UdeaRules.NET_ON_VAL]. */
    val NET_ON_VAL: KtDiagnosticFactory1<String> by error1<PsiElement, String>(
        SourceElementPositioningStrategies.DECLARATION_NAME,
    )

    /** [UdeaRules.SIM_ON_VAL]. */
    val SIM_ON_VAL: KtDiagnosticFactory1<String> by error1<PsiElement, String>(
        SourceElementPositioningStrategies.DECLARATION_NAME,
    )

    /** [UdeaRules.COMPONENT_FIELD_LIMIT]. */
    val COMPONENT_FIELD_LIMIT: KtDiagnosticFactory1<String> by error1<PsiElement, String>(
        SourceElementPositioningStrategies.DECLARATION_NAME,
    )

    /** [UdeaRules.QUANTIZED_NON_FLOAT]. */
    val QUANTIZED_NON_FLOAT: KtDiagnosticFactory1<String> by error1<PsiElement, String>(
        SourceElementPositioningStrategies.DECLARATION_NAME,
    )

    /**
     * [UdeaRules.UNRESOLVED_REFERENCE].
     *
     * `DEFAULT` positioning, unlike the declaration rules above: the element handed to
     * [report] is the string literal itself, so the span the compiler prints is the typo's own
     * span. Issue #41 asserts that `line:column` explicitly, because a diagnostic on the
     * enclosing call is the same test without it and does not satisfy the demo criterion.
     */
    val REFERENCE_UNRESOLVED: KtDiagnosticFactory1<String> by error1<PsiElement, String>(
        SourceElementPositioningStrategies.DEFAULT,
    )

    /** [UdeaRules.REFERENCE_KIND_MISMATCH]. At the literal, for the same reason. */
    val REFERENCE_KIND_MISMATCH: KtDiagnosticFactory1<String> by error1<PsiElement, String>(
        SourceElementPositioningStrategies.DEFAULT,
    )

    /** [UdeaRules.ASSET_INDEX_FORMAT]. */
    val ASSET_INDEX_FORMAT: KtDiagnosticFactory1<String> by error1<PsiElement, String>(
        SourceElementPositioningStrategies.DEFAULT,
    )

    /**
     * Every rule this plugin can raise, and the factory it raises it through.
     *
     * Declared explicitly rather than derived by reflection: reflection over delegated
     * properties on a per-compilation path is exactly what §1 of the engineering standards
     * forbids, and the parity test reads this map instead.
     */
    val factories: Map<UdeaRule, KtDiagnosticFactory1<String>> = linkedMapOf(
        UdeaRules.NET_ON_VAL to NET_ON_VAL,
        UdeaRules.SIM_ON_VAL to SIM_ON_VAL,
        UdeaRules.COMPONENT_FIELD_LIMIT to COMPONENT_FIELD_LIMIT,
        UdeaRules.QUANTIZED_NON_FLOAT to QUANTIZED_NON_FLOAT,
        UdeaRules.UNRESOLVED_REFERENCE to REFERENCE_UNRESOLVED,
        UdeaRules.REFERENCE_KIND_MISMATCH to REFERENCE_KIND_MISMATCH,
        UdeaRules.ASSET_INDEX_FORMAT to ASSET_INDEX_FORMAT,
    )

    /**
     * Reports [rule] at [source] with `"<id>: <detail>"`, the same shape `udea-codegen` prints.
     *
     * @param detail the message body, without the id. The checker owns this text; it is not
     *   part of any stability contract (see [UdeaRule]).
     */
    context(context: CheckerContext, reporter: DiagnosticReporter)
    fun report(rule: UdeaRule, source: KtSourceElement?, detail: String) {
        val factory = requireNotNull(factories[rule]) {
            "no diagnostic factory registered for rule ${rule.id}; add one to UdeaDiagnostics"
        }
        check(rule.defaultSeverity == Severity.Error) {
            "rule ${rule.id} has default severity ${rule.defaultSeverity} but its factory is an " +
                "error factory; the two must agree or a suppression would not do what it says"
        }
        reporter.reportOn(source, factory, "${rule.id}: $detail")
    }

    /**
     * Renderer map, registered by the plugin registrar when checkers are on.
     *
     * Every rule renders as `{0}` - the whole message is the parameter - so the text a
     * developer reads is byte-for-byte the text the checker built.
     */
    object Renderers : BaseDiagnosticRendererFactory() {
        override val MAP: KtDiagnosticFactoryToRendererMap =
            KtDiagnosticFactoryToRendererMap("Udea").apply {
                put(
                    UDEA_PLUGIN_LOADED,
                    "The Udea K2 compiler plugin is loaded and its FIR checkers are running.",
                )
                for (factory in UdeaDiagnostics.factories.values) {
                    put(factory, "{0}", CommonRenderers.STRING)
                }
            }
    }
}
