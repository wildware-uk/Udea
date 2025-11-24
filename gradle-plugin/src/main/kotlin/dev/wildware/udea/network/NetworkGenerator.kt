package dev.wildware.udea.network

import com.google.devtools.ksp.*
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*

/**
 * Generates code for network synchronization.
 * */
class NetworkGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) : SymbolProcessor {
    private val existingSerializers = mutableMapOf<String, String>()
    private val allSerializersForIndex = mutableMapOf<String, String>()
    private var indexGenerated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        logger.warn("[NetworkGen] ========== Starting process() ==========")

        // Load serializers from generated registry files in dev.wildware._serializer_ package
        loadSerializersFromRegistryPackage(resolver)

        // Find all @UdeaSerializer annotated classes in current sources
        findExistingSerializers(resolver)

        logger.warn("[NetworkGen] Found ${existingSerializers.size} existing serializers")
        existingSerializers.forEach { (target, serializer) ->
            logger.warn("[NetworkGen]   $target -> $serializer")
        }

        // Collect all for index
        allSerializersForIndex.putAll(existingSerializers)

        // Generate new serializers for @UdeaNetworked classes that don't have serializers yet
        generateSerializers(resolver)

        // Generate registry file in dev.wildware._serializer_ package
        generateSerializerRegistry()

