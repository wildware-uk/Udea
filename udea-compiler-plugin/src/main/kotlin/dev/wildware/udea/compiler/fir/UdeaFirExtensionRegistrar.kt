package dev.wildware.udea.compiler.fir

import dev.wildware.udea.compiler.UdeaCompilerPlugin
import dev.wildware.udea.compiler.UdeaPluginOptions
import dev.wildware.udea.compiler.assets.AssetCatalogSource
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

/**
 * Registers the FIR-side extensions the [options] ask for.
 *
 * `checkers` and `synthesis` are separate switches because they carry different risk:
 * checkers can only add diagnostics, whereas unresolved synthesised declarations paint a
 * whole project red in an IDE that has not loaded the plugin (spec 3.2). Synthesis stayed
 * off: issue #43's spike returned NO-GO (see `docs/compiler-plugin.md`), so this registrar
 * contributes no `FirDeclarationGenerationExtension` at all and `options.synthesis` selects
 * nothing today. The switch is kept because the CLI contract is already public and a future
 * GO must not have to re-mint it.
 */
internal class UdeaFirExtensionRegistrar(
    private val options: UdeaPluginOptions,
    private val catalog: AssetCatalogSource,
) : FirExtensionRegistrar() {

    override fun ExtensionRegistrarContext.configurePlugin() {
        if (options.checkers) {
            +{ session: FirSession -> UdeaFirAdditionalCheckers(session, catalog) }
        }
        val kdocIndex = options.kdocIndex
        if (kdocIndex != null) {
            // Loud rather than silently absolute: spec 5 forbids an absolute span, and a
            // guessed root produces a relative path that is wrong, which survives into a
            // shipped artefact instead of failing the build that produced it.
            val repoRoot = requireNotNull(options.repoRoot) {
                "-P plugin:${UdeaCompilerPlugin.PLUGIN_ID}:${UdeaCompilerPlugin.OPTION_KDOC_INDEX} " +
                    "was given without ${UdeaCompilerPlugin.OPTION_REPO_ROOT}. The harvester " +
                    "writes repo-relative spans (spec 5) and cannot infer the repository root."
            }
            +KDocHarvestExtension.factory(kdocIndex, repoRoot)
        }
    }
}

/**
 * The Udea FIR checker set.
 *
 * [UdeaProbeChecker] answers "is the plugin loaded at all"; the other two are the real rules
 * (issue #38). Every id they can raise is registered in `udea-diagnostics`' `UdeaRules`, which
 * `UdeaRuleParityTest` asserts by walking [UdeaDiagnostics.factories].
 */
internal class UdeaFirAdditionalCheckers(
    session: FirSession,
    catalog: AssetCatalogSource,
) : FirAdditionalCheckersExtension(session) {

    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val regularClassCheckers: Set<FirDeclarationChecker<FirRegularClass>> =
            setOf(UdeaProbeChecker, UdeaComponentFieldLimitChecker)

        override val propertyCheckers: Set<FirDeclarationChecker<FirProperty>> =
            setOf(UdeaReplicatedPropertyChecker)
    }

    /**
     * The `reference("...")` checker (issue #41).
     *
     * An instance rather than an `object`, unlike the declaration checkers, because it holds
     * two pieces of per-compilation state: the merged asset catalog and the "already said the
     * index is unreadable" latch. This extension is constructed once per FIR session, so an
     * instance field is the narrowest scope either of them can have.
     */
    override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
        override val functionCallCheckers: Set<FirExpressionChecker<FirFunctionCall>> =
            setOf(UdeaAssetReferenceChecker(catalog))
    }
}
