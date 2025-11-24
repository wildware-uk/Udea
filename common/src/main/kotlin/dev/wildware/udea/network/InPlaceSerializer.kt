package dev.wildware.udea.network

import dev.wildware.udea.network.InPlaceSerializers.inPlaceSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import java.nio.ByteBuffer
import kotlin.reflect.KClass

/**
 * A special interface for serializing and deserializing objects in-place from kryo.
 *
 * This class makes use of pooled entity updates, meaning it's superfast and memory efficient.
 * */
interface InPlaceSerializer<in T> {
    /**
     * Serialize the current component state into the entity update.
     * */
    fun serialize(component: T, data: ByteBuffer)

    /**
     * Deserialize the entity update into the component state.
     * */
    fun deserialize(component: T, data: ByteBuffer)
}

fun ByteBuffer.putBoolean(bool: Boolean) = put(if (bool) 1 else 0)
fun ByteBuffer.getBoolean(): Boolean = get() != 0.toByte()

// TODO can this be improved?
inline fun <reified T> ByteBuffer.putSerializable(any: T) {
    val data = cbor.encodeToByteArray(any)
    putInt(data.size)
    put(data)
}

inline fun <reified T> ByteBuffer.getSerializable(): T {
    val size = getInt()
    val array = ByteArray(size)
    get(array)
    return cbor.decodeFromByteArray<T>(array)
}

fun <T : Any> polymorphicSerializerFor(kClass: KClass<T>): InPlaceSerializer<Any> {
    return kClass.inPlaceSerializer() as InPlaceSerializer<Any>
}