        logger.warn("[NetworkGen] ========== Finished process() ==========")
        return emptyList()
    }

    private fun generateSerializers(resolver: Resolver) {
        var generatedCount = 0

        resolver.getSymbolsWithAnnotation(UdeaNetworked::class.qualifiedName!!)
            .forEach { symbol ->
                try {
                    if (symbol !is KSClassDeclaration) return@forEach

                    val className = symbol.simpleName.asString()
                    val packageName = symbol.packageName.asString()
                    val serializerName = "${className}NetworkSerializer"
                    val targetFqcn = symbol.qualifiedName!!.asString()
                    val serializerFqcn = "$packageName.$serializerName"

                    // Skip if serializer already exists
                    if (existingSerializers.containsKey(targetFqcn)) {
                        logger.warn("[NetworkGen] Skipping $targetFqcn - serializer already exists")
                        return@forEach
                    }

                    // Collect @UdeaSync properties
                    val syncProperties = symbol.getAllProperties().filter {
                        it.annotations.any { ann -> ann.shortName.asString() == "UdeaSync" }
                    }.map { property ->
                        "component.${property.simpleName.asString()}" to property.type.resolve()
                    }

                    // Collect delegated properties from companion object
                    val delegatedProperties = extractDelegatedProperties(symbol, resolver)

                    // Combine all properties to serialize
                    val allProperties = syncProperties + delegatedProperties.map { (expr, type) ->
                        "component.$expr" to type
                    }

                    val fileContent = buildString {
                        appendLine("package $packageName")
                        appendLine()
                        appendLine("import java.nio.ByteBuffer")
                        appendLine("import dev.wildware.udea.network.*")
                        appendLine()
                        appendLine("@UdeaSerializer($className::class)")
                        appendLine("object $serializerName : InPlaceSerializer<$className> {")
                        appendLine("    override fun serialize(component: $className, data: ByteBuffer) {")
                        allProperties.forEach { (propertyName, propertyType) ->
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
                        appendLine("    override fun deserialize(component: $className, data: ByteBuffer) {")
                        allProperties.forEach { (propertyName, propertyType) ->
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
                        dependencies = Dependencies(aggregating = true, symbol.containingFile!!),
                        packageName = packageName,
                        fileName = serializerName
                    ).write(fileContent.toByteArray())

                    allSerializersForIndex[targetFqcn] = serializerFqcn
                    generatedCount++
                    logger.warn("[NetworkGen] Generated: $targetFqcn -> $serializerFqcn")
                } catch (e: Exception) {
                    logger.error("Cannot generate serializer for $symbol: ${e.message}")
                }
            }

        logger.warn("[NetworkGen] Generated $generatedCount new serializers")
    }

    private fun serializeLine(out: StringBuilder, property: String, type: KSType): String {
        val typeName = type.declaration.qualifiedName?.asString() ?: type.declaration.simpleName.asString()
        return when {
            typeName == "kotlin.Int" -> "        data.putInt($property)"
            typeName == "kotlin.Float" -> "        data.putFloat($property)"
            typeName == "kotlin.Boolean" -> "        data.putBoolean($property)"
            typeName == "kotlin.String" -> "        data.putString($property)"
            typeName == "kotlin.Array" -> """
                    for(element in $property) {
                        ${serializeLine(out, "element", type.arguments.first().type!!.resolve())}
                    }
                """.trimIndent()

            isPolymorphic(type) -> "        polymorphicSerializerFor($property::class).serialize($property, data)"

            typeName in existingSerializers -> "        ${existingSerializers[typeName]}.serialize($property, data)"
            else -> "        data.putSerializable($property)"
        }
    }

    private fun deserializeLine(out: StringBuilder, property: String, type: KSType): String {
        val typeName = type.declaration.qualifiedName?.asString() ?: type.declaration.simpleName.asString()

        return when {
            typeName == "kotlin.Int" -> "        $property = data.getInt()"
            typeName == "kotlin.Float" -> "        $property = data.getFloat()"
            typeName == "kotlin.Boolean" -> "        $property = data.getBoolean()"
            typeName == "kotlin.String" -> "        $property = data.getString()"
            typeName == "kotlin.Array" -> """
                for(i in $property.indices) {
                    ${deserializeLine(out, "$property[i]", type.arguments.first().type!!.resolve())}
                }
            """

            isPolymorphic(type) -> "        polymorphicSerializerFor($property::class).deserialize($property, data)"

            typeName in existingSerializers -> "        ${existingSerializers[typeName]}.deserialize($property, data)"
            else -> "        $property = data.getSerializable()"
        }
    }

    private fun isPolymorphic(type: KSType): Boolean =
        (type.declaration as? KSClassDeclaration)?.isAbstract() == true || (type.declaration as? KSClassDeclaration)?.classKind == ClassKind.INTERFACE

    @OptIn(KspExperimental::class)
    private fun loadSerializersFromRegistryPackage(resolver: Resolver) {
        try {
            // Scan the special registry package for registry files
            val registryPackage = "dev.wildware._serializer_"
            val declarations = resolver.getDeclarationsFromPackage(registryPackage)

            declarations.filterIsInstance<KSClassDeclaration>().forEach { classDecl ->
                if (classDecl.simpleName.asString().startsWith("UdeaSerializerRegistry_")) {
                    logger.warn("[NetworkGen] Found registry: ${classDecl.qualifiedName?.asString()}")

                    // Find @UdeaSerializers annotation and read its value
                    classDecl.annotations.forEach { annotation ->
                        if (annotation.shortName.asString() == "UdeaSerializers") {
                            val value = annotation.arguments.firstOrNull()?.value as? String
                            if (value != null) {
                                parseSerializersString(value)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("[NetworkGen] Failed to load from registry package: ${e.message}")
        }
    }

    private fun parseSerializersString(encoded: String) {
        // Format: "target1=serializer1;target2=serializer2;..."
        var count = 0
        encoded.split(";").forEach { pair ->
            val trimmed = pair.trim()
            if (trimmed.isNotEmpty()) {
                val parts = trimmed.split("=", limit = 2)
                if (parts.size == 2) {
                    val targetClass = parts[0].trim()
                    val serializerClass = parts[1].trim()
                    existingSerializers[targetClass] = serializerClass
                    count++
                }
            }
        }
        if (count > 0) {
            logger.warn("[NetworkGen] Loaded $count serializers from registry const val")
        }
    }

    fun KSNode.dump(indent: String = "") {
        logger.warn("$indent${this::class.simpleName}: $this")

        if (this is KSDeclarationContainer) {
            declarations.forEach { it.dump("$indent  ") }
        }
        if (this is KSClassDeclaration) {
            getDeclaredFunctions().forEach { it.dump("$indent  ") }
            getDeclaredProperties().forEach { it.dump("$indent  ") }
        }
    }

    /**
     * Extracts delegated property expressions from a class's companion object if it extends UdeaComponentType
     */
    private fun extractDelegatedProperties(
        classDecl: KSClassDeclaration,
        resolver: Resolver
    ): List<Pair<String, KSType>> {
        try {
            // Find companion object
            val companionObject = classDecl.declarations
                .filterIsInstance<KSClassDeclaration>()
                .firstOrNull { it.isCompanionObject }
                ?: return emptyList()

            // Check if companion extends UdeaComponentType
            val extendsUdeaComponentType = companionObject.superTypes.any { superType ->
                val declaration = superType.resolve().declaration
                declaration.qualifiedName?.asString()
                    ?.startsWith("dev.wildware.udea.ecs.component.UdeaComponentType") == true
            }

            if (!extendsUdeaComponentType) {
                return emptyList()
            }

            println("HI PEOPLE")

            logger.warn("[NetworkGen] Found companion object extending UdeaComponentType in ${classDecl.simpleName.asString()}")

            // Look for networkComponent property initialization
            val delegatedProps = mutableListOf<Pair<String, KSType>>()
            val sourceFile = classDecl.containingFile

            if (sourceFile != null) {
                val content = sourceFile.filePath
                try {
                    val fileContent = java.io.File(content).readText()

                    // Parse delegate { } blocks from the DSL
                    val delegatePattern = Regex("""delegate\s*\{\s*([^}]+)\s*\}""")
                    delegatePattern.findAll(fileContent).forEach { match ->
                        val expression = match.groupValues[1].trim()
                        logger.warn("[NetworkGen] Found delegated property expression: $expression")

                        // Try to resolve the type of this expression
                        val propertyType = resolveDelegatedPropertyType(expression, classDecl, resolver)
                        if (propertyType != null) {
                            delegatedProps.add(expression to propertyType)
                            logger.warn("[NetworkGen] Resolved type: ${propertyType.declaration.qualifiedName?.asString()}")
                        } else {
                            logger.warn("[NetworkGen] Could not resolve type for: $expression")
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("[NetworkGen] Failed to read source file: ${e.message}")
                }
            }

            return delegatedProps
        } catch (e: Exception) {
            logger.warn("[NetworkGen] Error extracting delegated properties: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Attempts to resolve the type of a delegated property expression like "body.linearVelocity"
     */
    private fun resolveDelegatedPropertyType(
        expression: String,
        classDecl: KSClassDeclaration,
        resolver: Resolver
    ): KSType? {
        try {
            // Split the expression by dots to traverse properties
            val parts = expression.split(".")
            if (parts.isEmpty()) return null

            var currentType: KSType? = null
            var currentDecl: KSDeclaration? = classDecl

            for (part in parts) {
                val trimmedPart = part.trim()

                if (currentDecl is KSClassDeclaration) {
                    // Find property in the class
                    var propertyType = currentDecl.getAllProperties()
                        .firstOrNull { it.simpleName.asString() == trimmedPart }
                        ?.type?.resolve()

                    if (propertyType == null) {
                        propertyType = currentDecl.getAllFunctions()
                            .filter { it.isPublic() }
                            .firstOrNull { it.simpleName.asString().contains("get$trimmedPart", ignoreCase = true) }
                            ?.returnType?.resolve()
                    }

                    if (propertyType != null) {
                        currentType = propertyType
                        currentDecl = currentType.declaration
                    } else {
                        logger.warn("[NetworkGen] Could not find property '$trimmedPart' in ${currentDecl.simpleName.asString()}")
                        return null
                    }
                } else {
                    return null
                }
            }

            return currentType
        } catch (e: Exception) {
            logger.warn("[NetworkGen] Error resolving delegated property type: ${e.message}")
            return null
        }
    }

    private fun findExistingSerializers(resolver: Resolver) {
        // Find ALL serializers with @UdeaSerializer annotation in current sources
        resolver.getSymbolsWithAnnotation(UdeaSerializer::class.qualifiedName!!)
            .forEach { symbol ->
                val symbolFqcn = (symbol as? KSClassDeclaration)?.qualifiedName?.asString()

                // Iterate through all @UdeaSerializer annotations (repeatable)
                symbol.annotations
                    .filter { it.shortName.asString() == "UdeaSerializer" }
                    .forEach { annotation ->
                        val forClass = (annotation.arguments.first().value.let {
                            (it as? KSType)?.declaration
                        })?.qualifiedName?.asString()

                        logger.warn("[NetworkGen] found existing serializer for $forClass")

                        if (forClass != null && symbolFqcn != null) {
                            existingSerializers[forClass] = symbolFqcn
                        }
                    }
            }
    }

    private fun generateSerializerRegistry() {
        if (indexGenerated) {
            logger.warn("[NetworkGen] Registry already generated this session, skipping")
            return
        }

        if (allSerializersForIndex.isEmpty()) {
            logger.warn("[NetworkGen] No serializers to write to registry")
            return
        }

        // Generate unique registry file name based on hash or timestamp
        val registryName = "UdeaSerializerRegistry_${System.currentTimeMillis()}"

        // Encode serializers as semicolon-separated pairs: "target1=serializer1;target2=serializer2"
        val encodedSerializers = allSerializersForIndex.entries
            .sortedBy { it.key }
            .joinToString(";") { (target, serializer) -> "$target=$serializer" }

        val registryContent = buildString {
            appendLine("package dev.wildware._serializer_")
            appendLine()
            appendLine("import dev.wildware.udea.network.UdeaSerializers")
            appendLine()
            appendLine("/**")
            appendLine(" * Auto-generated serializer registry.")
            appendLine(" * DO NOT EDIT - This file is generated by KSP")
            appendLine(" * Format: target1=serializer1;target2=serializer2;...")
            appendLine(" */")
            appendLine("@UdeaSerializers(\"$encodedSerializers\")")
            appendLine("internal object $registryName")
        }

        try {
            codeGenerator.createNewFile(
                dependencies = Dependencies(aggregating = true),
                packageName = "dev.wildware._serializer_",
                fileName = registryName
            ).write(registryContent.toByteArray())

            indexGenerated = true
            logger.warn("[NetworkGen] ✓ Generated registry with ${allSerializersForIndex.size} entries: $registryName")
        } catch (e: FileAlreadyExistsException) {
            logger.warn("[NetworkGen] Registry file already exists (from previous round)")
            indexGenerated = true
        } catch (e: Exception) {
            logger.error("[NetworkGen] Failed to generate registry: ${e.message}")
        }
    }
}


class NetworkGeneratorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return NetworkGenerator(environment.codeGenerator, environment.logger, environment.options)
    }
}
