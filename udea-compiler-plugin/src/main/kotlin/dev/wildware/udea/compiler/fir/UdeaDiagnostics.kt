package dev.wildware.udea.compiler.fir

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.warning0

/**
 * The plugin's diagnostics.
 *
 * Spec 5 requires the K2 checkers to speak the same stable rule ids as the asset
 * validator. The rule id here is the property name the factory is delegated to, which is
 * what the compiler prints and what a suppression would name.
 */
public object UdeaDiagnostics {

    /**
     * Fires on a class named [dev.wildware.udea.compiler.UdeaCompilerPlugin.PROBE_CLASS_NAME]
     * and nothing else. It is the scaffold's proof of life: if this warning appears, the
     * plugin was loaded, its options were parsed and its FIR checkers ran.
     */
    public val UDEA_PLUGIN_LOADED: KtDiagnosticFactory0 by warning0<PsiElement>(
        SourceElementPositioningStrategies.DECLARATION_NAME,
    )

    /** Renderer map, registered by the plugin registrar when checkers are on. */
    public object Renderers : BaseDiagnosticRendererFactory() {
        override val MAP: KtDiagnosticFactoryToRendererMap =
            KtDiagnosticFactoryToRendererMap("Udea").apply {
                put(
                    UDEA_PLUGIN_LOADED,
                    "The Udea K2 compiler plugin is loaded and its FIR checkers are running.",
                )
            }
    }
}
