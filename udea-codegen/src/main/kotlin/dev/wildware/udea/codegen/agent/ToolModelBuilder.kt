package dev.wildware.udea.codegen.agent

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import dev.wildware.udea.codegen.AnnotationNames
import dev.wildware.udea.codegen.replicator.describe
import dev.wildware.udea.codegen.replicator.toClassName
import dev.wildware.udea.diagnostics.UdeaRules

/**
 * Turns an `@AgentTool` function into a [ToolModel], or reports why it cannot and returns
 * `null`.
 *
 * The failure policy is `ComponentModelBuilder`'s, for the same reason: no `catch`, every
 * diagnostic at the offending symbol, and a tool with any error emits **no file at all**
 * rather than a partial one. A tool that silently failed to generate would not be a missing
 * file; it would be a capability an agent is told about in one place and cannot call in
 * another.
 */
internal class ToolModelBuilder(private val logger: KSPLogger) {

    fun build(function: KSFunctionDeclaration): ToolModel? {
        var failed = false

        val owner = function.parentDeclaration as? KSClassDeclaration
        if (owner == null || owner.classKind !in TOOLSET_KINDS) {
            logger.error(
                "@AgentTool must be declared inside a class or object. The declaring type is " +
                    "the toolset the tool is grouped under in the manifest, and it is the " +
                    "receiver the generated dispatcher calls the function on; a top-level " +
                    "function has neither.",
                function,
            )
            return null
        }
        if (Modifier.SUSPEND in function.modifiers) {
            logger.error(
                "@AgentTool ${function.simpleName.asString()} is suspend. A tool call is " +
                    "drained on the simulation thread through SimBarrier, which has no " +
                    "coroutine context to resume into; make the function blocking and let the " +
                    "agent host decide what to wait for.",
                function,
            )
            failed = true
        }
        if (function.extensionReceiver != null) {
            logger.error(
                "@AgentTool ${function.simpleName.asString()} is an extension function, so the " +
                    "generated dispatcher has no way to supply its receiver. Make it a member " +
                    "of the toolset class.",
                function,
            )
            failed = true
        }

        val annotation = function.annotations.single {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == AnnotationNames.AGENT_TOOL
        }
        val declaredName = annotation.stringArgument("name").orEmpty()
        val name = declaredName.ifEmpty { AgentNaming.snakeCase(function.simpleName.asString()) }
        if (!AgentNaming.NAME_FORMAT.matches(name)) {
            logger.error(
                "@AgentTool name '$name' is not a legal MCP tool name. It must match " +
                    "${AgentNaming.NAME_FORMAT.pattern} - lower_snake_case, which is what a " +
                    "model expects to type and what the manifest's uniqueness check keys on.",
                function,
            )
            failed = true
        }

        val description = annotation.stringArgument("description").orEmpty().trim()
        if (!checkDescription(description, function, name)) failed = true

        val args = ArrayList<ToolArgModel>(function.parameters.size)
        for (parameter in function.parameters) {
            val arg = describeArgument(function, parameter)
            if (arg == null) failed = true else args += arg
        }

        if (failed) return null

        val ownerName = owner.toClassName()
        return ToolModel(
            name = name,
            description = description,
            owner = ownerName,
            toolset = AgentNaming.snakeCase(ownerName.simpleName),
            functionName = function.simpleName.asString(),
            objectName = AgentNaming.toolObjectName(ownerName.simpleName, function.simpleName.asString()),
            args = args,
        )
    }

    /**
     * The description gate.
     *
     * Spec section 6 makes description quality a Phase 1 exit criterion, and the reason is not
     * tidiness: the description is the only text a model has when it decides whether to reach
     * for this tool, so an undescribed tool is worse than a missing one - it gets called for
     * the wrong reason and its answer is believed.
     */
    private fun checkDescription(description: String, function: KSFunctionDeclaration, name: String): Boolean {
        if (description.isEmpty()) {
            logger.error(
                "${UdeaRules.AGENT_TOOL_DESCRIPTION.id}: @AgentTool $name has no description. " +
                    "Write one sentence saying what the tool does AND when an agent should " +
                    "reach for it - this is the text the model reasons over, and it is the " +
                    "only thing standing between a correct call and a plausible wrong one.",
                function,
            )
            return false
        }
        if (description.length < UdeaRules.MIN_TOOL_DESCRIPTION) {
            logger.error(
                "${UdeaRules.AGENT_TOOL_DESCRIPTION.id}: @AgentTool $name is described in " +
                    "${description.length} characters (\"$description\"), under the " +
                    "${UdeaRules.MIN_TOOL_DESCRIPTION} a usable description takes. Say what it " +
                    "does and when to use it, not what it is called.",
                function,
            )
            return false
        }
        return true
    }

