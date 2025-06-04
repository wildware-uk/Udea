package dev.wildware.udea.editors.builder

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import dev.wildware.udea.*
import dev.wildware.udea.editors.EditorType
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

/**
 * Creates a UBuilder from any object.
 * */
fun Any.toUBuilder(project: Project): UBuilder {
    val type = findClassByName(project, this::class.qualifiedName!!)!!
        .toType(project)
    return type.toUBuilder(project, this)
}

fun PsiType.toUBuilder(project: Project, instance: Any? = null): UBuilder {
    return when (this) {
        is PsiPrimitiveType -> {
            UValueBuilder(this.toEditorType(), instance)
        }

        is PsiClassType -> {
            val psiClass = this.resolve() ?: error("Cannot resolve class: ${this.canonicalText}")

            if (psiClass.isEnum) {
                val type = this.toEditorType<USelectBuilder>()
                return USelectBuilder(
                    type,
                    psiClass.fields
                        .filter { it.hasModifierProperty(PsiModifier.STATIC) }
                        .map { type.type.java.fields.first { e -> e.name == it.name }.get(null)!! },
                    (instance as? Enum<*>)
                )
            }

            when (val qualifiedName = psiClass.qualifiedName) {
                "java.lang.String" -> UValueBuilder(EditorType(String::class), instance)
                "java.util.List" -> UListBuilder(
                    EditorType(List::class),
                    (instance as? List<Any>)?.map {
                        it.toUBuilder(project)
                    }?.toMutableList() ?: mutableListOf()
                )

                else -> {
                    if (psiClass.isConcrete()) {
                        buildConcreteBuilder(psiClass, project, instance)
                    } else {
                        val subclasses = findClassesOfType(project, qualifiedName!!)
                            .associate {
                                val selectedInstance =
                                    if (instance != null && it.qualifiedName == instance::class.qualifiedName) {
                                        instance
                                    } else null
                                it.qualifiedName!! to it.toType(project)
                                    .toUBuilder(project, selectedInstance) as UObjectBuilder
                            }

                        UAbstractClassBuilder(
                            this.toEditorType(),
                            subclasses,
                            concreteClass = instance?.let { it::class.qualifiedName })
                    }
                }
            }
        }

        else -> error("Unsupported type: $this")
    }
}

private fun PsiClassType.buildConcreteBuilder(
    psiClass: PsiClass,
    project: Project,
    instance: Any? = null
): UObjectBuilder {
    val substitutor = PsiSubstitutor.EMPTY.putAll(this.resolveGenerics().substitutor)

    val constructor = psiClass.constructors.maxByOrNull { it.parameters.size }
        ?: error("No constructors found for class: ${psiClass.qualifiedName}")

    val parameters = constructor.parameters.associate { parameter ->
        val resolvedType = substitutor.substitute(parameter.type as? PsiType)

        parameter.name!! to resolvedType.toUBuilder(
            project,
            instance?.let {
                @Suppress("UNCHECKED_CAST")
                (it::class.memberProperties.find { prop -> prop.name == parameter.name }
                    ?.let { prop -> (prop as KProperty1<Any, *>).get(it) })
            }
        )
    }.toMutableMap()

    return UObjectBuilder(this.toEditorType(), parameters, instance)
}

/**
 * Base interface for all builder components in the Universal Data Editor API.
 * Provides a sealed hierarchy for type-safe builder implementations.
 */
sealed interface UBuilder {
    @get:JsonProperty("@class")
    val type: EditorType<Any>

    fun build(): Any?
}

/**
 * Builder for creating object structures with named children.
 *
 * @property children A map of child builders where keys represent field names
 */
data class UObjectBuilder(
    override val type: EditorType<Any>,
    val children: MutableMap<String, UBuilder> = mutableMapOf(),
    var value: Any? = null,
) : UBuilder {
    @JsonValue
    fun serialize() = Json.objectMapper.convertValue(value, Map::class.java) ?: (mapOf("@class" to type.type.java.name) + children)

    override fun build(): Any? {
        val constructor = type.type.constructors.maxByOrNull { it.parameters.size }!!

        return try {
            val params = children
                .mapKeys { constructor.parameters.first { param -> param.name == it.key } }
                .mapValues { it.value.build() }
            constructor.callBy(params)
        } catch (e: Exception) {
            println("Failed to build object by name: $e")

            try {
                val params = children.map { it.value.build() }
                constructor.call(*params.toTypedArray())
            } catch (e: Exception) {
                println("Failed to build object by index: $e")
                null
            }
        }
    }
}

/**
 * Builder for creating list structures containing object builders.
 *
 * @property children An ordered list of object builders
 */
data class UListBuilder(
    override val type: EditorType<Any>,

    @JsonValue
    val children: MutableList<UBuilder> = mutableListOf()

) : UBuilder {
    override fun build(): Any? {
        return children.map { it.build() }
    }
}

/**
 * Builder for creating primitive value containers.
 *
 * @property value The actual value being built, can be null
 */
data class UValueBuilder(
    override val type: EditorType<Any>,

    @JsonValue
    var value: Any? = null
) : UBuilder {
    override fun build() = value
}

/**
 * Builder for creating enum value containers.
 *
 * @property type The type identifier for this builder
 * @property values Available values as a string
 * @property value The selected enum value
 */
data class USelectBuilder(
    override val type: EditorType<Any>,
    val values: List<Any>,

    @JsonValue
    var value: Any? = null
) : UBuilder {
    override fun build() = value
}

/**
 * Builder for creating abstract class implementations.
 *
 * @property type The type identifier for this builder
 * @property concreteClasses List of available concrete class implementations
 * @property concreteClass The selected concrete class implementation
 */
data class UAbstractClassBuilder(
    override val type: EditorType<Any>,
    val concreteClasses: Map<String, UObjectBuilder>,
    var concreteClass: String? = null,
) : UBuilder {
    @get:JsonValue
    val value: UObjectBuilder?
        get() = concreteClasses[concreteClass]

    override fun build() = value?.build()
}
