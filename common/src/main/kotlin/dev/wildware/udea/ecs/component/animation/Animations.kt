package dev.wildware.udea.ecs.component.animation

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.assets.Animation
import dev.wildware.udea.assets.AnimationInstance

class Animations : Component<Animations> {

    val animations = mutableListOf<AnimationInstance<*>>()

    /**
     * Adds a new animation to this list.
     * */
    fun <T> addAnimation(
        animation: Animation<T>,
        autoPlay: Boolean = false
    ): AnimationInstance<T> {
        return AnimationInstance(animation, autoPlay).also {
            this.animations.add(it)
        }
    }

    fun removeAnimation(animation: AnimationInstance<*>) {
        this.animations.remove(animation)
    }

    override fun type() = Animations

    companion object : ComponentType<Animations>()
}