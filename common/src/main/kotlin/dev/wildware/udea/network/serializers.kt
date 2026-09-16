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

    // `T : Any`, not `T`, since Kotlin 2.4 rejects a class literal on a nullable expression
    // (issue #186). It is the true constraint rather than a `!!`: `this::class` on a null
    // receiver throws, so no caller ever passed one. Nothing in this module calls this
    // overload -- every use site goes through the `KClass<in T>` one above -- so the tighter
    // bound breaks nothing.
    inline fun <reified T : Any> T.inPlaceSerializer() = this::class.inPlaceSerializer()
}
