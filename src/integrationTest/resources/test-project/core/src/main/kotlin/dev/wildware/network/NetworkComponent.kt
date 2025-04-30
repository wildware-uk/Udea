package dev.wildware.network

import com.github.quillraven.fleks.Component
import dev.wildware.network.SyncStrategy.All

/**
 * A component type that implements this interface will be synced over the network.
 * */
interface NetworkComponent<T : Component<T>> {
    /**
     * The strategy to use when syncing this component.
     * */
    val syncStrategy: SyncStrategy
        get() = SyncStrategy.All

    /**
     * How many ticks to wait before syncing this component.
     * */
    val syncTick: Int
        get() = 1

    /**
     * Whether this component is owned by the client or server.
     * */
    val networkAuthority: NetworkAuthority
        get() = NetworkAuthority.Server

    /**
     * Delegate to use for serialization.
     * */
    val delegate: SerializerDelegate<T, *>?
        get() = null

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