    /** One parameter, or `null` having reported why it cannot be published. */
    private fun describeArgument(function: KSFunctionDeclaration, parameter: KSValueParameter): ToolArgModel? {
        val parameterName = parameter.name?.asString() ?: run {
            logger.error("@AgentTool parameters must be named", parameter)
            return null
        }
        val arg = parameter.annotations.firstOrNull {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == AnnotationNames.ARG
        }
        val description = arg?.stringArgument("description").orEmpty().trim()
        if (description.isEmpty()) {
            logger.error(
                "${UdeaRules.AGENT_ARG_DESCRIPTION.id}: parameter '$parameterName' of " +
                    "@AgentTool ${function.simpleName.asString()} has no @Arg description, so " +
                    "its JSON Schema property tells the model nothing about what to put in it. " +
                    "Add @Arg(description = \"...\"), including the units and the legal range.",
                parameter,
            )
            return null
        }

        val type = parameter.type.resolve()
        val spec = resolveType(type) ?: run {
            logger.error(
                "${UdeaRules.AGENT_TOOL_UNSUPPORTED_TYPE.id}: parameter '$parameterName' of " +
                    "@AgentTool ${function.simpleName.asString()} is ${type.describe()}, which " +
                    "has no JSON Schema type and no coercion from the query string a tool call " +
                    "arrives as. Use ${ArgKind.supported}. A composite argument is not " +
                    "supported: pass its parts, or pass a NetId and let the tool read the " +
                    "component through its Replicator.",
                parameter,
            )
            return null
        }

        // "A Kotlin parameter with a default value is emitted as optional regardless" - the
        // schema has to describe the call an agent can actually make, and it can omit any
        // parameter the function will fill in for itself.
        val optional = parameter.hasDefault || arg?.booleanArgument("required") == false
        val nullable = type.isMarkedNullable
        val defaultText = arg?.stringArgument("default").orEmpty().ifEmpty { null }

        if (optional && !nullable && defaultText == null) {
            logger.error(
                "parameter '$parameterName' of @AgentTool ${function.simpleName.asString()} is " +
                    "optional but declares no @Arg(default = \"...\"). KSP cannot read a Kotlin " +
                    "default's expression, and the generated dispatcher always passes an " +
                    "explicit value, so the default an agent is told about in the manifest has " +
                    "to be written where both the manifest and the dispatcher can read it. " +
                    "Either add @Arg(default = \"...\") or make the parameter's type nullable, " +
                    "which publishes it as optional with no default.",
                parameter,
            )
            return null
        }
        if (!optional && defaultText != null) {
            logger.error(
                "parameter '$parameterName' of @AgentTool ${function.simpleName.asString()} is " +
                    "required but declares @Arg(default = \"$defaultText\"). A default on a " +
                    "required argument is never reachable; drop one of the two.",
                parameter,
            )
            return null
        }

        val enumConstants = if (spec.kind == ArgKind.ENUM) spec.enumConstants else emptyList()
        // A list default is the text an agent would have sent, so it is checked element by
        // element - the same split the generated dispatcher performs.
        val defaultParts = defaultText?.let { if (spec.list) it.split(',') else listOf(it) }.orEmpty()
        val badPart = defaultParts.firstOrNull {
            ArgDefaults.literal(spec.kind, spec.enumType, enumConstants, it) == null
        }
        if (badPart != null) {
            logger.error(
                "@Arg(default = \"$defaultText\") on '$parameterName' of " +
                    "${function.simpleName.asString()} contains \"$badPart\", which is not " +
                    "${spec.kind.expectation}. Folding it here is what stops the value an agent " +
                    "gets when it omits the argument from failing on the first call instead.",
                parameter,
            )
            return null
        }

        return ToolArgModel(
            name = parameterName,
            kind = spec.kind,
            list = spec.list,
            enumType = spec.enumType,
            enumConstants = enumConstants,
            description = description,
            required = !optional,
            defaultText = defaultText,
            nullable = nullable,
        )
    }

    /** A resolved parameter type: its scalar kind, whether it is wrapped in a `List`, its enum. */
    private data class TypeSpec(
        val kind: ArgKind,
        val list: Boolean,
        val enumType: ClassName?,
        val enumConstants: List<String>,
    )

    private fun resolveType(type: KSType): TypeSpec? {
        val declaration = type.declaration
        val qualifiedName = declaration.qualifiedName?.asString() ?: return null
        if (qualifiedName == LIST_FQN) {
            // One level only: `List<List<Int>>` has no query-string encoding that is not a
            // guess, and a guess in an agent-facing schema is a wrong call waiting to happen.
            val element = type.arguments.singleOrNull()?.type?.resolve() ?: return null
            val inner = resolveType(element) ?: return null
            return if (inner.list) null else inner.copy(list = true)
        }
        ArgKind.scalarOf(qualifiedName)?.let {
            return TypeSpec(it, list = false, enumType = null, enumConstants = emptyList())
        }
        val enum = declaration as? KSClassDeclaration ?: return null
        if (enum.classKind != ClassKind.ENUM_CLASS) return null
        return TypeSpec(
            kind = ArgKind.ENUM,
            list = false,
            enumType = enum.toClassName(),
            enumConstants = enum.declarations
                .filterIsInstance<KSClassDeclaration>()
                .filter { it.classKind == ClassKind.ENUM_ENTRY }
                .map { it.simpleName.asString() }
                .toList(),
        )
    }

    private companion object {
        const val LIST_FQN = "kotlin.collections.List"
        val TOOLSET_KINDS = setOf(ClassKind.CLASS, ClassKind.OBJECT)
    }
}

/** The named `String` argument of an annotation, or `null` if it was not written. */
internal fun KSAnnotation.stringArgument(name: String): String? =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? String

/** The named `Boolean` argument of an annotation, or `null` if it was not written. */
internal fun KSAnnotation.booleanArgument(name: String): Boolean? =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? Boolean
