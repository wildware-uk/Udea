package dev.wildware.udea.ecs.component.base

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.ecs.NetworkComponent.Companion.configureNetwork
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.ecs.component.UdeaProperty
import dev.wildware.udea.ecs.component.UdeaProperty.UAttribute.Ignore
import dev.wildware.udea.game
import dev.wildware.udea.network.UdeaNetworked

/**
 * Entities with this component are networked and can be owned by a client.
 * */
@UdeaNetworked
data class Networkable(
    /**
     * The id of the owner of this entity.
     * */
    @UdeaProperty(Ignore)
    var owner: Int = 0,

    /**
     * The id of this entity on the server.
     * */
    @UdeaProperty(Ignore)
    var remoteId: Int = -1,
) : Component<Networkable> {
    override fun type() = Networkable

    override fun World.onAdd(entity: Entity) {
        if (game.isServer) {
            remoteId = entity.id
        }
    }

    companion object : UdeaComponentType<Networkable>(
        networkComponent = configureNetwork {
            syncTick = 20
        }
    )
}
