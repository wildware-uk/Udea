package dev.wildware.udea.codegen.level

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.isLocal
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import dev.wildware.udea.codegen.LevelNames
import dev.wildware.udea.codegen.replicator.toClassName

/**
 * Finds the components a level file can save: every concrete Fleks component class in the module
 * being processed that carries kotlinx's `@Serializable` (issue #191).
 *
 * `@Serializable` is the whole opt-in. A component without it is simply not listed, and saving a
 * world that holds one fails at run time naming the class - which is the loud, located failure
 * the issue asks for, and not something this pass could decide on the world's behalf.
 *
 * A `@Serializable` component this pass *cannot* list is a build error rather than an omission:
 * a private or local class cannot be named from the generated index, and an abstract or generic
 * one has no single serializer to register. Leaving any of them out silently would produce a
 * module whose levels refuse to save for a reason nothing at build time mentioned.
 */
internal class LevelComponentScanner(private val logger: KSPLogger) {

    /** One listable component, and the file it came from - the generated index depends on it. */
    class Found(val className: ClassName, val containingFile: KSFile)

    /** The saveable components in the round, in ascending qualified-name order. */
    fun find(resolver: Resolver): List<Found> =
        resolver.getSymbolsWithAnnotation(LevelNames.SERIALIZABLE)
            .filterIsInstance<KSClassDeclaration>()
            .filter(::isFleksComponent)
            .sortedBy { it.qualifiedName?.asString() ?: it.simpleName.asString() }
            .mapNotNull(::listable)
            .toList()

    private fun isFleksComponent(declaration: KSClassDeclaration): Boolean =
        declaration.classKind == ClassKind.CLASS &&
            declaration.getAllSuperTypes().any {
                it.declaration.qualifiedName?.asString() == LevelNames.FLEKS_COMPONENT
            }

    private fun listable(declaration: KSClassDeclaration): Found? {
        val file = declaration.containingFile
        val reason = when {
            declaration.isLocal() -> "it is a local class"
            declaration.isPrivate() -> "it is private"
            Modifier.ABSTRACT in declaration.modifiers -> "it is abstract"
            declaration.typeParameters.isNotEmpty() -> "it is generic"
            file == null -> "it has no source file in this module"
            else -> return Found(declaration.toClassName(), file)
        }
        logger.error(
            "${declaration.qualifiedName?.asString()} is a @Serializable Fleks component, but a " +
                "level file cannot list it because $reason. Make it a concrete, non-private " +
                "class, or remove @Serializable if it is never saved.",
            declaration,
        )
        return null
    }
}
