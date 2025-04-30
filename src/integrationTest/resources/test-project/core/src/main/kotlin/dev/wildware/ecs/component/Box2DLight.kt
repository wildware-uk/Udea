package dev.wildware.ecs.component

import box2dLight.PointLight
import box2dLight.PositionalLight
import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World

class Box2DLight(
    val pointLight: PositionalLight
) : Component<Box2DLight> {
    override fun type() = Box2DLight

    companion object : ComponentType<Box2DLight>()
}
