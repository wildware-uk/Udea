package dev.wildware.udea.ecs.component.base

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.ecs.component.configureNetwork
import dev.wildware.udea.gameScreen
import dev.wildware.udea.network.UdeaNetworked
import dev.wildware.udea.network.serde.UdeaSync
import kotlinx.serialization.Serializable

/**
 * Entities with this component are networked and can be owned by a client.
 * */
@UdeaNetworked
@Serializable
data class Networkable(
    /**
     * The network id of the owner of this entity.
     * */
    @UdeaSync
    var owner: Int = 0,

    /**
     * Reference to the remote entity.
     * */
    @UdeaSync
    var remoteEntity: Entity = NONE,
) : Component<Networkable> {
    override fun type() = Networkable

    override fun World.onAdd(entity: Entity) {
        if (gameScreen.isServer) {
            remoteEntity = entity
        }
    }

    companion object : UdeaComponentType<Networkable>(
        networkComponent = configureNetwork(
            syncTick = 20
        )
    )
}
