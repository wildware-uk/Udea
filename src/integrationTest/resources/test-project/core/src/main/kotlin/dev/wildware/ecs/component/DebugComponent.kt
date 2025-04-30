package dev.wildware.ecs.component

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType

class DebugComponent(
    val drawId: Boolean = false,
    val drawOwner: Boolean = false,
    val drawRemote: Boolean = false,
    val drawStats: Boolean = false,
    val debugPhysics: Boolean = false,
) : Component<DebugComponent> {
    override fun type()= DebugComponent
    companion object : ComponentType<DebugComponent>()
}
