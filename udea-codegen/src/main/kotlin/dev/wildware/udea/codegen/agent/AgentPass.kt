package dev.wildware.udea.codegen.agent

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.squareup.kotlinpoet.FileSpec
import dev.wildware.udea.codegen.AnnotationNames
import dev.wildware.udea.diagnostics.UdeaRules

/**
 * The agent half of one KSP round: `@AgentTool` and `@AgentState`, collected, checked and
 * turned into the files the processor writes.
 *
 * Separated from `UdeaSymbolProcessor` because it shares nothing with the replication half but
 * the round it runs in. Every cross-cutting check that *can* be made inside one module lives
 * here — a tool name declared twice, a digest key published twice — while the checks that
 * need the whole classpath belong to the runtime index the `ServiceLoader` entry feeds, since
 * no KSP round ever sees more than one module.
 */
internal class AgentPass(private val logger: KSPLogger) {

    private val toolModels = ToolModelBuilder(logger)
    private val stateModels = AgentStateBuilder(logger)

    /** One generated declaration, and the source file it is a pure function of. */
    data class Emitted<T>(val model: T, val file: FileSpec, val containingFile: KSFile)

    /** Everything the agent half of a round produced. */
    data class Result(
        val tools: List<Emitted<ToolModel>>,
        val states: List<Emitted<AgentStateModel>>,
    ) {
        val isEmpty: Boolean get() = tools.isEmpty() && states.isEmpty()
    }

    /** True when the round has no agent symbols at all, so nothing below needs running. */
    fun isEmpty(resolver: Resolver): Boolean =
        resolver.getSymbolsWithAnnotation(AnnotationNames.AGENT_TOOL).none() &&
            resolver.getSymbolsWithAnnotation(AnnotationNames.AGENT_STATE).none()

    fun run(resolver: Resolver): Result {
        val functions = resolver.getSymbolsWithAnnotation(AnnotationNames.AGENT_TOOL)
            .filterIsInstance<KSFunctionDeclaration>()
            // Sorted by FQN so the emitted set and its contents depend on the sources alone,
            // never on the order KSP happened to hand the symbols over.
            .sortedBy(::sortKey)
            .toList()
        val properties = resolver.getSymbolsWithAnnotation(AnnotationNames.AGENT_STATE)
            .filterIsInstance<KSPropertyDeclaration>()
            .sortedBy(::sortKey)
            .toList()

        val tools = functions.mapNotNull { function ->
            val model = toolModels.build(function) ?: return@mapNotNull null
            val containingFile = function.containingFile ?: return@mapNotNull sourceless(function)
            Emitted(model, ToolEmitter.emit(model), containingFile)
        }
        if (!checkUniqueToolNames(tools, functions)) return Result(emptyList(), emptyList())

        val states = stateModels.build(properties).mapNotNull { owned ->
            val containingFile = owned.owner.containingFile ?: return@mapNotNull sourceless(owned.owner)
            Emitted(owned.model, AgentStateEmitter.emit(owned.model), containingFile)
        }
        if (!checkUniqueStateNames(states)) return Result(tools, emptyList())

        return Result(tools, states)
    }

    private fun <T> sourceless(declaration: KSDeclaration): Emitted<T>? {
        logger.error(
            "${declaration.qualifiedName?.asString()} has no source file, so nothing can be " +
                "generated from it. Only declarations compiled from source in this module " +
                "contribute to the agent surface.",
            declaration,
        )
        return null
    }

    /**
     * A tool name is what a caller addresses, so two tools sharing one is not a merge: one of
     * them is unreachable and which one depends on iteration order. The same check runs again
     * at runtime across modules, where a KSP round cannot see.
     */
    private fun checkUniqueToolNames(
        tools: List<Emitted<ToolModel>>,
        functions: List<KSFunctionDeclaration>,
    ): Boolean {
        val duplicates = tools.groupBy { it.model.name }.filterValues { it.size > 1 }
        if (duplicates.isEmpty()) return true
        for ((name, colliding) in duplicates) {
            val declarations = colliding.joinToString(", ") {
                "${it.model.owner.canonicalName}.${it.model.functionName}"
            }
            logger.error(
                "${UdeaRules.AGENT_NAME_COLLISION.id}: ${colliding.size} tools in this module are " +
                    "called '$name' ($declarations). A tool name is what an agent types, so one " +
                    "of them would be unreachable; rename one with @AgentTool(name = \"...\").",
                functions.firstOrNull { it.simpleName.asString() == colliding.first().model.functionName },
            )
        }
        return false
    }

    /** The same rule for digest keys, across every declaring class in the module. */
    private fun checkUniqueStateNames(states: List<Emitted<AgentStateModel>>): Boolean {
        val byName = HashMap<String, MutableList<String>>()
        for (state in states) {
            for (entry in state.model.entries) {
                byName.getOrPut(entry.name) { mutableListOf() } += state.model.owner.canonicalName
            }
        }
        val duplicates = byName.filterValues { it.size > 1 }.toSortedMap()
        if (duplicates.isEmpty()) return true
        for ((name, owners) in duplicates) {
            logger.error(
                "${UdeaRules.AGENT_NAME_COLLISION.id}: the digest key '$name' is published by " +
                    "${owners.size} types in this module (${owners.joinToString(", ")}). The " +
                    "game block is one flat object, so one of the two values would silently " +
                    "replace the other; give one an @AgentState(name = \"...\").",
                null,
            )
        }
        return false
    }

    private fun sortKey(declaration: KSDeclaration): String =
        declaration.qualifiedName?.asString()
            ?: "${(declaration.parentDeclaration as? KSClassDeclaration)?.simpleName?.asString()}." +
            declaration.simpleName.asString()
}
