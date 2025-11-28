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
 * Marks a value parameter as an asset reference string.
 *
 * Adds the ability to click-through and adds compile-time validation.
 * */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class AssetRef

object EmptyReference : AssetReference<Nothing> {
    override val value: Nothing
        get() = error("Tried to access empty blueprint reference")
}

interface AssetReference<T : Asset<T>> {
    val value: T
}

@Serializable
data class AssetRefImpl<T : Asset<T>>(
    @AssetRef val path: String
) : AssetReference<T> {
    override val value: T
        get() = Assets.find(path)
}

/**
 * Creates a reference to an asset.
 * */
@UdeaDsl
fun <T : Asset<T>> reference(@AssetRef path: String) = AssetRefImpl<T>(path)


/**
 * Adds a reference to an asset to a list builder.
 * */
@UdeaDsl
fun <T : Asset<T>> ListBuilder<AssetReference<in T>>.reference(@AssetRef path: String) {
    add(AssetRefImpl(path))
}

@CreateDsl
@JsonTypeInfo(
    use = JsonTypeInfo.Id.CLASS,
    include = JsonTypeInfo.As.PROPERTY,
)
abstract class Asset<T : Asset<T>> : AssetReference<T> {
    @JsonIgnore
    var path: String = ""

    @JsonIgnore
    @DslInclude
    var name: String = ""

    val qualifiedName: String
        get() = "$path/$name"

    @get:JsonIgnore
    val reference: AssetReference<T>
        get() = AssetRefImpl<T>(qualifiedName)

    override val value: T = this as T

    override fun equals(other: Any?): Boolean {
        return other is Asset<T> && other.path == path
    }

    override fun hashCode(): Int = path.hashCode()
}

object Assets {
    @PublishedApi
    internal val assets = mutableMapOf<String, Asset<*>>()

    val ready: Boolean
        get() = assets.isNotEmpty()

    operator fun <T : Asset<T>> get(path: String) = assets[path] as T?
        ?: error("Asset $path does not exist ${debugAssets()}")

    fun <T : Asset<T>> find(path: String) = assets[path] as T?
        ?: error("Asset $path does not exist ${debugAssets()}")

    operator fun set(path: String, asset: Asset<*>) {
        assets[path] = asset
    }

    fun clear() {
        assets.clear()
    }

    fun toList(): List<Asset<*>> {
        return assets.values.toList()
    }

    inline fun <reified T : Asset<T>> filterIsInstance(): List<T> {
        return assets.values.filterIsInstance<T>()
    }

    fun <T : Asset<T>> filterIsInstance(type: KClass<out T>): List<T> {
        return assets.values.filter { it::class.isSubclassOf(type) } as List<T>
    }

    fun debugAssets(): String {
        return assets.toString()
    }
}

data class AssetBundle(val assets: List<Asset<*>>)

/**
 * Allows multiple assets to be defined in the same file.
 * */
fun bundle(builder: ListBuilder<Asset<*>>.() -> Unit = {}): AssetBundle {
    val listBuilder = ListBuilder<Asset<*>>()
    builder(listBuilder)
    return AssetBundle(listBuilder.build())
}
