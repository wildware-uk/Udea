package dev.wildware.udea.assets

import com.badlogic.gdx.physics.box2d.BodyDef
import com.github.quillraven.fleks.Component
import dev.wildware.udea.ability.AttributeSet
import dev.wildware.udea.assets.dsl.ListBuilder
import dev.wildware.udea.dsl.CreateDsl
import dev.wildware.udea.ecs.component.ability.abilities
import dev.wildware.udea.ecs.component.ai.*
import dev.wildware.udea.ecs.component.animation.animations
import dev.wildware.udea.ecs.component.animation.characterAnimationController
import dev.wildware.udea.ecs.component.base.networkable
import dev.wildware.udea.ecs.component.control.characterController
import dev.wildware.udea.ecs.component.physics.body
import dev.wildware.udea.ecs.component.physics.capsule
import dev.wildware.udea.ecs.component.render.animationSet
import dev.wildware.udea.ecs.component.render.particleEffect
import dev.wildware.udea.ecs.component.render.spriteRenderer

fun ListBuilder<in Blueprint>.character(
    name: String,
    animations: CharacterAnimations,
    size: CharacterSize,
    attributeSet: ()->AttributeSet,
    components: LazyList<Component<out Any>> = emptyLazyList()
) {
    add(dev.wildware.udea.assets.character(name, animations, size, attributeSet, components))
}

/**
 * Creates a blueprint for a character.
 * */
fun character(
    name: String,
    animations: CharacterAnimations,
    size: CharacterSize,
    attributeSet: ()->AttributeSet,
    components: LazyList<Component<out Any>> = emptyLazyList()
) = Blueprint(
    components = lazy {
        spriteRenderer()
        animationSet(
            spriteAnimationSet = animations.animationSet,
            defaultAnimation = animations.idle,
            frameTime = animations.animationSpeed,
        )

        body(
            type = BodyDef.BodyType.DynamicBody,
            linearDamping = 1.0F,
            fixedRotation = true,
        )

        capsule(
            width = size.width,
            height = size.height,
            friction = 1F,
        )

        agent(
            pathfindingStyle = PathfindingStyle.Walk
        )
        networkable()
        abilities(attributeSet())
        particleEffect()
        animations()
        characterController()
        characterAnimationController(animations)
        components().forEach { add(it) }
    }
).apply {
    this.name = name
}

@CreateDsl
data class CharacterAnimations(
    val animationSet: AssetReference<SpriteAnimationSet>,
    val walk: String,
    val run: String,
    val idle: String,
    val death: String,
    val animationSpeed: Float = 0.1F,
)

@CreateDsl
data class CharacterSize(
    val width: Float,
    val height: Float,
)