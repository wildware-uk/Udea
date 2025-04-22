package dev.wildware.udea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import dev.wildware.udea.KObjectBuilder.KObjectParameter

/**
 * Converts a camelCase string to a sentence format with proper capitalization.
 *
 * @param input The camelCase string to convert
 * @return The converted string in sentence format
 */
fun camelCaseToSentence(input: String): String {
    return input.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase()
        .replaceFirstChar { it.titlecase() }
}

/**
 * Converts a camelCase string to title case format where each word is capitalized.
 *
 * @param input The camelCase string to convert
 * @return The converted string in title case format
 */
fun camelCaseToTitle(input: String): String {
    return input.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .split(" ")
        .joinToString(" ") { it.replaceFirstChar { char -> char.titlecase() } }
}

/**
 * Finds all classes that inherit from a specified base class in the project scope.
 *
 * @param project The IntelliJ project to search in
 * @param baseClassName The fully qualified name of the base class
 * @return List of PsiClass objects representing the found classes
 */
fun findClassesOfType(project: Project, baseClassName: String): List<PsiClass> {
    return runReadAction {
        val psiClass = JavaPsiFacade.getInstance(project)
            .findClass(baseClassName, GlobalSearchScope.allScope(project))
            ?: error("Cannot find class: $baseClassName")
        val classes = ClassInheritorsSearch.search(psiClass, GlobalSearchScope.allScope(project), true)
        classes.toList()
    }
}

/**
 * Finds a class by its fully qualified name in the project scope.
 *
 * @param project The IntelliJ project to search in
 * @param qualifiedName The fully qualified name of the class to find
 * @return The found PsiClass or null if not found
 */
fun findClassByName(project: Project, qualifiedName: String): PsiClass? {
    return runReadAction {
        JavaPsiFacade.getInstance(project).findClass(qualifiedName, GlobalSearchScope.allScope(project))
    }
}

/**
 * Converts a PsiClass to a PsiClassType with optional type arguments.
 *
 * @param project The IntelliJ project
 * @param typeArgs Optional type arguments for the class type
 * @return The created PsiClassType
 */
fun PsiClass.toType(project: Project, vararg typeArgs: PsiType): PsiClassType {
    val psiElementFactory = PsiElementFactory.getInstance(project)
    return psiElementFactory.createType(this, *typeArgs)
}

/**
 * Executes the given block on a pooled thread using ApplicationManager.
 *
 * @param block The code block to execute on the pooled thread
 */
inline fun pooled(crossinline block: ()->Unit) {
    ApplicationManager.getApplication().executeOnPooledThread {
        block()
    }
}

/**
 * Creates a KObjectBuilder instance from a PSI class.
 *
 * @param psiClassType The PSI class to extract constructor parameters from
 * @return A new KObjectBuilder instance with parameters from the PSI class constructor
 */
fun fromPsiType(project: Project, psiType: PsiType): KBuilder {
    return when (psiType) {
        is PsiPrimitiveType -> {
            val boxedType = psiType.getBoxedType(
                PsiManager.getInstance(project),
                GlobalSearchScope.allScope(project)
            ) ?: error("Cannot resolve boxed type for: $psiType")

            KValueBuilder(boxedType.resolve()?.qualifiedName!!)
        }

        is PsiClassType -> {
            val psiClass = psiType.resolve() ?: error("Cannot resolve class: ${psiType.canonicalText}")
            if (psiClass.qualifiedName == "java.lang.String") {
                KValueBuilder(psiClass.qualifiedName!!)
            } else if (psiClass.qualifiedName == "java.util.List") {
                KListBuilder(psiClass.qualifiedName!!)
            } else {
                val substitutor = PsiSubstitutor.EMPTY.putAll(psiType.resolveGenerics().substitutor)

                val constructor = psiClass.constructors.firstOrNull()
                    ?: error("No constructors found for class: ${psiClass.qualifiedName}")

                val parameters = constructor.parameters.map { parameter ->
                    val resolvedType = substitutor.substitute(parameter.type as? PsiType)
                    val value = fromPsiType(project, resolvedType ?: error("Cannot resolve type for parameter: ${parameter.name}"))
                    KObjectParameter(parameter.name!!, value)
                }

                KObjectBuilder(psiClass.qualifiedName!!, parameters)
            }
        }

        else -> error("Unsupported type: $psiType")
    }
}
