package dev.wildware.udea

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import com.badlogic.gdx.math.Vector2 as GdxVec2

typealias Vector2 = @Serializable(with = Vector2Serializer::class) GdxVec2

object Vector2Serializer : KSerializer<Vector2> {
    override fun deserialize(decoder: Decoder): Vector2 {
        return Vector2(decoder.decodeFloat(), decoder.decodeFloat())
    }

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.badlogic.gdx.math.Vector2") {
        element<Float>("x")
        element<Float>("y")
    }

    override fun serialize(encoder: Encoder, value: Vector2) {
        encoder.encodeFloat(value.x)
        encoder.encodeFloat(value.y)
    }
}
