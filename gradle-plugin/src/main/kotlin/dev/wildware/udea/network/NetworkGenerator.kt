package dev.wildware.udea.network

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType

/**
 * Generates code for network synchronization.
 * */
class NetworkGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    private val existingSerializers = mutableMapOf<String, String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        findExistingSerializers(resolver)
        generateSerializers(resolver)
        println("EXISTING SERIALIZERS: $existingSerializers")
        return emptyList()
    }

    private fun generateSerializers(resolver: Resolver) {
        resolver.getSymbolsWithAnnotation(UdeaNetworked::class.qualifiedName!!)
            .forEach { symbol ->
                try {
                    if (symbol !is KSClassDeclaration) return@forEach

                    val className = symbol.simpleName.asString()
                    val packageName = symbol.packageName.asString()
                    val serializerName = "${className}NetworkSerializer"

                    if (existingSerializers.containsKey(symbol.qualifiedName?.asString())) {
                        return@forEach
                    }

                    val fileContent = buildString {
                        appendLine("package $packageName")
                        appendLine()
                        appendLine("import dev.wildware.udea.network.InPlaceSerializer")
                        appendLine("import dev.wildware.udea.network.UdeaSerializer")
                        appendLine("import java.nio.ByteBuffer")
                        appendLine()
                        appendLine("@UdeaSerializer($className::class)")
                        appendLine("object $serializerName : InPlaceSerializer<$className> {")
                        appendLine("    override fun serialize(component: $className, byteBuffer: ByteBuffer) {")
                        symbol.getAllProperties().filter {
                            it.annotations.any { ann -> ann.shortName.asString() == "UdeaSync" }
                        }.forEach { property ->
                            val propertyName = "component.${property.simpleName.asString()}"
                            val propertyType = property.type.resolve()
                            appendLine(
                                serializeLine(
                                    this,
                                    propertyName,
                                    propertyType
                                )
                            )
                        }
                        appendLine("    }")
                        appendLine()
                        appendLine("    override fun deserialize(component: $className, byteBuffer: ByteBuffer) {")
                        symbol.getAllProperties().filter {
                            it.annotations.any { ann -> ann.shortName.asString() == "UdeaSync" }
                        }.forEach { property ->
                            val propertyName = "component.${property.simpleName.asString()}"
                            val propertyType = property.type.resolve()
                            appendLine(
                                deserializeLine(
                                    this,
                                    propertyName,
                                    propertyType
                                )
                            )
                        }
                        appendLine("    }")
                        appendLine("}")
                    }

                    codeGenerator.createNewFile(
                        dependencies = Dependencies(false, symbol.containingFile!!),
                        packageName = packageName,
                        fileName = serializerName
                    ).write(fileContent.toByteArray())
                } catch (e: Exception) {
                    error("Cannot generate serializer for $symbol: ${e.message}")
                }
            }
    }

    private fun serializeLine(out: StringBuilder, property: String, type: KSType): String {
        val typeName = type.declaration.qualifiedName?.asString() ?: type.declaration.simpleName.asString()
        return when (typeName) {
            "kotlin.Int" -> "        byteBuffer.putInt($property)"
            "kotlin.Float" -> "        byteBuffer.putFloat($property)"
            "kotlin.Boolean" -> "        byteBuffer.put(if(element) 1 else 0)"
            "kotlin.String" -> "        byteBuffer.putUTF($property)"
            "kotlin.Array" -> """
                    for(element in $property) {
                        ${serializeLine(out, "element", type.arguments.first().type!!.resolve())}
                    }
                """.trimIndent()

            in existingSerializers -> "        ${existingSerializers[typeName]}.serialize($property, byteBuffer)"
            else -> error("Cannot serialize $typeName, add a serializer for it")
        }
    }

    private fun deserializeLine(out: StringBuilder, property: String, type: KSType): String {
        val typeName = type.declaration.qualifiedName?.asString() ?: type.declaration.simpleName.asString()

        return when (typeName) {
            "kotlin.Int" -> "        $property = byteBuffer.getInt()"
            "kotlin.Float" -> "        $property = byteBuffer.getFloat()"
            "kotlin.Boolean" -> "        $property = byteBuffer.get() != 0.toByte()"
            "kotlin.String" -> "        $property = byteBuffer.getUTF()"
            "kotlin.Array" -> """
                    for(i in $property.indices) {
                        ${deserializeLine(out, "$property[i]", type.arguments.first().type!!.resolve())}
                    }
                """

            in existingSerializers -> "        ${existingSerializers[typeName]}.deserialize($property, byteBuffer)"
            else -> error("Cannot deserialize $typeName, add a serializer for it")
        }
    }

    private fun findExistingSerializers(resolver: Resolver) {
        existingSerializers.clear()
        resolver.getSymbolsWithAnnotation(UdeaSerializer::class.qualifiedName!!)
            .forEach { symbol ->
                val annotation = symbol.annotations.first { it.shortName.asString() == "UdeaSerializer" }
                val forClass = (annotation.arguments.first().value.let {
                    (it as? KSType)?.declaration as? KSClassDeclaration
                })?.qualifiedName?.asString()
                val symbolFqcn = (symbol as? KSClassDeclaration)?.qualifiedName?.asString()
                if (forClass != null && symbolFqcn != null) {
                    existingSerializers[forClass] = symbolFqcn
                }
            }
    }
}

class NetworkGeneratorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return NetworkGenerator(environment.codeGenerator, environment.logger)
    }
}
