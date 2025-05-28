package dev.wildware.ecs.component

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.math.Vector2

class CameraTrack(
    val offset: Vector2 = Vector2.Zero
) : Component<CameraTrack> {
    val position = Vector2()

    override fun type() = CameraTrack

    companion object : ComponentType<CameraTrack>()
}
