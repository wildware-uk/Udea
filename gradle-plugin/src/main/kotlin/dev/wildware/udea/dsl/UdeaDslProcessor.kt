package dev.wildware.udea.dsl

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import java.io.OutputStreamWriter

private val DefaultClasses = listOf(
    "com.github.quillraven.fleks.Component"
)

class UdeaDslProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    private fun getDslIncludeProperties(decl: KSClassDeclaration): List<KSPropertyDeclaration> {
        return decl.getAllProperties().filter { prop ->
            prop.annotations.any {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == DslInclude::class.qualifiedName
            }
        }.toList()
    }

    private fun validateProperty(prop: KSPropertyDeclaration) {
        if (!prop.isMutable) {
            throw IllegalStateException("Property ${prop.simpleName.asString()} marked with @DslInclude must be mutable (var)")
        }
    }

    private fun writeParameterDeclarations(
        out: OutputStreamWriter,
        params: List<KSValueParameter>,
        includedProps: List<KSPropertyDeclaration> = emptyList()
    ) {
        params.forEachIndexed { index, param ->
            val paramName = param.name?.asString()
            val paramType = param.type.resolve()
            val isOptional = param.hasDefault
            val isList = isListType(paramType)

            if (isList) {
                out.append(getListParameterDeclaration(paramName, paramType))
            } else {
                out.append("    $paramName: ${paramType.toQualifiedString()}")
            }
            if (isOptional) out.append("? = null")
            if (index < params.size - 1 || includedProps.isNotEmpty()) out.append(",")
            out.appendLine()
        }

        includedProps.forEachIndexed { index, prop ->
            val propName = prop.simpleName.asString()
            val propType = prop.type.resolve()
            out.append("    $propName: ${propType.toQualifiedString()}? = null")
            if (index < includedProps.size - 1) out.append(",")
            out.appendLine()
        }
    }

    private fun writeParameterAssignments(out: OutputStreamWriter, params: List<KSValueParameter>) {
        params.forEach { param ->
            val paramName = param.name?.asString() ?: return@forEach
            val paramType = param.type.resolve()
            val isList = isListType(paramType)
            writeParameterAssignment(out, param, paramName, isList)
        }
    }

    private fun writeParameterAssignment(
        out: OutputStreamWriter,
        param: KSValueParameter,
        paramName: String,
        isList: Boolean
    ) {
        if (param.hasDefault) {
            if (isList) {
                out.appendLine(getListBuilderAssignment(paramName, param.type.resolve(), true))
            } else {
                out.appendLine("    if ($paramName != null) parameters[\"$paramName\"] = $paramName")
            }
        } else {
            if (isList) {
                out.appendLine(getListBuilderAssignment(paramName, param.type.resolve(), false))
            } else {
                out.appendLine("    parameters[\"$paramName\"] = $paramName")
            }
        }
    }

    private fun isListType(type: KSType): Boolean =
        type.declaration.qualifiedName?.asString() == "kotlin.collections.List"

    private fun getListParameterDeclaration(paramName: String?, paramType: KSType): String =
        "    $paramName: (ListBuilder<${
            paramType.arguments.first().type?.resolve()?.toQualifiedString() ?: "Any"
        }>.() -> Unit)"

    private fun getListBuilderAssignment(paramName: String, paramType: KSType, isOptional: Boolean): String {
        val baseAssignment =
            "    ${if (isOptional) "if ($paramName != null) " else ""}parameters[\"$paramName\"] = ListBuilder<${
                paramType.arguments.first().type?.resolve()?.toQualifiedString() ?: "Any"
            }>().apply($paramName).build()"
        return baseAssignment
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()
        val assetClasses = mutableListOf<KSClassDeclaration>()

        resolver.getNewFiles().forEach { file ->
            file.declarations
                .filterIsInstance<KSClassDeclaration>()
                .filter { it.classKind == ClassKind.CLASS || it.classKind == ClassKind.INTERFACE }
                .forEach { decl ->
                    if (!decl.validate()) {
                        deferred += decl
                        return@forEach
                    }
                    if (decl.getAllSuperTypes().any { superType ->
                            superType.declaration.qualifiedName?.asString() in DefaultClasses ||
                                    superType.declaration.annotations.any {
                                        it.annotationType.resolve().declaration.qualifiedName?.asString() == CreateDsl::class.qualifiedName
                                    }
                        } || decl.annotations.any {
                            it.annotationType.resolve().declaration.qualifiedName?.asString() == CreateDsl::class.qualifiedName
                        }) {
                        assetClasses += decl
                    }
                }
        }

        if (assetClasses.isNotEmpty()) {
            logger.info("UdeaDslProcessor: discovered ${assetClasses.size} Asset subclasses for DSL generation.")
            generateGenericAssetFactories(assetClasses)
        }

        return deferred
    }


    private fun getNameFromCreateDsl(decl: KSClassDeclaration): String? {
        return (decl.annotations
            .find { it.annotationType.resolve().declaration.qualifiedName?.asString() == CreateDsl::class.qualifiedName }
            ?.arguments
            ?.find { it.name?.asString() == "name" }
            ?.value as String?)
            ?.takeIf { it.isNotBlank() }
    }

    private fun getTypeParameters(decl: KSClassDeclaration): String {
        val typeParams = decl.typeParameters
        return if (typeParams.isEmpty()) {
            ""
        } else {
            typeParams.joinToString(", ", prefix = "<", postfix = ">") { param ->
                buildString {
                    append(param.name.asString())
                    val bounds = param.bounds.toList()
                    if (bounds.isNotEmpty()) {
                        append(" : ")
                        append(bounds.joinToString(" & ") { it.resolve().toQualifiedString() })
                    }
                }
            }
        }
    }

    private fun getTypeArgumentsOnly(decl: KSClassDeclaration): String {
        val typeParams = decl.typeParameters
        return if (typeParams.isEmpty()) {
            ""
        } else {
            typeParams.joinToString(", ", prefix = "<", postfix = ">") { it.name.asString() }
        }
    }

    private fun generateGenericAssetFactories(assets: List<KSClassDeclaration>) {
        assets.forEach { decl ->
            val pkg = decl.packageName.asString()
            val name = getNameFromCreateDsl(decl) ?: decl.simpleName.asString()

            val params = decl.primaryConstructor!!.parameters
            val typeParams = getTypeParameters(decl)
            val typeArgs = getTypeArgumentsOnly(decl)

            val file = codeGenerator.createNewFile(
                Dependencies(false, *assets.mapNotNull { it.containingFile }.toTypedArray()),
                packageName = decl.packageName.asString(),
                fileName = "${name}Dsl"
            )
            OutputStreamWriter(file, Charsets.UTF_8).use { out ->
                out.appendLine("package ${decl.packageName.asString()}")
                out.appendLine()
                out.appendLine("import dev.wildware.udea.assets.dsl.*")
                out.appendLine()

                // Skip abstract classes or interfaces
                if (decl.modifiers.contains(Modifier.ABSTRACT) || decl.classKind != ClassKind.CLASS) return@forEach

                out.appendLine("@UdeaDsl")
                out.appendLine("fun $typeParams ${name.replaceFirstChar { it.lowercase() }}(")
                writeParameterDeclarations(out, params, getDslIncludeProperties(decl))
                out.appendLine("): ${decl.qualifiedName!!.asString()}$typeArgs {")
                out.appendLine("    val parameters = mutableMapOf<String, Any?>()")
                writeParameterAssignments(out, params)
                out.appendLine()
                val castSuffix = if (typeArgs.isNotEmpty()) " as ${decl.qualifiedName!!.asString()}$typeArgs" else ""
                out.appendLine("    val obj = createObject(${decl.qualifiedName!!.asString()}::class, parameters)$castSuffix")
                getDslIncludeProperties(decl).forEach { prop ->
                    validateProperty(prop)
                    val propName = prop.simpleName.asString()
                    out.appendLine("    if ($propName != null) obj.$propName = $propName")
                }
                out.appendLine("    return obj")
                out.appendLine("}")
                out.appendLine()
                out.appendLine("@UdeaDsl")
                out.appendLine("fun $typeParams ListBuilder<in ${decl.qualifiedName!!.asString()}$typeArgs>.${name.replaceFirstChar { it.lowercase() }}(")
                writeParameterDeclarations(out, params, getDslIncludeProperties(decl))
                out.appendLine(") {")
                out.appendLine("    val parameters = mutableMapOf<String, Any?>()")
                params.forEach { param ->
                    val paramName = param.name?.asString() ?: return@forEach
                    val paramType = param.type.resolve()
                    val isList =
                        paramType.declaration.qualifiedName?.asString()?.startsWith("kotlin.collections.List") == true

                    if (isList) {
                        if (param.hasDefault) {
                            out.appendLine(
                                "    if ($paramName != null) parameters[\"$paramName\"] = ListBuilder<${
                                    paramType.arguments.first().type?.resolve()?.toQualifiedString() ?: "Any"
                                }>().apply($paramName).build()"
                            )
                        } else {
                            out.appendLine(
                                "    parameters[\"$paramName\"] = ListBuilder<${
                                    paramType.arguments.first().type?.resolve()?.toQualifiedString() ?: "Any"
                                }>().apply($paramName).build()"
                            )
                        }
                    } else {
                        if (param.hasDefault) {
                            out.appendLine("    if ($paramName != null) parameters[\"$paramName\"] = $paramName")
                        } else {
                            out.appendLine("    parameters[\"$paramName\"] = $paramName")
                        }
                    }
                }
                out.appendLine()
                out.appendLine("    val obj = createObject(${decl.qualifiedName!!.asString()}::class, parameters)$castSuffix")
                getDslIncludeProperties(decl).forEach { prop ->
                    validateProperty(prop)
                    val propName = prop.simpleName.asString()
                    out.appendLine("    if ($propName != null) obj.$propName = $propName")
                }
                out.appendLine("    add(obj)")
                out.appendLine("}")
            }
        }
    }

    private fun KSType.toQualifiedString(): String {
        val decl = declaration
        
        // If this is a type parameter, just return its simple name
        if (decl is KSTypeParameter) {
            return if (isMarkedNullable) "${decl.simpleName.asString()}?" else decl.simpleName.asString()
        }
        
        val pkg = decl.packageName.asString()
        val simple = decl.qualifiedName?.asString() ?: decl.simpleName.asString()

        val args = arguments.takeIf { it.isNotEmpty() }?.joinToString(", ") { arg ->
            buildString {
                if (arg.variance != Variance.INVARIANT) append("${arg.variance.label} ")
                append(arg.type?.resolve()?.toQualifiedString() ?: "*")
            }
        }

        val qualified = if (pkg.isNotEmpty()) simple else simple
        val rendered = if (args != null) "$qualified<$args>" else qualified
        return if (isMarkedNullable) "$rendered?" else rendered
    }
}

class UdeaDslProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return UdeaDslProcessor(environment.codeGenerator, environment.logger)
    }
}
