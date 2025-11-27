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
import dev.wildware.udea.assets.animation
import dev.wildware.udea.assets.frame
import dev.wildware.udea.ecs.UdeaSystem
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Editor
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Game
import dev.wildware.udea.ecs.component.animation.Animations
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.ecs.component.render.AnimationSet
import dev.wildware.udea.gameScreen
import dev.wildware.udea.use
import dev.wildware.udea.ecs.component.render.SpriteRenderer as SpriteComponent

@UdeaSystem(runIn = [Editor, Game])
class AnimationSetSystem(
    val spriteBatch: SpriteBatch = inject()
) : IteratingSystem(
    family { all(Transform, AnimationSet, Animations, SpriteComponent) }
), FamilyOnAdd {

    override fun onAddEntity(entity: Entity) {
        val animationSet = entity[AnimationSet]

        if (animationSet.defaultAnimation != null) {
            setAnimation(entity, animationSet.defaultAnimation)
        }
    }

    fun setAnimation(entity: Entity, name: String, force: Boolean = false): AnimationInstance<out TextureRegion>? {
        val animationSet = entity[AnimationSet]
        val currentAnimationInstance = animationSet.currentAnimationInstance

        if (currentAnimationInstance != null) {
            val currentAnimation = animationSet.currentAnimation!!
            if (currentAnimationInstance.animation.name == name && currentAnimationInstance.animation.loop) return null

            val canInterrupt = (currentAnimation.interruptable) || force
            if (!canInterrupt && !currentAnimationInstance.isFinished) return null

            currentAnimationInstance.finish()
        }

        val animations = entity[Animations]

        animationSet.currentAnimationInstance
            ?.let(animations::removeAnimation)

        animationSet.currentAnimation = animationSet.spriteAnimationSet.animations
            .firstOrNull { it.name == name }
            ?: error("Animation $name not found in animation set ${animationSet.spriteAnimationSet.name}")

        animationSet.currentAnimationInstance = animationSet.currentAnimation!!.toAnimationInstance(animationSet)

        animations.animations += animationSet.currentAnimationInstance!!
        return animationSet.currentAnimationInstance!!
    }

    override fun onTick() {
        spriteBatch.use(gameScreen.camera) {
            super.onTick()
        }
    }

    override fun onTickEntity(entity: Entity) {
        val sprite = entity[SpriteComponent]
        val texture = entity[AnimationSet].currentAnimationInstance!!.currentFrame.data
        sprite.texture = texture
    }

    private fun SpriteAnimation.toAnimationInstance(animationSet: AnimationSet): AnimationInstance<out TextureRegion> {
        val instance = AnimationInstance( // TODO is this a bad idea?
            animation(
                name = this.name,
                loop = this.loop,
                frames = {
                    var nextFrame = 0.0F

                    spriteSheet.forEachIndexed { i, it ->

                        frame(
                            nextFrame,
                            it,
                            name = this@toAnimationInstance.notifies.find { it.frame == i }?.name
                        )
                        nextFrame += animationSet.frameTime
                    }
                })
        )

        return instance
    }
}
