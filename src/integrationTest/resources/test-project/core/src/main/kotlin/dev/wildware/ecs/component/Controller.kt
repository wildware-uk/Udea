package dev.wildware.ecs.component

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.assets.Asset
import dev.wildware.udea.assets.Assets
import dev.wildware.Control
import dev.wildware.network.NetworkAuthority
import dev.wildware.network.NetworkAuthority.Client
import dev.wildware.network.NetworkComponent
import dev.wildware.network.Networked
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Networked
@Serializable
class Controller : Component<Controller> {
    @Transient
    private val controls = Assets[Control].toList()

    val bindingPressed = Array(controls.size) { false }
    val bindingJustPressed = Array(controls.size) { false }

    override fun type() = Controller

    fun isPressed(control: Asset<Control>): Boolean {
        return bindingPressed[control().id]
    }

    fun isJustPressed(control: Asset<Control>): Boolean {
        return bindingJustPressed[control().id]
    }

    companion object : ComponentType<Controller>(), NetworkComponent<Controller> {
        override val networkAuthority = Client
    }
}
