package dev.wildware.udea.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import dev.wildware.udea.codegen.replicator.ComponentModelBuilder
import dev.wildware.udea.codegen.replicator.ReplicatorEmitter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * The one Udea KSP2 processor.
 *
 * Today it emits a `Replicator<T>` per `@Replicated` component. The ServiceLoader registries and
 * the `@AgentTool` manifests join it here rather than as separate processors, so that id
 * assignment stays in one place (spec 5, "Id assignment").
 *
 * ## Two things it deliberately does not do
 *
 * **It never logs at `warn` or `info`.** A successful run is silent. The generator this replaces
 * reported ordinary progress at `logger.warn`, so every build printed a wall of text and a real
 * warning was invisible in it.
 *
 * **It never catches an exception around a symbol.** A component it cannot handle is a
 * `logger.error` at that symbol and a failed build, never a skipped file.
 */
internal class UdeaSymbolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private val models = ComponentModelBuilder(logger)

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val components = resolver.getSymbolsWithAnnotation(AnnotationNames.REPLICATED)
            .filterIsInstance<KSClassDeclaration>()
            // Sorted by FQN so the set of emitted files, and their contents, depend on the
            // sources alone and not on the order KSP happened to hand them over. Two clean
            // builds of the same sources must produce byte-identical output.
            .sortedBy { it.qualifiedName?.asString() ?: it.simpleName.asString() }
            .toList()

        for (declaration in components) {
            val model = models.build(declaration) ?: continue
            val containingFile = declaration.containingFile
            if (containingFile == null) {
                logger.error(
                    "@Replicated ${model.qualifiedName} has no source file; only components " +
                        "compiled from source in this module can have a Replicator generated.",
                    declaration,
                )
                continue
            }
            val file = ReplicatorEmitter.emit(model)
            // aggregating = false: this file is a pure function of one source file, so an
            // unrelated edit elsewhere in the module must not invalidate it.
            codeGenerator.createNewFile(
                dependencies = Dependencies(aggregating = false, containingFile),
                packageName = file.packageName,
                fileName = file.name,
            ).use { stream ->
                OutputStreamWriter(stream, StandardCharsets.UTF_8).use(file::writeTo)
            }
        }
        return emptyList()
    }
}
