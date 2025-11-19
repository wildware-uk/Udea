package dev.wildware.udea

import com.intellij.lang.jvm.JvmAnnotatedElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtParameter
import kotlin.reflect.full.memberProperties

/**
 * Converts a camelCase string to a sentence format with proper capitalization.
 *
 * @param input The camelCase string to convert
 * @return The converted string in sentence format
 */
fun String.camelCaseToSentence(): String {
    return replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase()
        .replaceFirstChar { it.titlecase() }
}

/**
 * Converts a camelCase string to title case format where each word is capitalized.
 *
 * @param input The camelCase string to convert
 * @return The converted string in title case format
 */
fun String.camelCaseToTitle(): String {
    return replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .split(" ")
        .joinToString(" ") { it.replaceFirstChar { char -> char.titlecase() } }
}

/**
 * Converts a camelCase string to a title format with proper capitalization.
 *
 * @param input The camelCase string to convert
 * @return The converted string in title format
 */
fun String.qualifiedNameToTitle(): String {
    return this.substringAfterLast(".")
        .camelCaseToTitle()
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

fun createPsiClassTypeWithGenerics(project: Project, mainClass: String, vararg genericClasses: String): PsiClassType {
    val psiFacade = JavaPsiFacade.getInstance(project)
    val elementFactory = psiFacade.elementFactory

    // Resolve the main class
    val mainPsiClass = psiFacade.findClass(mainClass, GlobalSearchScope.allScope(project))
        ?: error("Class '$mainClass' not found")

    // Resolve the generic type arguments
    val genericPsiTypes = genericClasses.map { className ->
        val psiClass = psiFacade.findClass(className, GlobalSearchScope.allScope(project))
            ?: error("Class '$className' not found")
        elementFactory.createType(psiClass)
    }.toTypedArray()

    // Create the PsiClassType with generics
    return elementFactory.createType(mainPsiClass, *genericPsiTypes)
}

fun objectToPsiClassType(project: Project, obj: Any): PsiClassType {
    val psiFacade = JavaPsiFacade.getInstance(project)
    val elementFactory = psiFacade.elementFactory

    // Get the runtime class of the object
    val objClass = obj::class

    // Resolve the main class in the PSI model
    val mainPsiClass = psiFacade.findClass(objClass.qualifiedName!!, GlobalSearchScope.allScope(project))
        ?: error("Class '${objClass.qualifiedName}' not found in the project")

    val genericTypes = objClass.typeParameters.map { typeParameter ->
        objClass.memberProperties.find { it.returnType.classifier == typeParameter }
            ?.call(obj)!!::class.qualifiedName
    }.map { elementFactory.createType(findClassByName(project, it!!)!!) }
        .toTypedArray()

    // Create and return the PsiClassType
    return elementFactory.createType(mainPsiClass, *genericTypes)
}

/**
 * Executes the given block on a pooled thread using ApplicationManager.
 *
 * @param block The code block to execute on the pooled thread
 */
inline fun pooled(crossinline block: () -> Unit) =
    ApplicationManager.getApplication().executeOnPooledThread {
        block()
    }

/**
 * Executes the given block on a pooled thread and returns the result.
 */
inline fun <T> pooledResult(crossinline block: () -> T): T {
    var result: T? = null
    ApplicationManager.getApplication().executeOnPooledThread {
        result = block()
    }.get()
    return result!!
}


/**
 * Checks if the class has a specific annotation.
 *
 * @param T The annotation class to check for
 * @return true if the class has the specified annotation, false otherwise
 */
inline fun <reified T> PsiClass.hasAnnotation(): Boolean {
    return this.annotations.any { it.qualifiedName == T::class.qualifiedName }
}

/**
 * Checks if the class has a specific annotation.
 *
 * @param T The annotation class to check for
 * @return true if the class has the specified annotation, false otherwise
 */
inline fun <reified T> JvmAnnotatedElement.hasAnnotation(): Boolean {
    return this.annotations.any { it.qualifiedName == T::class.qualifiedName }
}

/**
 * @return true if the class is concrete (not abstract, sealed, or an interface)
 **/
fun PsiClass.isConcrete(): Boolean =
    !(isInterface || hasModifierProperty(PsiModifier.ABSTRACT) || hasModifierProperty(PsiModifier.SEALED))

inline fun <reified T> KtClass.hasAnnotation(): Boolean {
    return this.annotations.any { it.name == T::class.simpleName }
}

inline fun <reified T> KtParameter.hasAnnotation(): Boolean {
    return this.annotations.any { it.name == T::class.qualifiedName }
}

/**
 * Converts a PsiClass to its JVM qualified name, handling nested classes with '$' notation.
 *
 * Example: "dev.wildware.udea.assets.Binding$BindingInput"
 *
 * @param psiClass The PsiClass to convert
 * @return The JVM qualified name
 */
fun PsiClass.toJvmQualifiedName(): String = runReadAction {
    val containingClass = this.containingClass
    if (containingClass != null) {
        "${containingClass.toJvmQualifiedName()}$${this.name}"
    } else {
        this.qualifiedName ?: ""
    }
}

fun getJvmQualifiedName(psiClass: PsiClass): String {
    val outerClass = psiClass.containingClass
    return if (outerClass != null) {
        "${getJvmQualifiedName(outerClass)}$${psiClass.name}"
    } else {
        psiClass.qualifiedName ?: error("Cannot determine qualified name for class: ${psiClass.name}")
    }
}
