package dev.wildware.udea.assets

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

data class AssetRefence<T : Asset>(
    @JsonValue
    val path: String
) {
    @get:JsonIgnore
    val value: T
        get() = Assets.find(path)

    companion object {
        @JvmStatic
        @JsonCreator
        fun createAssetReference(path: String): AssetRefence<*> = AssetRefence<Asset>(path)
    }
}

@JsonTypeInfo(
    use = JsonTypeInfo.Id.CLASS,
    include = JsonTypeInfo.As.PROPERTY,
)
abstract class Asset {
    @JsonIgnore
    lateinit var id: String
        internal set

    @JsonIgnore
    var path: String = ""
}

object Assets {
    @PublishedApi
    internal val assets = mutableMapOf<String, Asset>()

    inline operator fun <reified T : Asset> get(path: String) = assets[path] as T?
        ?: error("Asset $path does not exist")

    fun <T : Asset> find(path: String) = assets[path] as T?
        ?: error("Asset $path does not exist")

    operator fun set(path: String, asset: Asset) {
        assets[path] = asset
    }

    fun clear() {
        assets.clear()
    }

    fun toList(): List<Asset> {
        return assets.values.toList()
    }
}

object AssetReferenceSerializer : KSerializer<Asset> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Asset", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Asset) {
        encoder.encodeString(value.id)
    }

    override fun deserialize(decoder: Decoder): Asset {
        val (typeName, id) = decoder.decodeString().split("/", limit = 2)
        return Assets[id]
    }
}

