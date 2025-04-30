package dev.wildware.ecs.component

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.math.Affine2
import dev.wildware.math.Vector2
import dev.wildware.network.NetworkComponent
import dev.wildware.udea.network.Networked
import kotlinx.serialization.Serializable

@Networked
@Serializable
class Transform : Component<Transform> {
    val position: Vector2 = Vector2()
    var rotation: Float = 0F
    val scale: Vector2 = Vector2(1f, 1f)

    override fun type() = Transform

    override fun toString(): String {
        return "Transform(position=$position, rotation=$rotation, scale=$scale)"
    }

    companion object : ComponentType<Transform>(), NetworkComponent<Transform> {
        override val syncTick = 20
    }
}
