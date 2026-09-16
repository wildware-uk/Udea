package dev.wildware.udea.compiler

import dev.wildware.udea.compiler.assets.AssetCatalogSource
import dev.wildware.udea.compiler.assets.ClasspathAssetCatalogScanner
import dev.wildware.udea.compiler.fir.UdeaDiagnostics
import dev.wildware.udea.compiler.fir.UdeaFirExtensionRegistrar
import org.jetbrains.kotlin.cli.jvm.config.jvmClasspathRoots
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

/**
 * The plugin's entry point. `CompilerPluginRegistrar`, not the pre-K2
 * `ComponentRegistrar`/`MockProject` shape, which K2 no longer calls.
 *
 * The one behaviour this class is required to have is the kill switch: with
 * `enabled=false` it registers nothing and returns, so a plugin broken by a Kotlin
 * upgrade degrades the build to checkers-off instead of blocking it (spec 7).
 *
 * There is deliberately no IR extension here and there never will be one for
 * replication: spec 3.2 settles dirty determination as capture-and-diff, because
 * `Transform.position` is mutated in place and no setter fires.
 */
@OptIn(ExperimentalCompilerApi::class)
public class UdeaCompilerPluginRegistrar : CompilerPluginRegistrar() {

    override val supportsK2: Boolean = true

    /**
     * Abstract on `CompilerPluginRegistrar` since Kotlin 2.4, and it is the same id the CLI
     * processor and the `gradle-plugins` descriptor already use, so it is read from the one
     * place that declares it rather than typed a fourth time.
     */
    override val pluginId: String = UdeaCompilerPlugin.PLUGIN_ID

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val options = configuration.toUdeaPluginOptions()
        if (!options.enabled) return

        // No renderer registration here any more, and nothing was lost with it. Up to Kotlin
        // 2.2 a diagnostic factory carried no renderer, so this class called
        // `RootDiagnosticRendererFactory.registerFactory(UdeaDiagnostics.Renderers)` into a
        // process-wide registry or the message rendered as a bare "null". From 2.4
        // `AbstractKtDiagnosticFactory` holds its own `rendererFactory`, taken from the
        // `KtDiagnosticsContainer` the `error1`/`warning0` delegate was declared on -- see
        // `UdeaDiagnostics` -- so a factory cannot exist without a renderer to print it.
        // `UdeaRuleParityTest.the rendered message carries the id, so a developer sees it`
        // compiles real source and reads the text the compiler printed, so it is that test and
        // not this comment that says the renderer still reaches a developer.

        // Built here, once per compilation, and shared by every FIR session the compilation
        // creates. The `lazy` inside it means the classpath is not walked at all unless a
        // checker actually meets an asset reference, so a module with no `reference("...")`
        // pays nothing (issue #40).
        val catalog = AssetCatalogSource(ClasspathAssetCatalogScanner(configuration.jvmClasspathRoots))

        FirExtensionRegistrarAdapter.registerExtension(UdeaFirExtensionRegistrar(options, catalog))
    }
}
