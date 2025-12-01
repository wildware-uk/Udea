package dev.wildware.udea.network

import dev.wildware.udea.network.InPlaceSerializers.inPlaceSerializer
import kotlinx.serialization.*
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

fun ByteBuffer.putString(str: String) = putSerializable(str)
fun ByteBuffer.getString(): String = getSerializable()

fun ByteBuffer.putBoolean(bool: Boolean) = put(if (bool) 1 else 0)
fun ByteBuffer.getBoolean(): Boolean = get() != 0.toByte()

// TODO can this be improved?
inline fun <reified T> ByteBuffer.putSerializable(any: T, serializer: KSerializer<T> = serializer()) {
    val data = cbor.encodeToByteArray(serializer, any)
    putInt(data.size)
    put(data)
}

@OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
fun <T : Any> ByteBuffer.putSerializable(any: Any, kClass: KClass<Any>) {
    val data = cbor.encodeToByteArray(kClass.serializer(), any)
    putInt(data.size)
    put(data)
}

inline fun <reified T> ByteBuffer.getSerializable(serializer: KSerializer<T> = serializer()): T {
    val size = getInt()
    val array = ByteArray(size)
    get(array)
    return cbor.decodeFromByteArray(serializer, array)
}

@OptIn(InternalSerializationApi::class)
fun <T : Any> ByteBuffer.getSerializable(kClass: KClass<T>): T {
    val size = getInt()
    val array = ByteArray(size)
    get(array)
    return cbor.decodeFromByteArray(kClass.serializer(), array)
}

fun <T : Any> polymorphicSerializerFor(kClass: KClass<T>): InPlaceSerializer<Any> {
    return kClass.inPlaceSerializer() as InPlaceSerializer<Any>
}
