package dev.wildware.ecs.component

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType

class PlayerController : Component<PlayerController> {
    override fun type() = PlayerController

    companion object : ComponentType<PlayerController>()
}
