package dev.wildware.udea.assets

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import kotlin.reflect.KClass

class KClassSerializer : StdSerializer<KClass<*>>(KClass::class.java) {
    override fun serialize(value: KClass<*>, gen: JsonGenerator, provider: SerializerProvider) {
        gen.writeString(value.qualifiedName)
    }
}

class KClassDeserializer(
    private val classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
) : StdDeserializer<KClass<*>>(KClass::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): KClass<*> {
        return classLoader.loadClass(p.valueAsString).kotlin
    }
}
