package dev.wildware.udea.network.serde

import kotlin.reflect.KClass

/**
 * Annotate an object with this annotation to make it a serializer for the given class.
 * */
@Target(AnnotationTarget.CLASS)
annotation class UdeaSerializer(
    val forClass: KClass<*>
)
