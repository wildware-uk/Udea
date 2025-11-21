package dev.wildware.udea.ecs.component.animation

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.assets.CharacterAnimations

class CharacterAnimationController(
    val characterAnimations: CharacterAnimations
) : Component<CharacterAnimationController> {
    override fun type() = CharacterAnimationController

    companion object : ComponentType<CharacterAnimationController>()
}
