package dev.wildware.udea

import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Marks a class or property as a reference, meaning it will be treated as a value, instead of an object.
 *
 * If used on a class, all fields of that class will be treated as references.
 * */
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class KReference

/**
 * Marks a class or property as provided, meaning it will be injected by the framework.
 *
 * If used on a class, all fields of that class will be treated as provided.
 * */
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class KProvided

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
    val fields: List<KObjectParameter>
) : KBuilder {

    override fun build(classLoader: ClassLoader): Any? {
        val kClass = classLoader.loadClass(type).kotlin
        val constructor = kClass.constructors.first()
        val parameters = fields.associate { field ->
            constructor.parameters.find { it.name == field.name }!! to field.value.build(classLoader)
        }
        return constructor.callBy(parameters)
    }

    /**
     * Represents a parameter for object construction.
     *
     * @property name The name of the parameter
     * @property type The PSI type of the parameter
     * @property value The value to be assigned to this parameter, defaults to null
     */
    data class KObjectParameter(
        val name: String,
        val value: KBuilder,
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
    val elements: MutableList<KBuilder> = mutableListOf()
) : KBuilder {
    override fun build(classLoader: ClassLoader): Any? {
        return elements.map { it.build(classLoader) }
    }
}

/**
 * A builder class for constructing simple value objects.
 *
 * @property type The PSI class that this value builder represents
 */
data class KValueBuilder(
    override val type: String
) : KBuilder {

    /**
     * The value to be assigned to this builder, defaults to null
     */
    var value: Any? = null

    override fun build(classLoader: ClassLoader): Any? {
        return value
    }
}
