package dev.wildware.udea.network

import dev.wildware.udea.UdeaReflections.udeaReflections
import kotlin.reflect.KClass

object InPlaceSerializers {
    private val serializers = udeaReflections
        .getTypesAnnotatedWith(UdeaSerializer::class.java)
        .associate { it.getAnnotation(UdeaSerializer::class.java).forClass to it.kotlin.objectInstance as InPlaceSerializer<*> }

    fun <T : Any> KClass<in T>.inPlaceSerializer(): InPlaceSerializer<T> {
        return (serializers[this] ?: error("No inplace serializer found for $this")) as InPlaceSerializer<T>
    }

    inline fun <reified T> T.inPlaceSerializer() = this::class.inPlaceSerializer()
}
