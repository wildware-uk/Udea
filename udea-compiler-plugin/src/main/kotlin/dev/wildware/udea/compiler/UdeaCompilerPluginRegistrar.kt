package dev.wildware.udea.compiler

import dev.wildware.udea.compiler.fir.UdeaDiagnostics
import dev.wildware.udea.compiler.fir.UdeaFirExtensionRegistrar
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.diagnostics.rendering.RootDiagnosticRendererFactory
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

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val options = configuration.toUdeaPluginOptions()
        if (!options.enabled) return

        if (options.checkers) {
            // Registering the renderer map is what makes the diagnostic printable at all;
            // an unregistered factory renders as a bare "null" message.
            RootDiagnosticRendererFactory.registerFactory(UdeaDiagnostics.Renderers)
        }

        FirExtensionRegistrarAdapter.registerExtension(UdeaFirExtensionRegistrar(options))
    }
}
