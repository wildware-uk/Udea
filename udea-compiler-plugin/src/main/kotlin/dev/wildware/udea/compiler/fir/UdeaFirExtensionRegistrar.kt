package dev.wildware.udea.compiler.fir

import dev.wildware.udea.compiler.UdeaPluginOptions
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

/**
 * Registers the FIR-side extensions the [options] ask for.
 *
 * `checkers` and `synthesis` are separate switches because they carry different risk:
 * checkers can only add diagnostics, whereas unresolved synthesised declarations paint a
 * whole project red in an IDE that has not loaded the plugin (spec 3.2). Synthesis is
 * therefore off by default and is not implemented here at all.
 */
internal class UdeaFirExtensionRegistrar(
    private val options: UdeaPluginOptions,
) : FirExtensionRegistrar() {

    override fun ExtensionRegistrarContext.configurePlugin() {
        if (options.checkers) {
            +::UdeaFirAdditionalCheckers
        }
    }
}

/** Contributes [UdeaProbeChecker] — and, in later issues, the rest of the checkers. */
internal class UdeaFirAdditionalCheckers(
    session: FirSession,
) : FirAdditionalCheckersExtension(session) {

    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val regularClassCheckers: Set<FirDeclarationChecker<FirRegularClass>> =
            setOf(UdeaProbeChecker)
    }
}
