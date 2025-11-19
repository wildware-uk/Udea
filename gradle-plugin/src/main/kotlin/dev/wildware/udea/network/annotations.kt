package dev.wildware.udea.network

import kotlin.reflect.KClass

/**
 * Classes annotated with this annotation will have their network synchronization
 * code generated.
 * */
@Target(AnnotationTarget.CLASS)
annotation class UdeaNetworked

/**
 * Annotate an object with this annotation to make it a serializer for the given class.
 * */
@Target(AnnotationTarget.CLASS)
annotation class UdeaSerializer(
    val forClass: KClass<*>
)
