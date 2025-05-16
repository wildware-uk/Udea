package dev.wildware.udea.ecs.component.base

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType

class Debug(
    val drawId: Boolean = false,
    val drawOwner: Boolean = false,
    val drawRemote: Boolean = false,
    val drawStats: Boolean = false,
    val debugPhysics: Boolean = false,
) : Component<Debug> {
    override fun type()= Debug
    companion object : ComponentType<Debug>()
}
