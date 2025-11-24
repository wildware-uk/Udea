package dev.wildware.udea.assets

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonTypeInfo
import dev.wildware.udea.assets.dsl.ListBuilder
import dev.wildware.udea.assets.dsl.UdeaDsl
import dev.wildware.udea.dsl.CreateDsl
import dev.wildware.udea.dsl.DslInclude
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

/**
 * Contains a reference to an asset which may or may not exist.
 * */
data class AssetFile(
    val type: String,
    val asset: Asset?
)

val EmptyReference = AssetReference<Asset>("")

@Serializable
data class AssetReference<out T : Asset>(
    val path: String
) {
    val value: T
        get() = Assets.find(path)
}

/**
 * Creates a reference to an asset.
 * */
@UdeaDsl
fun <T : Asset> reference(path: String) = AssetReference<T>(path)


/**
 * Adds a reference to an asset to a list builder.
 * */
@UdeaDsl
fun <T : Asset> ListBuilder<AssetReference<T>>.reference(path: String) {
    add(AssetReference(path))
}

@CreateDsl
@JsonTypeInfo(
    use = JsonTypeInfo.Id.CLASS,
    include = JsonTypeInfo.As.PROPERTY,
)
abstract class Asset {
    @JsonIgnore
    var path: String = ""

    @JsonIgnore
    @DslInclude
    var name: String = ""

    val qualifiedName: String
        get() = "$path/$name"

    @get:JsonIgnore
    val reference: AssetReference<Asset>
        get() = AssetReference(qualifiedName)

    override fun equals(other: Any?): Boolean {
        return other is Asset && other.path == path
    }

    override fun hashCode(): Int = path.hashCode()
}

object Assets {
    @PublishedApi
    internal val assets = mutableMapOf<String, Asset>()

    val ready: Boolean
        get() = assets.isNotEmpty()

    inline operator fun <reified T : Asset> get(path: String) = assets[path] as T?
        ?: error("Asset $path does not exist ${debugAssets()}")

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

    fun debugAssets(): String {
        return assets.toString()
    }
}

data class AssetBundle(val assets: List<Asset>)

/**
 * Allows multiple assets to be defined in the same file.
 * */
fun bundle(builder: ListBuilder<Asset>.() -> Unit = {}): AssetBundle {
    val listBuilder = ListBuilder<Asset>()
    builder(listBuilder)
    return AssetBundle(listBuilder.build())
}
