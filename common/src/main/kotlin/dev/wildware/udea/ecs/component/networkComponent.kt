package dev.wildware.udea.ecs.component

import com.github.quillraven.fleks.Component
import dev.wildware.udea.assets.dsl.ListBuilder
import dev.wildware.udea.assets.dsl.UdeaDsl
import dev.wildware.udea.dsl.CreateDsl
import dev.wildware.udea.ecs.component.SyncStrategy.All

/**
 * A component type that implements this interface will be synced over the network.
 * */
@CreateDsl(name = "configureNetwork")
class NetworkComponent<T : Component<T>>(
    var syncStrategy: SyncStrategy = All,
    var syncTick: Int = 1,
    var networkAuthority: NetworkAuthority = Server,
    var delegates: List<ComponentDelegate<T, *>> = emptyList()
) {
    /**
     * @return true if this component has a delegate.
     * */
    val isDelegated: Boolean
        get() = delegates.isNotEmpty()

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
        return networkAuthority == Server || this.networkAuthority == networkAuthority
    }
}

/**
 * Returns true if this component network data is delegated. (works on null)
 * */
val NetworkComponent<*>?.isDelegatedSafe: Boolean
    get() = this?.isDelegated == true

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

/**
 * Delegate class for serializing values on a component.
 * */
@CreateDsl(name = "delegate", onlyList = true)
data class ComponentDelegate<COMP, T>(
    val property: COMP.()->T,
)
