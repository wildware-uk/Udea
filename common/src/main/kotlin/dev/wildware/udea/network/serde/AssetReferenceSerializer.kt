package dev.wildware.udea.network.serde

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import dev.wildware.udea.assets.Asset
import dev.wildware.udea.assets.AssetReference

object AssetReferenceSerializer : Serializer<AssetReference<Asset>>() {
    override fun write(kryo: Kryo, output: Output, reference: AssetReference<Asset>) {
        output.writeString(reference.path)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out AssetReference<Asset>>): AssetReference<Asset> {
        return AssetReference(input.readString())
    }
}