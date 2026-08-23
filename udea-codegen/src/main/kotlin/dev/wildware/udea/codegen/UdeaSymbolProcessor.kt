package dev.wildware.udea.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import dev.wildware.udea.codegen.protocol.LockedComponent
import dev.wildware.udea.codegen.protocol.LockedField
import dev.wildware.udea.codegen.protocol.NetProtocolEmitter
import dev.wildware.udea.codegen.protocol.ProtocolLock
import dev.wildware.udea.codegen.registry.ServiceIndexEmitter
import dev.wildware.udea.codegen.replicator.ComponentModelBuilder
import dev.wildware.udea.codegen.replicator.ReplicatedComponent
import dev.wildware.udea.codegen.replicator.ReplicatorEmitter
import dev.wildware.udea.codegen.replicator.TypeIds
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * The one Udea KSP2 processor.
 *
 * It emits three kinds of output, and the split between them is what makes incremental
 * processing possible at all:
 *
 * | output | one per | dependency |
 * |---|---|---|
 * | `…Replicator` | `@Replicated` component | **isolating** — the component's own file |
 * | `…NetProtocol`, `…NetModule`, `<Module>-net-protocol.lock` | module | **aggregating** — every source |
 *
 * An isolating output is invalidated only by an edit to the one file it came from, so editing
 * one component reprocesses one component. The generator this replaces marked *every* file
 * aggregating and named its index after the wall clock, so every build reprocessed everything
 * and produced a file that had never existed before — which is why `ksp.incremental` was
 * switched off repository-wide. `IncrementalProcessingTest` audits both halves of that here,
 * because nothing else in the repository would notice either being got wrong again.
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
    private val options: CodegenOptions,
) : SymbolProcessor {

    private val models = ComponentModelBuilder(logger)

    /**
     * KSP calls `process` once per round. Nothing here defers a symbol, so the module-level
     * files belong to the first round that saw components; the flag stops a later empty round
     * from rewriting them as an empty index.
     */
    private var emittedModuleFiles = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val components = resolver.getSymbolsWithAnnotation(AnnotationNames.REPLICATED)
            .filterIsInstance<KSClassDeclaration>()
            // Sorted by FQN so the set of emitted files, and their contents, depend on the
            // sources alone and not on the order KSP happened to hand them over. Two clean
            // builds of the same sources must produce byte-identical output.
            .sortedBy { it.qualifiedName?.asString() ?: it.simpleName.asString() }
            .toList()
        if (components.isEmpty()) return emptyList()

        val local = components.mapNotNull { it.qualifiedName?.asString() }

        // **The id space is the whole project, not this module.** A KSP run sees one Gradle
        // module, so assigning from `local` alone hands out 0, 1, 2, … per module and two
        // modules mint the same ComponentTypeId — after which two peers decode each other's
        // packets as the wrong component type, silently, with the connect-time protoHash
        // reporting agreement. Spec 5 puts every id in one sorted-FQN assignment, and the
        // build passes that list in as `udea.projectComponents` rather than the processor
        // scanning for it.
        //
        // Absent, the id space is the module's own components — and that is legal in exactly
        // one configuration: a module that emits no protocol identity. `udea.moduleName` is
        // what turns the lock, the protoHash and the `ServiceLoader` index on, so a module
        // that sets it is a participant in the project's wire contract and must be numbered
        // from the project's id space. Silently falling back there is the defect itself: the
        // module's lock would be internally consistent, its protoHash would agree with a peer
        // built the same way, and the ids would still collide with another module's.
        //
        // Ids come from **every** name in the space, including components that fail to build.
        // Otherwise one broken component silently renumbers all its successors on the wire,
        // and the id a developer sees while fixing the build is not the id they ship.
        if (options.moduleName != null && options.projectComponents == null) {
            logger.error(
                "this module emits a wire protocol (${CodegenOptions.MODULE_NAME} is " +
                    "'${options.moduleName}') but the build did not set " +
                    "${CodegenOptions.PROJECT_COMPONENTS}, so its component type ids would be " +
                    "numbered from its own ${local.size} component(s) starting at 0. Another " +
                    "module numbered the same way mints the same ids, and two peers then decode " +
                    "each other's packets as the wrong component type while protoHash reports " +
                    "agreement. Add this module's components to the project's " +
                    "'net-components.lock' and let the build pass the list in.",
            )
            return emptyList()
        }
        val idSpace = options.projectComponents ?: local
        val ids = TypeIds.assignIds(idSpace)

        // A component this module compiles but the project list does not name would otherwise
        // fall out of `ids` and crash generation with a NoSuchElementException naming nothing.
        // It means the list is stale — a component was added and the build was not re-run, or
        // the module is not on the path the list was computed from — and the consequence is an
        // id space two modules disagree about, so it is a located error and not a fallback.
        val missing = local.filterNot(ids::containsKey)
        if (missing.isNotEmpty()) {
            for (declaration in components) {
                val name = declaration.qualifiedName?.asString() ?: continue
                if (name !in missing) continue
                logger.error(
                    "$name is compiled by this module but is not in ${CodegenOptions.PROJECT_COMPONENTS}, " +
                        "which lists the ${idSpace.size} @Replicated components the build assigns " +
                        "component type ids from. The list is stale: regenerate it, or the ids " +
                        "this module emits will not be the ids the rest of the project agreed on.",
                    declaration,
                )
            }
            return emptyList()
        }

        val emitted = ArrayList<Pair<ReplicatedComponent, Int>>(components.size)
        val sourceFiles = ArrayList<KSFile>(components.size)
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
            val typeId = ids.getValue(model.qualifiedName)
            writeIsolating(ReplicatorEmitter.emit(model, typeId), containingFile)
            emitted += model to typeId
            sourceFiles += containingFile
        }

        val moduleName = options.moduleName
        if (moduleName != null && !emittedModuleFiles) {
            if (!CodegenOptions.MODULE_NAME_FORMAT.matches(moduleName)) {
                logger.error(
                    "${CodegenOptions.MODULE_NAME} is '$moduleName', which cannot be part of a " +
                        "generated object name. It must match " +
                        "${CodegenOptions.MODULE_NAME_FORMAT.pattern} — UpperCamelCase, letters " +
                        "and digits only.",
                )
            } else {
                writeModuleFiles(moduleName, emitted, sourceFiles)
                emittedModuleFiles = true
            }
        }
        return emptyList()
    }

    /**
     * A per-component file: a pure function of one source file, so an unrelated edit elsewhere
     * in the module must not invalidate it.
     */
    private fun writeIsolating(file: FileSpec, containingFile: KSFile) {
        codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = false, containingFile),
            packageName = file.packageName,
            fileName = file.name,
        ).use { stream ->
            OutputStreamWriter(stream, StandardCharsets.UTF_8).use(file::writeTo)
        }
    }

    /**
     * The module-level outputs: the protocol constant, the lock file and — when the module has
     * `udea-net` on its classpath — the `ServiceLoader` index.
     *
     * These are the **only** aggregating outputs. They genuinely depend on every component in
     * the module (adding one shifts every later id), so marking them isolating would be a
     * correctness bug rather than an optimisation; keeping them to one file per module is what
     * stops that dependency from costing a full rebuild per edited component.
     */
    private fun writeModuleFiles(
        moduleName: String,
        emitted: List<Pair<ReplicatedComponent, Int>>,
        sourceFiles: List<KSFile>,
    ) {
        val dependencies = Dependencies(aggregating = true, *sourceFiles.toTypedArray())
        val lock = ProtocolLock.build(emitted.map { (component, id) -> locked(component, id) })

        writeAggregating(NetProtocolEmitter.emit(moduleName, lock), dependencies)
        codeGenerator.createNewFileByPath(
            dependencies = dependencies,
            path = ProtocolLock.resourcePath(moduleName),
            extensionName = "",
        ).use { stream ->
            stream.write(ProtocolLock.render(lock).toByteArray(StandardCharsets.UTF_8))
        }

        val service = options.netModuleService ?: return
        val serviceName = ClassName.bestGuess(service)
        val index = GeneratedNames.netModule(moduleName)
        writeAggregating(
            ServiceIndexEmitter.emit(
                service = serviceName,
                index = index,
                moduleName = moduleName,
                property = ServiceIndexEmitter.REPLICATORS,
                // Ascending type id, which is ascending name: the same order NetRegistry
                // indexes the array it builds by.
                members = emitted.sortedBy { it.second }.map { (component, _) ->
                    ClassName(component.className.packageName, component.replicatorName)
                },
            ),
            dependencies,
        )
        codeGenerator.createNewFileByPath(
            dependencies = dependencies,
            path = ServiceIndexEmitter.resourcePath(serviceName),
            extensionName = "",
        ).use { stream ->
            stream.write(ServiceIndexEmitter.resourceContent(index).toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun writeAggregating(file: FileSpec, dependencies: Dependencies) {
        codeGenerator.createNewFile(
            dependencies = dependencies,
            packageName = file.packageName,
            fileName = file.name,
        ).use { stream ->
            OutputStreamWriter(stream, StandardCharsets.UTF_8).use(file::writeTo)
        }
    }

    private fun locked(component: ReplicatedComponent, typeId: Int): LockedComponent =
        LockedComponent(
            id = typeId,
            qualifiedName = component.qualifiedName,
            fields = component.fields.map { field ->
                LockedField(index = field.index, name = field.name, wire = field.wireDescription)
            },
        )
}
