package dev.wildware.udea.ecs.component.animation

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.assets.AnimationMap

/**
 * Component to hold an [AnimationMap].
 * */
class AnimationMapHolder(
    val animationMap: AnimationMap
) : Component<AnimationMapHolder> {

    inline fun <reified T : AnimationMap> animationMap() = animationMap as T

    override fun type() = AnimationMapHolder

    companion object : ComponentType<AnimationMapHolder>()
}
