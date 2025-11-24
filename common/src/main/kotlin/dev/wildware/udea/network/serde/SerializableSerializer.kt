package dev.wildware.udea.network.serde

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import dev.wildware.udea.network.cbor
import kotlinx.serialization.*

@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
object SerializableSerializer : Serializer<Any>() {
    override fun write(kryo: Kryo, output: Output, obj: Any) {
        val serializer = obj::class.serializerOrNull()
            ?: error("No serializer found for ${obj::class}")
        val bytes = cbor.encodeToByteArray(serializer as SerializationStrategy<Any>, obj)
        output.writeInt(bytes.size)
        output.writeBytes(bytes)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out Any>): Any {
        val size = input.readInt()
        val bytes = input.readBytes(size)
        val kClass = type.kotlin
        val serializer = kClass.serializerOrNull()
            ?: error("No serializer found for $type")

        return cbor.decodeFromByteArray(serializer as DeserializationStrategy<Any>, bytes)
    }
}
