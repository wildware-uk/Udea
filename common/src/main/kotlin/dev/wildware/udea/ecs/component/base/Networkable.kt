package dev.wildware.udea.ecs.component.base

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.network.Networked
import dev.wildware.udea.ecs.NetworkComponent.Companion.configureNetwork
import dev.wildware.udea.ecs.component.UdeaComponentType

/**
 * Entities with this component are networked and can be owned by a client.
 * */
@Networked
class Networkable(
    /**
     * The id of the owner of this entity.
     * */
    var owner: Int = 0,

    /**
     * The id of this entity on the server.
     * */
    var remoteId: Int = -1,
) : Component<Networkable> {
    override fun type() = Networkable

    override fun World.onAdd(entity: Entity) {
//        if (game.isServer) {
//            remoteId = entity.id
//        }
    }

    companion object : UdeaComponentType<Networkable>(
        networkComponent = configureNetwork {
            syncTick = 20
        }
    )
}
