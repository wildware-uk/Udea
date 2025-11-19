package dev.wildware.udea

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope
import dev.wildware.KProvided
import dev.wildware.KReference
import dev.wildware.udea.KObjectBuilder.KObjectParameter
import dev.wildware.udea.assets.Asset
import org.jetbrains.kotlin.idea.refactoring.memberInfo.qualifiedClassNameForRendering
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

class KBuilderBuilder private constructor(
    val kContext: KContext
) {

    fun build(): KBuilder {
        return fromPsiType(
            kContext.project,
            kContext.psiClass,
            kContext.instance
        )
    }

    private fun fromPsiType(project: Project, psiType: PsiType, instance: Any? = null): KBuilder {
        return when (psiType) {
            is PsiPrimitiveType -> {
                val boxedType = psiType.getBoxedType(
                    PsiManager.getInstance(project),
                    GlobalSearchScope.allScope(project)
                ) ?: error("Cannot resolve boxed type for: $psiType")

                KValueBuilder(boxedType.resolve()?.qualifiedName!!, instance)
            }

            is PsiClassType -> {
                val psiClass = psiType.resolve() ?: error("Cannot resolve class: ${psiType.canonicalText}")
                val isProvided = psiClass.hasAnnotation<KProvided>()

                if (isProvided) {
                    KProvidedValue(psiClass.qualifiedName!!, instance)
                } else when (val qualifiedName = psiClass.qualifiedName) {
                    "java.lang.String" -> KValueBuilder(qualifiedName, instance)
                    "java.util.List" -> KListBuilder(qualifiedName, value = instance)
                    else -> {
                        if (psiClass.isConcrete()) {
                            buildConcreteBuilder(psiType, psiClass, project, instance)
                        } else {
                            KAbstractObjectBuilder(qualifiedName!!)
                        }
                    }
                }
            }

            else -> error("Unsupported type: $psiType")
        }
    }

    private fun buildConcreteBuilder(
        psiType: PsiClassType,
        psiClass: PsiClass,
        project: Project,
        instance: Any? = null
    ): KObjectBuilder {
        val substitutor = PsiSubstitutor.EMPTY.putAll(psiType.resolveGenerics().substitutor)

        val constructor = psiClass.constructors.firstOrNull()
            ?: error("No constructors found for class: ${psiClass.qualifiedName}")

        val parameters = constructor.parameters.map { parameter ->
            val resolvedType = substitutor.substitute(parameter.type as? PsiType)

            KObjectParameter(
                parameter.name!!,
                if (resolvedType is PsiClassType && resolvedType.resolve()!!.hasAnnotation<KReference>()) {
                    KAssetBuilder(resolvedType.resolve()!!.qualifiedName!!)
                } else if (psiClass.findFieldByName(parameter.name!!, false)!!.hasAnnotation<KProvided>()) {
                    KProvidedValue(resolvedType.canonicalText, instance?.let {
                        @Suppress("UNCHECKED_CAST")
                        (it::class.memberProperties.find { prop -> prop.name == parameter.name }
                            ?.let { prop -> (prop as KProperty1<Any, *>).get(it) })
                    })
                } else {
                    val value = fromPsiType(
                        project,
                        resolvedType ?: error("Cannot resolve type for parameter: ${parameter.name}"),
                        instance?.let {
                            @Suppress("UNCHECKED_CAST")
                            (it::class.memberProperties.find { prop -> prop.name == parameter.name }
                                ?.let { prop -> (prop as KProperty1<Any, *>).get(it) })
                        }
                    )

                    value
                },
            )
        }

        return KObjectBuilder(getJvmQualifiedName(psiClass), parameters)
    }

    companion object {
        private fun getJvmQualifiedName(psiClass: PsiClass): String {
            val outerClass = psiClass.containingClass
            return if (outerClass != null) {
                "${getJvmQualifiedName(outerClass)}\$${psiClass.name}"
            } else {
                psiClass.qualifiedName ?: error("Cannot determine qualified name for class: ${psiClass.name}")
            }
        }

        /**
         * Creates a KObjectBuilder instance from an object instance.
         *
         * @param project The IntelliJ project context
         * @param instance The object instance to extract constructor parameters from
         * @return A new KObjectBuilder instance with parameters from the object instance
         */
        fun fromInstance(project: Project, instance: Any) =
            KBuilderBuilder(KContext(instance,project, objectToPsiClassType(project, instance)))

        /**
         * Creates a KObjectBuilder instance from a PsiClassType.
         *
         * @param project The IntelliJ project context
         * @param psiClassType The PSI class type to extract constructor parameters from
         * @param instance An optional object instance to extract constructor parameters from
         * @return A new KObjectBuilder instance with parameters from the PSI class type
         */
        fun fromPsiType(
            project: Project,
            psiClassType: PsiClassType,
            instance: Any? = null
        ): KBuilderBuilder {
            return KBuilderBuilder(KContext(instance, project, psiClassType))
        }
    }

    data class KContext(
        val instance: Any?,
        val project: Project,
        val psiClass: PsiClassType,
    )
}

