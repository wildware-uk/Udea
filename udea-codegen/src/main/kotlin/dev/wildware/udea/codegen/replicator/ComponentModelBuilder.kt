package dev.wildware.udea.codegen.replicator

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import dev.wildware.udea.codegen.AnnotationNames
import dev.wildware.udea.diagnostics.UdeaRules

/**
 * Turns a `@Replicated` class declaration into a [ReplicatedComponent], or reports why it
 * cannot and returns `null`.
 *
 * **The failure policy is the reason this class exists.** The generator this replaces wrapped
 * each symbol in `catch (e: Exception)` and turned a component it could not handle into a log
 * line plus a silently missing serializer — a component that then failed to replicate at
 * runtime with no build-time trace. Here:
 *
 * - there is no `catch`;
 * - every diagnostic is [KSPLogger.error] **at the offending symbol**, which fails the build;
 * - a component with any error emits **no file at all**, rather than a partial one.
 *
 * Errors that a K2 FIR checker will also raise (spec 3.2) report the same stable rule id from
 * `udea-diagnostics`, so the two producers cannot drift apart (spec 5, "Diagnostics").
 */
internal class ComponentModelBuilder(private val logger: KSPLogger) {

    fun build(declaration: KSClassDeclaration): ReplicatedComponent? {
        var failed = false

        val qualifiedName = declaration.qualifiedName?.asString()
        if (qualifiedName == null) {
            logger.error("@Replicated is only supported on a named, top-level or nested class", declaration)
            return null
        }
        if (declaration.classKind != ClassKind.CLASS) {
            logger.error(
                "@Replicated is only supported on a class, but ${declaration.simpleName.asString()} " +
                    "is ${declaration.classKind.name.lowercase().replace('_', ' ')}",
                declaration,
            )
            failed = true
        }

        val annotated = declaration.getDeclaredProperties()
            .filter { it.hasAnnotation(AnnotationNames.NET) || it.hasAnnotation(AnnotationNames.SIM) }
            .toList()

        if (annotated.size > UdeaRules.MAX_COMPONENT_FIELDS) {
            // Deliberately not a truncation and not a widening: one FieldMask addresses 64
            // fields, and the fix a developer can actually take is to split the component.
            logger.error(
                "${UdeaRules.COMPONENT_FIELD_LIMIT.id}: $qualifiedName declares ${annotated.size} " +
                    "@Net/@Sim fields, but a field mask addresses at most " +
                    "${UdeaRules.MAX_COMPONENT_FIELDS}. SPLIT the component into two or more " +
                    "components of at most ${UdeaRules.MAX_COMPONENT_FIELDS} fields each; " +
                    "there is no way to widen the mask for one component.",
                declaration,
            )
            failed = true
        }

        val ordered = FieldOrder.assign(annotated) { it.simpleName.asString() }
        val constants = FieldOrder.constantNames(ordered.map { it.simpleName.asString() })

        val fields = ArrayList<ReplicatedField>(ordered.size)
        ordered.forEachIndexed { index, property ->
            val field = describe(declaration, property, index, constants[index])
            if (field == null) failed = true else fields += field
        }

        if (failed) return null
        return ReplicatedComponent(
            className = declaration.toClassName(),
            qualifiedName = qualifiedName,
            fields = fields,
        )
    }

    private fun describe(
        owner: KSClassDeclaration,
        property: KSPropertyDeclaration,
        index: Int,
        constant: String,
    ): ReplicatedField? {
        val ownerName = owner.qualifiedName?.asString() ?: owner.simpleName.asString()
        val propertyName = property.simpleName.asString()
        val net = property.hasAnnotation(AnnotationNames.NET)
        var failed = false

        if (!property.isMutable) {
            // @Net on a val is always a mistake, never a no-op: replication is capture-and-diff,
            // and a val cannot change, so the field would occupy a bit that can never be set.
            // @Sim on a val is the same defect but has no registered rule id yet: udea-diagnostics
            // only registers UDEA0001 for @Net, and this module must not add rules to it.
            val prefix = if (net) "${UdeaRules.NET_ON_VAL.id}: @Net" else "@Sim"
            logger.error(
                "$prefix annotates the val $ownerName.$propertyName. A val can never change, so " +
                    "it can never replicate, and Replicator.apply could not restore it. Make it " +
                    "a var or drop the annotation.",
                property,
            )
            failed = true
        }

        val type = property.type.resolve()

        if (property.hasAnnotation(AnnotationNames.Q) && !type.isFloat()) {
            logger.error(
                "${UdeaRules.QUANTIZED_NON_FLOAT.id}: @Q annotates $ownerName.$propertyName, " +
                    "which is ${type.describe()}, not Float. Quantization is only defined for " +
                    "floats.",
                property,
            )
            failed = true
        }

        val storage = storageOf(type)
        if (storage == null) {
            logger.error(
                "$ownerName.$propertyName is ${type.describe()}, which udea-codegen cannot " +
                    "replicate yet. Supported field types are Int, Long, Float, Boolean and " +
                    "enums; anything else needs a field codec (@NetCodecFor), which is not " +
                    "implemented.",
                property,
            )
            failed = true
        }

        if (failed || storage == null) return null
        val enumDeclaration = type.declaration as? KSClassDeclaration
        return ReplicatedField(
            name = propertyName,
            constant = constant,
            index = index,
            net = net,
            storage = storage,
            declaredType = type.toClassName(),
            enumEntries = if (enumDeclaration?.classKind == ClassKind.ENUM_CLASS) {
                enumDeclaration.toClassName()
            } else {
                null
            },
        )
    }

    private fun storageOf(type: KSType): FieldStorage? {
        if (type.isMarkedNullable) return null
        val declaration = type.declaration
        return when (declaration.qualifiedName?.asString()) {
            "kotlin.Boolean" -> FieldStorage.BOOLEAN
            "kotlin.Int" -> FieldStorage.INT
            "kotlin.Long" -> FieldStorage.LONG
            "kotlin.Float" -> FieldStorage.FLOAT
            // An enum is stored and sent as its ordinal, which is why it needs no codec.
            else -> if ((declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS) {
                FieldStorage.INT
            } else {
                null
            }
        }
    }
}

private fun KSPropertyDeclaration.hasAnnotation(qualifiedName: String): Boolean =
    annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName }

private fun KSType.isFloat(): Boolean = declaration.qualifiedName?.asString() == "kotlin.Float"

/** A type as a developer wrote it, for a message: `Vector2`, `String?`, `List<Int>`. */
private fun KSType.describe(): String {
    val base = declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
    return if (isMarkedNullable) "$base?" else base
}

private fun KSClassDeclaration.toClassName(): ClassName {
    val packageName = packageName.asString()
    val simpleNames = qualifiedName?.asString()
        ?.removePrefix(if (packageName.isEmpty()) "" else "$packageName.")
        ?.split('.')
        ?: listOf(simpleName.asString())
    return ClassName(packageName, simpleNames)
}

private fun KSType.toClassName(): ClassName =
    (declaration as? KSClassDeclaration)?.toClassName()
        ?: ClassName(declaration.packageName.asString(), declaration.simpleName.asString())
