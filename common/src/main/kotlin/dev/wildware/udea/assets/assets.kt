package dev.wildware.udea.assets

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import dev.wildware.udea.Json
import dev.wildware.udea.KProvided
import dev.wildware.udea.KReference
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.io.File
import java.net.URL
import kotlin.reflect.jvm.jvmName

@Serializable(with = AssetReferenceSerializer::class)
@JsonSerialize(using = AssetJacksonSerializer::class)
@JsonDeserialize(using = AssetJacksonDeserializer::class)
@KReference
data class Asset<T>(
    @KProvided
    val id: String,

    @JsonTypeInfo(
        use = JsonTypeInfo.Id.CLASS,
        include = JsonTypeInfo.As.PROPERTY,
        property = "@class"
    )
    val value: T,
    val type: AssetType<out T>
) {
    operator fun invoke() = value

    override fun toString(): String {
        return "$type/$id"
    }
}

object Assets {
    private val assets = mutableMapOf<String, AssetSet<*>>()

    operator fun <T> get(assetType: AssetType<T>): AssetSet<T> =
        assets.getOrPut(assetType.id) { AssetSet(assetType) } as AssetSet<T>

    operator fun get(id: String) = assets[id]!!

    private fun loadAssets(classLoader: ClassLoader = this::class.java.classLoader) {
        val resources = classLoader.getResources("")
        while (resources.hasMoreElements()) {
            val root = resources.nextElement()
            loadUdeaFiles(root)
        }
    }

    private fun loadUdeaFiles(root: URL) {
        val rootFile = File(root.toURI())
        if (rootFile.isDirectory) {
            rootFile.walk()
                .filter { it.isFile && it.extension == "udea" }
                .forEach { file ->
                    val asset = Json.fromJson<Asset<*>>(file.readText())
                }
        }
    }
}

class AssetSet<T>(
    val assetType: AssetType<T>,
    private val assets: MutableMap<String, Asset<T>> = mutableMapOf()
) : Iterable<Asset<T>> by assets.values {

    operator fun set(id: String, value: T) {
        assets[id] = Asset(id, value, assetType)
    }

    operator fun get(id: String): Asset<T> = assets[id]
        ?: throw kotlin.IllegalArgumentException("Missing asset ${assetType.id}/$id")

    fun getOrNull(id: String): Asset<T>? = assets[id]
}

class AssetJacksonSerializer : JsonSerializer<Asset<*>>() {
    override fun serialize(value: Asset<*>, gen: JsonGenerator, serializers: SerializerProvider) {
        gen.writeStartObject()
        gen.writeStringField("type", value.type::class.jvmName)
        gen.writeStringField("id", value.id)
        gen.writeStringField("@class", value.value!!::class.jvmName)
        gen.writeObjectField("value", value.value)
        gen.writeEndObject()
    }
}

class AssetJacksonDeserializer : JsonDeserializer<Asset<*>>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Asset<*> {
        val node = p.codec.readTree<JsonNode>(p)
        val typeClass = ctxt.typeFactory.classLoader.loadClass((node.get("type").asText())).kotlin
        val type = typeClass.objectInstance as AssetType<out Any>
        val id = node.get("id").asText()
        val valueNode = node.get("value")
        val valueClass = ctxt.typeFactory.classLoader.loadClass(node.get("@class").asText())
        val value = p.codec.treeToValue(valueNode, valueClass)

        return Asset(id, value, type)
    }
}

@KProvided
abstract class AssetType<T> {
    abstract val id: String
    override fun toString() = id
}

object AssetReferenceSerializer : KSerializer<Asset<*>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Asset", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Asset<*>) {
        encoder.encodeString("${value.type.id}/${value.id}")
    }

    override fun deserialize(decoder: Decoder): Asset<*> {
        val (typeName, id) = decoder.decodeString().split("/", limit = 2)
        return Assets[typeName][id]
    }
}

