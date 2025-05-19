package dev.wildware.udea.ecs

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.ecs.SyncStrategy.All

/**
 * A component type that implements this interface will be synced over the network.
 * */
class NetworkComponent<T : Component<T>>(
    var syncStrategy: SyncStrategy = SyncStrategy.All,
    var syncTick: Int = 1,
    var networkAuthority: NetworkAuthority = NetworkAuthority.Server,
    var delegate: SerializerDelegate<T, *>? = null
) {
    /**
     * @return true if this component has a delegate.
     * */
    val isDelegated: Boolean
        get() = delegate != null

    /**
     * Checks if this component should be synced with the given sync strategy.
     * */
    fun checkSyncStrategy(syncStrategy: SyncStrategy): Boolean {
        return this.syncStrategy == syncStrategy || this.syncStrategy == All || syncStrategy == All
    }

    /**
     * Checks if the given [NetworkAuthority] matches this component's authority.
     * */
    fun checkNetworkAuthority(networkAuthority: NetworkAuthority): Boolean {
        return this.networkAuthority == networkAuthority
    }

    class NetworkComponentBuilder<T : Component<T>> {
        /**
         * Strategy for when to sync this component.
         * */
        var syncStrategy: SyncStrategy = All

        /**
         * Frequency in ticks to sync this component.
         * */
        var syncTick: Int = 1

        /**
         * Who has authority over this component?
         * */
        var networkAuthority: NetworkAuthority = NetworkAuthority.Server

        /**
         * Delegate serialization for this component.
         * @see dev.wildware.udea.ecs.component.physics.Body for example.
         * */
        var delegate: SerializerDelegate<T, *>? = null

        fun build() = NetworkComponent(
            syncStrategy,
            syncTick,
            networkAuthority,
            delegate
        )
    }

    companion object {
        fun <T : Component<T>> configureNetwork(init: NetworkComponentBuilder<T>.() -> Unit = {}): NetworkComponent<T> {
            val builder = NetworkComponentBuilder<T>()
            builder.init()
            return builder.build()
        }
    }
}

enum class SyncStrategy {
    /**
     * Syncs on entity create and update.
     * */
    All,

    /**
     * Syncs on entity create.
     * */
    Create,

    /**
     * Syncs on entity update.
     * */
    Update;
}

/**
 * Defines a network authority type.
 * */
enum class NetworkAuthority {
    /** Can be updated by clients. */
    Client,

    /** Can only be updated by the server. */
    Server
}

interface RawSerializerDelegate {
    fun createRaw(component: Component<*>): ComponentDelegate
}

/**
 * Allows a class to delegate its serialization to a simple object, and apply that data later.
 * */
interface SerializerDelegate<COMP : Component<COMP>, out DELEGATE : ComponentDelegate> : RawSerializerDelegate {
    fun create(component: COMP): DELEGATE

    @Suppress("UNCHECKED_CAST")
    override fun createRaw(component: Component<*>): ComponentDelegate {
        return this.create(component as COMP)
    }
}

/**
 * A interface for data classes to delegate serialization of a component.
 * */
interface ComponentDelegate {
    fun World.applyToEntity(entity: Entity)
}