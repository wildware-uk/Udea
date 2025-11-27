package dev.wildware.udea.ecs.component.render

import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.github.quillraven.fleks.Component
import dev.wildware.udea.assets.AnimationInstance
import dev.wildware.udea.assets.AssetReference
import dev.wildware.udea.assets.SpriteAnimation
import dev.wildware.udea.assets.SpriteAnimationSet
import dev.wildware.udea.ecs.component.ComponentDependency.Companion.dependencies
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.ecs.component.animation.Animations

/**
 * A component that holds a reference to a sprite animation set.
 * */
class AnimationHolder(
    val spriteAnimationSet: AssetReference<SpriteAnimationSet>,
    val defaultAnimation: String? = null,
    val frameTime: Float = 1.0F,
) : Component<AnimationHolder> {
    var currentAnimation: SpriteAnimation? = null
    var currentAnimationInstance: AnimationInstance<out TextureRegion>? = null

    override fun type() = AnimationHolder

    companion object : UdeaComponentType<AnimationHolder>(
        dependsOn = dependencies(Animations)
    )
}
