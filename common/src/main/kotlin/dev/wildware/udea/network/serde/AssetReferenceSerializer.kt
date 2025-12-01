package dev.wildware.udea.network.serde

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import dev.wildware.udea.assets.AssetRefImpl
import dev.wildware.udea.assets.AssetReference
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Asset Reference serializer for Kryo.
 * */
object AssetReferenceSerializer : Serializer<AssetReference<*>>() {
    override fun write(kryo: Kryo, output: Output, reference: AssetReference<*>) {
        output.writeString(reference.qualifiedName)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out AssetReference<*>>): AssetRefImpl<*> {
        return AssetRefImpl(input.readString())
    }
}

object AssetRefKSerializer : KSerializer<AssetReference<*>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("AssetRef", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: AssetReference<*>) {
        encoder.encodeString(value.qualifiedName)
    }

    override fun deserialize(decoder: Decoder): AssetReference<*> {
        return AssetRefImpl(decoder.decodeString())
    }
}