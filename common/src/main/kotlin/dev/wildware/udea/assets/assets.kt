package dev.wildware.udea.assets

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

/**
 * Contains a reference to an asset which may or may not exist.
 * */
data class AssetFile(
    val type: String,
    val asset: Asset?
)

data class AssetReference<T : Asset>(
    @JsonValue
    val path: String
) {
    @get:JsonIgnore
    val value: T
        get() = Assets.find(path)

    companion object {
        @JvmStatic
        @JsonCreator
        fun createAssetReference(path: String): AssetReference<*> = AssetReference<Asset>(path)
    }
}

@JsonTypeInfo(
    use = JsonTypeInfo.Id.CLASS,
    include = JsonTypeInfo.As.PROPERTY,
)
@Serializable(with = AssetReferenceSerializer::class)
abstract class Asset {
    @JsonIgnore
    var path: String = ""

    @JsonIgnore
    var name: String = ""

    override fun equals(other: Any?): Boolean {
        return other is Asset && other.path == path
    }
}

object Assets {
    @PublishedApi
    internal val assets = mutableMapOf<String, Asset>()

    val ready: Boolean
        get() = assets.isNotEmpty()

    inline operator fun <reified T : Asset> get(path: String) = assets[path] as T?
        ?: error("Asset $path does not exist")

    fun <T : Asset> find(path: String) = assets[path] as T?
        ?: error("Asset $path does not exist ${debugAssets()}")

    operator fun set(path: String, asset: Asset) {
        assets[path] = asset
    }

    fun clear() {
        assets.clear()
    }

    fun toList(): List<Asset> {
        return assets.values.toList()
    }

    inline fun <reified T : Asset> filterIsInstance(): List<T> {
        return assets.values.filterIsInstance<T>()
    }

    fun <T : Asset> filterIsInstance(type: KClass<out T>): List<T> {
        return assets.values.filter { it::class.isSubclassOf(type) } as List<T>
    }

    private fun debugAssets() {
        println(assets)
    }
}

object AssetReferenceSerializer : KSerializer<Asset> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Asset", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Asset) {
        encoder.encodeString(value.path.substringAfter("/assets"))
    }

    override fun deserialize(decoder: Decoder): Asset {
        val path = decoder.decodeString()
        return Assets[path]
    }
}
