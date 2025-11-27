package dev.wildware.udea.assets

import com.badlogic.gdx.physics.box2d.BodyDef
import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.EntityTag
import dev.wildware.udea.ability.AttributeSet
import dev.wildware.udea.assets.dsl.ListBuilder
import dev.wildware.udea.dsl.CreateDsl
import dev.wildware.udea.ecs.component.ability.abilities
import dev.wildware.udea.ecs.component.ai.PathfindingStyle
import dev.wildware.udea.ecs.component.ai.agent
import dev.wildware.udea.ecs.component.animation.animationMapHolder
import dev.wildware.udea.ecs.component.animation.animations
import dev.wildware.udea.ecs.component.base.networkable
import dev.wildware.udea.ecs.component.control.characterController
import dev.wildware.udea.ecs.component.physics.body
import dev.wildware.udea.ecs.component.physics.capsule
import dev.wildware.udea.ecs.component.render.animationHolder
import dev.wildware.udea.ecs.component.render.particleEffect
import dev.wildware.udea.ecs.component.render.spriteRenderer

fun ListBuilder<in Blueprint>.character(
    name: String,
    spriteAnimationSet: AssetReference<SpriteAnimationSet>,
    animations: CharacterAnimationMap,
    size: CharacterSize,
    attributeSet: () -> AttributeSet,
    components: LazyList<Component<out Any>> = emptyLazyList(),
    tags: List<EntityTag> = emptyList()
) {
    add(dev.wildware.udea.assets.character(name, spriteAnimationSet, animations, size, attributeSet, components, tags))
}

/**
 * Creates a blueprint for a character.
 * */
fun character(
    name: String,
    spriteAnimationSet: AssetReference<SpriteAnimationSet>,
    animations: CharacterAnimationMap,
    size: CharacterSize,
    attributeSet: () -> AttributeSet,
    components: LazyList<Component<out Any>> = emptyLazyList(),
    tags: List<EntityTag> = emptyList()
) = Blueprint(
    components = lazy {
        spriteRenderer()
        animationHolder(
            spriteAnimationSet = spriteAnimationSet,
            defaultAnimation = animations.idle
        )

        body(
            type = BodyDef.BodyType.DynamicBody,
            linearDamping = 5.0F,
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
        animationMapHolder(animations)
        components().forEach { add(it) }
    },

    tags = tags
).apply {
    this.name = name
}

/**
 * Set of animations for a character.
 * */
@CreateDsl(name = "characterAnimations")
open class CharacterAnimationMap(
    val walk: String,
    val run: String,
    val idle: String,
    val death: String,
) : AnimationMap

@CreateDsl
data class CharacterSize(
    val width: Float,
    val height: Float,
)