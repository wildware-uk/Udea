package dev.wildware.udea.ecs.control

import com.github.quillraven.fleks.Component
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.assets.Control
import dev.wildware.udea.ecs.NetworkAuthority.Client
import dev.wildware.udea.ecs.NetworkComponent.Companion.configureNetwork
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.network.Networked

@Networked
class Controller : Component<Controller> {
    @Transient
    private val controls = Assets
        .filterIsInstance<Control>()

    val bindingPressed = Array(controls.size) { false }
    val bindingJustPressed = Array(controls.size) { false }

    override fun type() = Controller

    fun isPressed(control: Control): Boolean {
        return bindingPressed[control.controlId]
    }

    fun isJustPressed(control: Control): Boolean {
        return bindingJustPressed[control.controlId]
    }

    companion object : UdeaComponentType<Controller>(
        networkComponent = configureNetwork {
            networkAuthority = Client
        }
    )
}
