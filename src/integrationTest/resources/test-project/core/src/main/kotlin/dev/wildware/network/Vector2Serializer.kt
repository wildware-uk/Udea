package dev.wildware.network

import com.badlogic.gdx.math.Affine2
import com.badlogic.gdx.math.Vector2
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

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

object Affine2Serializer : KSerializer<Affine2> {
    override val descriptor = buildClassSerialDescriptor("Affine2") {
        element<Float>("m00")
        element<Float>("m01")
        element<Float>("m02")
        element<Float>("m10")
        element<Float>("m11")
        element<Float>("m12")
    }

    override fun serialize(encoder: Encoder, value: Affine2) {
        val composite = encoder.beginStructure(descriptor)
        composite.encodeFloatElement(descriptor, 0, value.m00)
        composite.encodeFloatElement(descriptor, 1, value.m01)
        composite.encodeFloatElement(descriptor, 2, value.m02)
        composite.encodeFloatElement(descriptor, 3, value.m10)
        composite.encodeFloatElement(descriptor, 4, value.m11)
        composite.encodeFloatElement(descriptor, 5, value.m12)
        composite.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): Affine2 {
        val composite = decoder.beginStructure(descriptor)
        var m00 = 1f
        var m01 = 0f
        var m02 = 0f
        var m10 = 0f
        var m11 = 1f
        var m12 = 0f
        loop@ while (true) {
            when (val index = composite.decodeElementIndex(descriptor)) {
                0 -> m00 = composite.decodeFloatElement(descriptor, 0)
                1 -> m01 = composite.decodeFloatElement(descriptor, 1)
                2 -> m02 = composite.decodeFloatElement(descriptor, 2)
                3 -> m10 = composite.decodeFloatElement(descriptor, 3)
                4 -> m11 = composite.decodeFloatElement(descriptor, 4)
                5 -> m12 = composite.decodeFloatElement(descriptor, 5)
                CompositeDecoder.DECODE_DONE -> break@loop
                else -> error("Unexpected index: $index")
            }
        }
        composite.endStructure(descriptor)
        return Affine2().apply {
            this.m00 = m00
            this.m01 = m01
            this.m02 = m02
            this.m10 = m10
            this.m11 = m11
            this.m12 = m12
        }
    }
}
