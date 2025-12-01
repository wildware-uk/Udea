package dev.wildware.udea.ecs.component.control

import com.github.quillraven.fleks.Component
import dev.wildware.udea.Vector2
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.ecs.component.configureNetwork
import dev.wildware.udea.network.UdeaNetworked
import dev.wildware.udea.network.serde.UdeaSync
import kotlinx.serialization.Serializable

/**
 * Generic character controller for allowing players and AI to control
 * characters.
 * */
@UdeaNetworked
@Serializable
class CharacterController(
    var moveSpeed: Float = .01F,
) : Component<CharacterController> {
    var isActive: Boolean = true

    @UdeaSync
    val movement: Vector2 = Vector2()

    override fun type() = CharacterController

    companion object : UdeaComponentType<CharacterController>(
        networkComponent = configureNetwork(
            networkAuthority = Client
        )
    )
}
