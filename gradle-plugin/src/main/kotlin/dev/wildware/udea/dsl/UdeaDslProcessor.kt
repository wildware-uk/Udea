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


    private fun generateGenericAssetFactories(assets: List<KSClassDeclaration>) {
        assets.forEach { decl ->
            val pkg = decl.packageName.asString()
            val name = decl.simpleName.asString()

            val params = decl.primaryConstructor!!.parameters

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
                out.appendLine("fun ${name.replaceFirstChar { it.lowercase() }}(")
                params.forEachIndexed { index, param ->
                    val paramName = param.name?.asString()
                    val paramType = param.type.resolve()
                    val isOptional = param.hasDefault
                    val isList =
                        paramType.declaration.qualifiedName?.asString() == "kotlin.collections.List"

                    if (isList) {
                        out.append(
                            "    $paramName: (ListBuilder<${
                                paramType.arguments.first().type?.resolve()?.toQualifiedString() ?: "Any"
                            }>.() -> Unit)"
                        )
                    } else {
                        out.append("    $paramName: ${paramType.toQualifiedString()}")
                    }
                    if (isOptional) out.append("? = null")
                    if (index < params.size - 1) out.append(",")
                    out.appendLine()
                }
                out.appendLine("): ${decl.qualifiedName!!.asString()} {")
                out.appendLine("    val parameters = mutableMapOf<String, Any?>()")
                params.forEach { param ->
                    val paramName = param.name?.asString() ?: return@forEach
                    val isList = param.type.resolve().declaration.qualifiedName?.asString() == "kotlin.collections.List"
                    if (param.hasDefault) {
                        if (isList) {
                            out.appendLine(
                                "    if ($paramName != null) parameters[\"$paramName\"] = ListBuilder<${
                                    param.type.resolve().arguments.first().type?.resolve()?.toQualifiedString() ?: "Any"
                                }>().apply($paramName).build()"
                            )
                        } else {
                            out.appendLine("    if ($paramName != null) parameters[\"$paramName\"] = $paramName")
                        }
                    } else {
                        if (isList) {
                            out.appendLine(
                                "    parameters[\"$paramName\"] = ListBuilder<${
                                    param.type.resolve().arguments.first().type?.resolve()?.toQualifiedString() ?: "Any"
                                }>().apply($paramName).build()"
                            )
                        } else {
                            out.appendLine("    parameters[\"$paramName\"] = $paramName")
                        }
                    }
                }
                out.appendLine()
                out.appendLine("    return createObject(${decl.qualifiedName!!.asString()}::class, parameters)")
                out.appendLine("}")
                out.appendLine()
                out.appendLine("@UdeaDsl")
                out.appendLine("fun ListBuilder<in ${decl.qualifiedName!!.asString()}>.${name.replaceFirstChar { it.lowercase() }}(")
                params.forEachIndexed { index, param ->
                    val paramName = param.name?.asString()
                    val paramType = param.type.resolve()
                    val isOptional = param.hasDefault
                    val isList = paramType.declaration.qualifiedName?.asString() == "kotlin.collections.List"

                    if (isList) {
                        out.append(
                            "    $paramName: (ListBuilder<${
                                paramType.arguments.first().type?.resolve()?.toQualifiedString() ?: "Any"
                            }>.() -> Unit)"
                        )
                    } else {
                        out.append("    $paramName: ${paramType.toQualifiedString()}")
                    }
                    if (isOptional) out.append("? = null")
                    if (index < params.size - 1) out.append(",")
                    out.appendLine()
                }
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
                out.appendLine("    add(createObject(${decl.qualifiedName!!.asString()}::class, parameters))")
                out.appendLine("}")
            }
        }
    }

    private fun KSType.toQualifiedString(): String {
        val decl = declaration
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
