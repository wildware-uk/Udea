package dev.wildware.udea.network

import java.nio.ByteBuffer

/**
 * A special interface for serializing and deserializing objects in-place.
 *
 * This class makes use of pooled entity updates, meaning it's superfast and memory efficient.
 * */
interface InPlaceSerializer<T> {
    /**
     * Serialize the current component state into the entity update.
     * */
    fun serialize(component: T, byteBuffer: ByteBuffer)

    /**
     * Deserialize the entity update into the component state.
     * */
    fun deserialize(component: T, byteBuffer: ByteBuffer)
}
