package dev.wildware.udea.network.serde

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import dev.wildware.udea.assets.AssetRefImpl

object AssetReferenceSerializer : Serializer<AssetRefImpl<*>>() {
    override fun write(kryo: Kryo, output: Output, reference: AssetRefImpl<*>) {
        output.writeString(reference.path)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out AssetRefImpl<*>>): AssetRefImpl<*> {
        return AssetRefImpl(input.readString())
    }
}
