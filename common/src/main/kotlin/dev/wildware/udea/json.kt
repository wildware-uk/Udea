package dev.wildware.udea

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.InputStream

/**
 * Utility object for JSON serialization and deserialization using Jackson ObjectMapper.
 * Provides convenient methods to convert between JSON strings and Kotlin objects.
 */
object Json {
    @PublishedApi
    internal val objectMapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        configure(SerializationFeature.INDENT_OUTPUT, true)
        setDefaultPrettyPrinter(DefaultPrettyPrinter().apply {
            indentArraysWith(DefaultIndenter("  ", "\n"))
            indentObjectsWith(DefaultIndenter("  ", "\n"))
        })
    }


    /**
     * Sets the class loader for the ObjectMapper's type factory.
     *
     * @param classLoader The ClassLoader to use for type resolution, defaults to current thread's context class loader
     * @return The [Json] object instance for method chaining
     */
    fun withClassLoader(classLoader: ClassLoader = Thread.currentThread().contextClassLoader): Json {
        objectMapper.typeFactory = objectMapper.typeFactory.withClassLoader(classLoader)
        return this
    }

    /**
     * Converts a JSON string to an object of the specified type.
     *
     * @param json The JSON string to deserialize
     * @param clazz The target class type to convert the JSON into
     * @return An instance of type T containing the deserialized data
     */
    inline fun <reified T> fromJson(json: String): T {
        return objectMapper.readValue(json, object : TypeReference<T>() {})
            .also { withClassLoader() }
    }

    /**
     * Converts JSON data from an InputStream to an object of the specified type.
     *
     * @param inputStream The InputStream containing JSON data to deserialize
     * @return An instance of type T containing the deserialized data
     */
    inline fun <reified T> fromJson(inputStream: InputStream): T {
        return objectMapper.readValue(inputStream, object : TypeReference<T>() {})
            .also { withClassLoader() }
    }

    /**
     * Converts an object to its JSON string representation.
     *
     * @param value The object to serialize to JSON
     * @return The JSON string representation of the object
     */
    fun toJson(value: Any): String {
        return objectMapper.writeValueAsString(value)
            .also { withClassLoader() }
    }
}