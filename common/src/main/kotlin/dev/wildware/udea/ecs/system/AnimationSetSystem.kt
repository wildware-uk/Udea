package dev.wildware.udea.ecs.system

import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.FamilyOnAdd
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import com.github.quillraven.fleks.World.Companion.inject
import dev.wildware.udea.assets.AnimationInstance
import dev.wildware.udea.assets.SpriteAnimation
import dev.wildware.udea.ecs.UdeaSystem
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Editor
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Game
import dev.wildware.udea.ecs.component.animation.Animations
import dev.wildware.udea.ecs.component.base.Debug
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.ecs.component.render.AnimationHolder
import dev.wildware.udea.gameScreen
import dev.wildware.udea.getOrNull
import dev.wildware.udea.use
import dev.wildware.udea.ecs.component.render.SpriteRenderer as SpriteComponent

@UdeaSystem(runIn = [Editor, Game])
class AnimationSetSystem(
    val spriteBatch: SpriteBatch = inject()
) : IteratingSystem(
    family { all(Transform, AnimationHolder, Animations, SpriteComponent) }
), FamilyOnAdd {

    override fun onAddEntity(entity: Entity) {
        val animationHolder = entity[AnimationHolder]

        if (animationHolder.defaultAnimation != null) {
            setAnimation(entity, animationHolder.defaultAnimation)
        }
    }

    fun setAnimation(entity: Entity, name: String, force: Boolean = false): AnimationInstance<out TextureRegion>? {
        val animationHolder = entity[AnimationHolder]
        val currentAnimationInstance = animationHolder.currentAnimationInstance

        if (currentAnimationInstance != null) {
            val currentAnimation = animationHolder.currentAnimation!!
            if (currentAnimationInstance.animation.name == name && currentAnimationInstance.animation.loop) return null

            val canInterrupt = (currentAnimation.interruptable) || force
            if (!canInterrupt && !currentAnimationInstance.isFinished) return null

            currentAnimationInstance.finish()
        }

        entity.getOrNull(Debug)?.addMessage("New Anim: $name", 1.0F)

        val animations = entity[Animations]

        animationHolder.currentAnimationInstance
            ?.let(animations::removeAnimation)

        animationHolder.currentAnimation = animationHolder.spriteAnimationSet.value.animations
            .firstOrNull { it.value.name == name }?.value
            ?: error("Animation $name not found in animation set ${animationHolder.spriteAnimationSet.value.name}")

        animationHolder.currentAnimationInstance =
            animationHolder.currentAnimation!!.toAnimationInstance()

        animations.animations += animationHolder.currentAnimationInstance!!
        return animationHolder.currentAnimationInstance!!
    }

    override fun onTick() {
        spriteBatch.use(gameScreen.camera) {
            super.onTick()
        }
    }

    override fun onTickEntity(entity: Entity) {
        val sprite = entity[SpriteComponent]
        val texture = entity[AnimationHolder].currentAnimationInstance!!.currentFrame.data
        sprite.texture = texture
    }

    private fun SpriteAnimation.toAnimationInstance() =
        AnimationInstance(this.animation)
}
