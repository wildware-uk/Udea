package dev.wildware.udea.codegen.replicator

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName

/**
 * The handful of `KSP` -> `KotlinPoet` conversions the processor needs, in one place.
 *
 * They are extensions rather than members of anything because they are adaptations between two
 * libraries neither of which is ours: `KSType` and `ClassName` describe the same idea in two
 * vocabularies, and the translation belongs at the seam rather than inside whichever class
 * happened to need it first.
 */

/** A type as a developer wrote it, for a message: `Vector2`, `String?`, `List<Int>`. */
internal fun KSType.describe(): String {
    val base = declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
    return if (isMarkedNullable) "$base?" else base
}

/** The type's simple name, for a did-you-mean comparison: `Vector2`, not the FQN. */
internal fun KSType.simpleName(): String = declaration.simpleName.asString()

internal fun KSClassDeclaration.toClassName(): ClassName {
    val packageName = packageName.asString()
    val simpleNames = qualifiedName?.asString()
        ?.removePrefix(if (packageName.isEmpty()) "" else "$packageName.")
        ?.split('.')
        ?: listOf(simpleName.asString())
    return ClassName(packageName, simpleNames)
}

internal fun KSType.toClassName(): ClassName =
    (declaration as? KSClassDeclaration)?.toClassName()
        ?: ClassName(declaration.packageName.asString(), declaration.simpleName.asString())

internal fun KSPropertyDeclaration.hasAnnotation(qualifiedName: String): Boolean =
    annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName }
