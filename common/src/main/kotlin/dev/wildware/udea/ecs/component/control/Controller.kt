package dev.wildware.udea.ecs.component.control

import com.github.quillraven.fleks.Component
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.assets.Control
import dev.wildware.udea.ecs.component.NetworkAuthority.Client
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.ecs.component.configureNetwork

class Controller : Component<Controller> {
    private val controls = Assets
        .filterIsInstance<Control>()

    val bindingPressed = Array(controls.size + 1) { false }

    val bindingJustPressed = Array(controls.size + 1) { false }

    override fun type() = Controller

    fun isPressed(control: Control): Boolean {
        return bindingPressed[control.controlId]
    }

    fun isJustPressed(control: Control): Boolean {
        return bindingJustPressed[control.controlId]
    }

    companion object : UdeaComponentType<Controller>()
}
