package dev.wildware.udea.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSFile
import dev.wildware.udea.codegen.agent.AgentPass
import dev.wildware.udea.codegen.agent.AgentStateModel
import dev.wildware.udea.codegen.agent.ToolManifest
import dev.wildware.udea.codegen.agent.ToolModel
import dev.wildware.udea.codegen.level.LevelComponentScanner
import dev.wildware.udea.codegen.protocol.LockedComponent
import dev.wildware.udea.codegen.protocol.LockedField
import dev.wildware.udea.codegen.protocol.NetProtocolEmitter
import dev.wildware.udea.codegen.protocol.ProtocolLock
import dev.wildware.udea.codegen.registry.RegistryEmitter
import dev.wildware.udea.codegen.replicator.ComponentModelBuilder
import dev.wildware.udea.codegen.replicator.ReplicatedComponent
import dev.wildware.udea.codegen.replicator.ReplicatorEmitter
import dev.wildware.udea.codegen.replicator.TypeIds
import dev.wildware.udea.codegen.rpc.RpcEmitter
import dev.wildware.udea.codegen.rpc.RpcFunction
import dev.wildware.udea.codegen.rpc.RpcModelBuilder
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
 * | `…NetProtocol`, `…ModuleRegistry`, `…UdeaRegistry`, `<Module>-net-protocol.lock` | module | **aggregating** — every source |
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
    options: CodegenOptions,
) : SymbolProcessor {

    /**
     * The options this run acts on. A platform run - [CodegenOptions.sourceSet] set - writes no
     * module-level file, because the common run over the same module already wrote them, so it
     * is handed no module name to write them under.
     */
    private val options: CodegenOptions =
        if (options.sourceSet == null) options else options.copy(moduleName = null)

    private val scope = SourceSetScope(options.sourceSet)

    private val models = ComponentModelBuilder(logger)
    private val agent = AgentPass(logger, scope)
    private val rpcs = RpcModelBuilder(logger)
    private val levelComponents = LevelComponentScanner(logger)

    /**
     * KSP calls `process` once per round. Nothing here defers a symbol, so the module-level
     * files belong to the first round that saw components; the flag stops a later empty round
     * from rewriting them as an empty index.
     */
    private var emittedModuleFiles = false

    /** So a malformed `udea.sourceSet` is reported once and not once per KSP round. */
    private var reportedSourceSet = false

    /** So a malformed `udea.moduleName` is reported once and not once per KSP round. */
    private var reportedModuleName = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (!checkSourceSet()) return emptyList()
        val components = resolver.getSymbolsWithAnnotation(AnnotationNames.REPLICATED)
            .filter(scope::admits)
            .filterIsInstance<KSClassDeclaration>()
            // Sorted by FQN so the set of emitted files, and their contents, depend on the
            // sources alone and not on the order KSP happened to hand them over. Two clean
            // builds of the same sources must produce byte-identical output.
            .sortedBy { it.qualifiedName?.asString() ?: it.simpleName.asString() }
            .toList()
        // The agent surface and the replication surface share a round and nothing else: a
        // module may declare tools and publish no components, or the reverse, and neither is
        // an error. Bailing out on `components.isEmpty()` alone would have silently generated
        // no tools for exactly the module the agent epic cares about most.
        val agentIsEmpty = agent.isEmpty(resolver)
        // The RPC surface shares a round with the other two and nothing else: a module may
        // declare `@Rpc` functions and no components, or the reverse. Bailing out before this
        // on `components.isEmpty()` alone would have silently generated no guards for a module
        // whose only networking is RPCs, which is the failure mode the agent pass already
        // taught this method once.
        val rpcFunctions = rpcFunctions(resolver)
        // The level pass shares the round and nothing else, for the reason the RPC pass does: a
        // module may declare saveable components and nothing else at all - `udea-core` and
        // `udea-gas` do exactly that - and an early return keyed on the other three would
        // generate no list for them, so their levels would refuse to save.
        val saveable = levelComponents.find(resolver).filter { scope.admitsFile(it.containingFile) }
        // A named module with nothing in it still gets its registry, once: a launcher whose
        // classpath holds the module names that registry, and a reference to a class nobody
        // wrote is a compile error that is only meant to happen when the module is absent.
        val nothingToDo = components.isEmpty() && agentIsEmpty && rpcFunctions.isEmpty() && saveable.isEmpty()
        if (nothingToDo && (options.moduleName == null || emittedModuleFiles)) return emptyList()
        // Validated once, here, and not at each writer. `udea.moduleName` gates the lock, the
        // protoHash, both registries and the tool manifest, and a module whose name
        // is malformed can produce none of them - so a check inside one writer and a bare
        // `return` inside another had the two paths disagreeing about whether the same
        // misconfiguration was a failure at all. Tools-only modules took the silent path:
        // dispatchers compiled, nothing indexed them, and no diagnostic said so.
        if (!checkModuleName()) return emptyList()
        // Before the component id space is resolved, because an `RpcDescriptor` depends on
        // nothing in it: an RPC index is assigned at runtime from the sorted name list that
        // `RpcRegistry` builds, so a module can emit its guards even while its component list
        // is being argued about.
        writeRpcFiles(rpcFunctions)
        if (!checkLevelModuleName(saveable)) return emptyList()
        if (components.isEmpty()) {
            if (emittedModuleFiles) return emptyList()
            val agentResult = agent.run(resolver)
            writeAgentFiles(agentResult)
            val moduleName = options.moduleName ?: return emptyList()
            val sources = agentSourceFiles(agentResult) + saveable.map(LevelComponentScanner.Found::containingFile)
            writeManifest(moduleName, agentResult, aggregating(sources))
            writeRegistries(resolver, moduleName, agentFacets(agentResult) + levelFacets(saveable), sources)
            emittedModuleFiles = true
            return emptyList()
        }

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
        // what turns the lock, the protoHash and the module registry on, so a module
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

        val agentResult = if (agentIsEmpty) AgentPass.Result(emptyList(), emptyList()) else agent.run(resolver)
        writeAgentFiles(agentResult)
        sourceFiles += agentSourceFiles(agentResult)

        val moduleName = options.moduleName
        if (moduleName != null && !emittedModuleFiles) {
            sourceFiles += saveable.map(LevelComponentScanner.Found::containingFile)
            val dependencies = aggregating(sourceFiles)
            writeProtocolFiles(moduleName, emitted, dependencies)
            writeManifest(moduleName, agentResult, dependencies)
            writeRegistries(
                resolver,
                moduleName,
                netFacets(emitted) + agentFacets(agentResult) + levelFacets(saveable),
                sourceFiles,
            )
            emittedModuleFiles = true
        }
        return emptyList()
    }

    /**
     * False, having reported it, when this round has saveable components and no module name.
     *
     * Their list lives on the module registry, which is named after the module, so there is
     * nowhere to put it. That is an error rather than a skip: the cost of skipping is a level
     * that refuses to save at run time, naming a component whose module simply never said what
     * it was called.
     */
    private fun checkLevelModuleName(found: List<LevelComponentScanner.Found>): Boolean {
        if (found.isEmpty() || options.moduleName != null) return true
        logger.error(
            "this module declares ${found.size} @Serializable Fleks component(s) but the build " +
                "did not set ${CodegenOptions.MODULE_NAME}, so no level component list can be " +
                "generated for them and a level holding one would refuse to save. Set it in " +
                "this module's ksp { } block.",
        )
        return false
    }

    /**
     * Every `@Rpc` function in this round, sorted by fully-qualified name.
     *
     * Sorted for the reason the component list is: the set of emitted files and their contents
     * must depend on the sources alone and not on the order KSP happened to hand them over.
     */
    private fun rpcFunctions(resolver: Resolver): List<KSFunctionDeclaration> =
        resolver.getSymbolsWithAnnotation(AnnotationNames.RPC)
            .filter(scope::admits)
            .filterIsInstance<KSFunctionDeclaration>()
            .sortedBy { it.qualifiedName?.asString() ?: it.simpleName.asString() }
            .toList()

    /**
     * One `RpcDescriptor` object per `@Rpc` function.
     *
     * **Isolating**, like a `Replicator`: the descriptor is a pure function of the one file
     * that declared the function, so adding an RPC reprocesses that file and not the module.
     *
     * A function the builder refused emits nothing, which is the whole point - a half-emitted
     * descriptor is an unguarded one, and an unguarded RPC is the defect this feature exists
     * to remove rather than a degraded version of it.
     */
    private fun writeRpcFiles(functions: List<KSFunctionDeclaration>) {
        for (declaration in functions) {
            val model: RpcFunction = rpcs.build(declaration) ?: continue
            val containingFile = declaration.containingFile
            if (containingFile == null) {
                logger.error(
                    "@Rpc ${model.qualifiedName} has no source file; only a function compiled " +
                        "from source in this module can have an RpcDescriptor generated.",
                    declaration,
                )
                continue
            }
            writeIsolating(RpcEmitter.emit(model), containingFile)
        }
    }

    /**
     * False, having reported it, when `udea.sourceSet` is not a source set name.
     *
     * A path, or anything else that is not a name, would match no file, and the run would
     * silently generate nothing: the platform compilation would then fail naming a missing tool
     * object, a long way from the option that caused it.
     */
    private fun checkSourceSet(): Boolean {
        val sourceSet = options.sourceSet ?: return true
        if (CodegenOptions.SOURCE_SET_FORMAT.matches(sourceSet)) return true
        if (!reportedSourceSet) {
            reportedSourceSet = true
            logger.error(
                "${CodegenOptions.SOURCE_SET} is '$sourceSet', which is not a source set name. " +
                    "It must match ${CodegenOptions.SOURCE_SET_FORMAT.pattern}, for example " +
                    "'jvmMain': the run processes the declarations under src/<name>/ and no others.",
            )
        }
        return false
    }

    /**
     * False, having reported it, when `udea.moduleName` cannot be half of a generated object
     * name.
     *
     * The name is checked rather than sanitised: silently turning `my-game` into `MyGame` would
     * make a generated object's name depend on a rule nobody can see, and two modules could
     * sanitise to the same one. Reported once rather than once per round, because KSP calls
     * `process` again for every round and the same misconfiguration is one mistake.
     */
    private fun checkModuleName(): Boolean {
        val moduleName = options.moduleName ?: return true
        if (CodegenOptions.MODULE_NAME_FORMAT.matches(moduleName)) return true
        if (!reportedModuleName) {
            reportedModuleName = true
            logger.error(
                "${CodegenOptions.MODULE_NAME} is '$moduleName', which cannot be part of a " +
                    "generated object name. It must match " +
                    "${CodegenOptions.MODULE_NAME_FORMAT.pattern} — UpperCamelCase, letters " +
                    "and digits only. Nothing this module publishes can be indexed under that " +
                    "name: not its NetModule, not its ToolModule and not its tool manifest.",
            )
        }
        return false
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
     * The module's wire identity: the protocol constant and the lock file.
     *
     * Aggregating, like every module-level output. They genuinely depend on every component in
     * the module (adding one shifts every later id), so marking them isolating would be a
     * correctness bug rather than an optimisation; keeping them to one group per module is what
     * stops that dependency from costing a full rebuild per edited component.
     */
    private fun writeProtocolFiles(
        moduleName: String,
        emitted: List<Pair<ReplicatedComponent, Int>>,
        dependencies: Dependencies,
    ) {
        val lock = ProtocolLock.build(emitted.map { (component, id) -> locked(component, id) })

        writeAggregating(NetProtocolEmitter.emit(moduleName, lock), dependencies)
        codeGenerator.createNewFileByPath(
            dependencies = dependencies,
            path = ProtocolLock.resourcePath(moduleName),
            extensionName = "",
        ).use { stream ->
            stream.write(ProtocolLock.render(lock).toByteArray(StandardCharsets.UTF_8))
        }
    }

    /**
     * The `NetModule` facet, when the build says `udea-net` is on this module's classpath.
     *
     * Members in ascending type id, which is ascending name: the same order `NetRegistry`
     * indexes the array it builds by.
     */
    private fun netFacets(emitted: List<Pair<ReplicatedComponent, Int>>): List<RegistryEmitter.Facet> {
        val service = options.netModuleService ?: return emptyList()
        return listOf(
            RegistryEmitter.Facet(
                service = ClassName.bestGuess(service),
                member = RegistryEmitter.REPLICATORS,
                elements = emitted.sortedBy { it.second }.map { (component, _) ->
                    CodeBlock.of("%T", ClassName(component.className.packageName, component.replicatorName))
                },
            ),
        )
    }

    /** The `LevelComponentModule` facet, when the module declares a saveable component. */
    private fun levelFacets(found: List<LevelComponentScanner.Found>): List<RegistryEmitter.Facet> {
        if (found.isEmpty()) return emptyList()
        return listOf(
            RegistryEmitter.Facet(
                service = CoreNames.LEVEL_COMPONENT_MODULE,
                member = RegistryEmitter.LEVEL_COMPONENTS,
                elements = found.map { RegistryEmitter.levelComponent(it.className) },
            ),
        )
    }

    /**
     * `<Module>ModuleRegistry`, and `<Module>UdeaRegistry` over the modules the build listed.
     *
     * The launcher registry names every listed module's registry as a static reference, so a
     * module the build says is on the classpath and whose registry is not would fail the
     * compile anyway. It is checked here first, by exact name, so the failure names the module
     * and the option rather than surfacing as an unresolved reference in a generated file.
     */
    private fun writeRegistries(
        resolver: Resolver,
        moduleName: String,
        facets: List<RegistryEmitter.Facet>,
        sourceFiles: List<KSFile>,
    ) {
        val self = GeneratedNames.moduleRegistry(moduleName)
        writeAggregating(RegistryEmitter.module(self, moduleName, facets), aggregating(sourceFiles))

        val listed = options.registryModules
        if (listed == null) {
            logger.error(
                "${CodegenOptions.MODULE_NAME} is '$moduleName' but the build did not set " +
                    "${CodegenOptions.REGISTRY_MODULES}, so no launcher registry can be generated " +
                    "for this module - and no launcher would list it either, because the module " +
                    "attribute a launcher's list is read from is only published by build-logic's " +
                    "udeaModule(\"$moduleName\"). Declare the module through udeaModule rather than " +
                    "setting ${CodegenOptions.MODULE_NAME} by hand.",
            )
            return
        }
        if (moduleName !in listed) {
            logger.error(
                "${CodegenOptions.REGISTRY_MODULES} is '${listed.joinToString(",")}', which leaves " +
                    "out this module, '$moduleName'. The list is every module on this module's " +
                    "runtime classpath and the module itself; a launcher registry without its own " +
                    "module would drop everything the module contributes.",
            )
            return
        }
        val others = listed.filter { it != moduleName }.distinct()
        val missing = others.filter { name ->
            !CodegenOptions.MODULE_NAME_FORMAT.matches(name) ||
                resolver.getClassDeclarationByName(
                    resolver.getKSNameFromString(GeneratedNames.moduleRegistry(name).canonicalName),
                ) == null
        }
        for (name in missing) {
            logger.error(
                "${CodegenOptions.REGISTRY_MODULES} lists module '$name', but " +
                    "${GeneratedNames.moduleRegistry(name).canonicalName} is not on this module's " +
                    "classpath. The build lists every module on the runtime classpath that declared " +
                    "itself with udeaModule, so that module did not generate its registry: check that " +
                    "it applies the KSP plugin with udea-codegen and passes both of udeaModule's options.",
            )
        }
        if (missing.isNotEmpty()) return
        writeAggregating(
            RegistryEmitter.launcher(
                GeneratedNames.udeaRegistry(moduleName),
                moduleName,
                (others + moduleName).map(GeneratedNames::moduleRegistry),
            ),
            aggregating(sourceFiles),
        )
    }

    /**
     * The **only** aggregating dependency in the processor.
     *
     * Every module-level output genuinely depends on every source in the module — adding a
     * component shifts every later type id, adding a tool changes the index and the manifest —
     * so claiming otherwise would be a correctness bug rather than an optimisation. Keeping it
     * to one construction site is what stops a fourth module-level output from quietly being
     * written with a fifth opinion about what it depends on.
     */
    private fun aggregating(sourceFiles: List<KSFile>): Dependencies =
        Dependencies(aggregating = true, *sourceFiles.toTypedArray())

    private fun writeAggregating(file: FileSpec, dependencies: Dependencies) {
        codeGenerator.createNewFile(
            dependencies = dependencies,
            packageName = file.packageName,
            fileName = file.name,
        ).use { stream ->
            OutputStreamWriter(stream, StandardCharsets.UTF_8).use(file::writeTo)
        }
    }

    /**
     * The per-declaration agent files: one object per `@AgentTool` and one per class declaring
     * `@AgentState`.
     *
     * Isolating, like a `Replicator`: each is a pure function of the single source file that
     * declared it, so adding a tool reprocesses that tool and not the module.
     */
    private fun agentSourceFiles(result: AgentPass.Result): List<KSFile> =
        result.tools.map(AgentPass.Emitted<ToolModel>::containingFile) +
            result.states.map(AgentPass.Emitted<AgentStateModel>::containingFile)

    private fun writeAgentFiles(result: AgentPass.Result) {
        for (tool in result.tools) writeIsolating(tool.file, tool.containingFile)
        for (state in result.states) writeIsolating(state.file, state.containingFile)
    }

    /**
     * The tool manifest fragment, `udea/<Module>-agent-tools.json`.
     *
     * **Not** gated on the build naming the `ToolModule` interface, unlike the facet. It is
     * data, not code - the CI diff against the checked-in golden is the only thing that turns a
     * reworded description into a reviewable change, and gating it on a runtime dependency
     * would silence that for every module that has not yet grown one.
     */
    private fun writeManifest(moduleName: String, result: AgentPass.Result, dependencies: Dependencies) {
        if (result.tools.isEmpty()) return
        codeGenerator.createNewFileByPath(
            dependencies = dependencies,
            path = ToolManifest.resourcePath(moduleName),
            extensionName = "",
        ).use { stream ->
            stream.write(
                ToolManifest.render(moduleName, result.tools.map(AgentPass.Emitted<ToolModel>::model))
                    .toByteArray(StandardCharsets.UTF_8),
            )
        }
    }

    /**
     * The `ToolModule` and `StateModule` facets.
     *
     * Each is gated on the build telling the processor its interface is on the module's
     * classpath, exactly as the `NetModule` facet is: generated code may only implement an
     * interface that exists, and a module that declares tools for a game which does not ship
     * the agent surface must still compile.
     */
    private fun agentFacets(result: AgentPass.Result): List<RegistryEmitter.Facet> = listOfNotNull(
        options.toolModuleService?.takeIf { result.tools.isNotEmpty() }?.let { service ->
            RegistryEmitter.Facet(
                service = ClassName.bestGuess(service),
                member = RegistryEmitter.TOOLS,
                // Ascending tool name: the order the merged manifest and the dispatch table are
                // both built in, so no consumer has to sort.
                elements = result.tools
                    .map(AgentPass.Emitted<ToolModel>::model)
                    .sortedBy(ToolModel::name)
                    .map { CodeBlock.of("%T", ClassName(it.owner.packageName, it.objectName)) },
            )
        },
        options.stateModuleService?.takeIf { result.states.isNotEmpty() }?.let { service ->
            RegistryEmitter.Facet(
                service = ClassName.bestGuess(service),
                member = RegistryEmitter.STATES,
                elements = result.states
                    .map(AgentPass.Emitted<AgentStateModel>::model)
                    .sortedBy { it.owner.canonicalName }
                    .map { CodeBlock.of("%T", ClassName(it.owner.packageName, it.objectName)) },
            )
        },
    )

    private fun locked(component: ReplicatedComponent, typeId: Int): LockedComponent =
        LockedComponent(
            id = typeId,
            qualifiedName = component.qualifiedName,
            fields = component.fields.map { field ->
                LockedField(index = field.index, name = field.name, wire = field.wireDescription)
            },
        )
}
