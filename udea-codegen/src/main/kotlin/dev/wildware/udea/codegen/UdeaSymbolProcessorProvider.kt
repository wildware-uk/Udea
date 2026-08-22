package dev.wildware.udea.codegen

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/**
 * KSP's entry point into `udea-codegen`, found through
 * `META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`.
 *
 * Public because KSP instantiates it reflectively by service name; nothing in the engine calls it.
 */
public class UdeaSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        UdeaSymbolProcessor(
            environment.codeGenerator,
            environment.logger,
            CodegenOptions.from(environment.options),
        )
}
