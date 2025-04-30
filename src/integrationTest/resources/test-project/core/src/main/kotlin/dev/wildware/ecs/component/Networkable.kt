package dev.wildware.ecs.component

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.game
import dev.wildware.network.NetworkComponent
import dev.wildware.udea.network.Networked
import kotlinx.serialization.Serializable

@Networked
@Serializable
data class Networkable(
    var owner: Int = 0,
    var remoteId: Int = -1
) : Component<Networkable> {

    override fun World.onAdd(entity: Entity) {
        if (game.isServer) {
            remoteId = entity.id
        }
    }

    override fun type() = Networkable

    companion object : ComponentType<Networkable>(), NetworkComponent<Networkable> {
        override val syncTick = 20
    }
}