/**
 * A sealed interface representing a builder for Kotlin objects.
 * It defines the common structure for different types of builders.
 *
 * @property type The PSI class that this builder will construct
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.DEDUCTION
)
sealed interface KBuilder {
    val type: String

    /**
     * Builds an instance of the specified class using the stored parameters.
     *
     * @param kClass The Kotlin class to instantiate
     * @return A new instance of the specified class
     * @throws NoSuchElementException if a required parameter is not found
     */
    fun build(classLoader: ClassLoader = Thread.currentThread().contextClassLoader): Any?
}

/**
 * A builder class that constructs objects dynamically using reflection.
 * It holds a list of parameters that will be used to create an instance of a specified class.
 *
 * @property fields List of fields required to construct the object
 */
data class KObjectBuilder(
    override val type: String,
    val fields: List<KObjectParameter>,
) : KBuilder {

    override fun build(classLoader: ClassLoader): Any? {
        val kClass = classLoader.loadClass(type).kotlin
        val constructor = kClass.constructors.first()
        val parameters = fields.associate { field ->
            constructor.parameters.find { it.name == field.name }!! to field.builder.build(classLoader)
        }
        return constructor.callBy(parameters)
    }

    fun getObject(name: String): KObjectBuilder {
        return (fields.find { it.name == name }!!.builder as KObjectBuilder)
    }

    fun <T> getValue(name: String): T {
        return (fields.find { it.name == name }!!.builder as KValueBuilder).value as T
    }

    /**
     * Represents a parameter for object construction.
     *
     * @property name The name of the parameter
     * @property type The PSI type of the parameter
     * @property builder The value to be assigned to this parameter, defaults to null
     */
    data class KObjectParameter(
        val name: String,
        val builder: KBuilder
    )
}

/**
 * A builder class for constructing lists of objects.
 * It maintains a mutable list of builders that will be used to construct the elements of the list.
 *
 * @property type The PSI class that this list builder represents
 * @property elements A mutable list of builders that will construct the elements of the list
 */
data class KListBuilder(
    override val type: String,
    val elements: MutableList<KBuilder> = mutableListOf(),
    val value: Any? = null
) : KBuilder {
    override fun build(classLoader: ClassLoader): Any? {
        return elements.map { it.build(classLoader) }
    }
}

/**
 * A builder class for constructing abstract/sealed/interface objects.
 * It holds a list of fields and a selected type for the sealed class.
 *
 * @property type The type of the sealed class
 */
data class KAbstractObjectBuilder(
    override val type: String,
) : KBuilder {

    var selectedType: String? = null
    var concreteBuilder: KBuilder? = null

    override fun build(classLoader: ClassLoader): Any? {
        return concreteBuilder?.build(classLoader)
    }
}

/**
 * A builder class for constructing asset objects.
 * It holds a reference to an asset that will be used to create the object.
 *
 * @property type The PSI class that this asset builder represents
 */
data class KAssetBuilder(
    override val type: String,
    var reference: Asset? = null,
) : KBuilder {
    override fun build(classLoader: ClassLoader): Any? {
        return reference
    }
}

/**
 * A builder class for constructing simple value objects.
 *
 * @property type The PSI class that this value builder represents
 */
data class KValueBuilder(
    override val type: String,

    /**
     * The value to be assigned to this builder, defaults to null
     */
    var value: Any? = null
) : KBuilder {
    override fun build(classLoader: ClassLoader): Any? {
        return value
    }
}

/**
 * A builder class for constructing provided values.
 * It holds a type that will be used to resolve the provided value.
 *
 * @property type The PSI class that this provided value builder represents
 */
data class KProvidedValue(
    override val type: String,
    val value: Any? = null
) : KBuilder {
    override fun build(classLoader: ClassLoader): Any? {
        return value
    }
}
