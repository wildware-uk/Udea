package dev.wildware.udea.ecs.component.render

import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.github.quillraven.fleks.Component
import dev.wildware.udea.assets.*
import dev.wildware.udea.ecs.component.ComponentDependency.Companion.dependencies
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.ecs.component.animation.Animations

/**
 * A component that holds a reference to a sprite animation set.
 * */
class AnimationSet(
    spriteAnimationSet: AssetReference<SpriteAnimationSet>,
    val defaultAnimation: String? = null,
    val frameTime: Float = 1.0F,
) : Component<AnimationSet> {

    val spriteAnimationSet = spriteAnimationSet.value

    var currentAnimation: AnimationInstance<out TextureRegion>? = null

    override fun type() = AnimationSet

    companion object : UdeaComponentType<AnimationSet>(
        dependsOn = dependencies(Animations)
    )
}
