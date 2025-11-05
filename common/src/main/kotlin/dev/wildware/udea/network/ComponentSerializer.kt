package dev.wildware.udea.network

import com.github.quillraven.fleks.Component

/**
 * A special interface for serializing and deserializing components in-place.
 *
 * This class makes use of pooled entity updates, meaning its super fast and memory efficient.
 * */
interface ComponentSerializer<T: Component<out Any>> {
    /**
     * Serialize the current component state into the entity update.
     * */
    fun serialize(component: T, entityUpdate: EntityUpdate)

    /**
     * Deserialize the entity update into the component state.
     * */
    fun deserialize(component: T, entityUpdate: EntityUpdate)
}
