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
import dev.wildware.udea.game
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

    fun setAnimation(entity: Entity, name: String) {
        val animationSet = entity[AnimationSet]

        if (animationSet.currentAnimation?.animation?.name == name) return

        val animations = entity[Animations]

        animationSet.currentAnimation
            ?.let(animations::removeAnimation)

        animationSet.currentAnimation = animationSet.spriteAnimationSet.animations
            .first { it.name == name }
            .toAnimationInstance(animationSet)

        animations.animations += animationSet.currentAnimation!!
    }

    override fun onTick() {
        spriteBatch.use(game.camera) {
            super.onTick()
        }
    }

    override fun onTickEntity(entity: Entity) {
        val sprite = entity[SpriteComponent]
        val texture = entity[AnimationSet].currentAnimation!!.currentFrame.data
        sprite.texture = texture
    }

    private fun SpriteAnimation.toAnimationInstance(animationSet: AnimationSet): AnimationInstance<out TextureRegion> {
        val instance = AnimationInstance( // TODO is this a bad idea?
            animation(
                name = this.name,
                loop = true,
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
