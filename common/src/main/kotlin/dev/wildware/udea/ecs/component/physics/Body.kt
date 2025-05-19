package dev.wildware.udea.ecs.component.physics

import com.badlogic.gdx.physics.box2d.BodyDef
import com.fasterxml.jackson.annotation.JsonIgnore
import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.ecs.system.Box2DSystem
import ktx.box2d.body
import com.badlogic.gdx.physics.box2d.Body as Box2DBody

/**
 * Represents the physical properties of an entity in the game world.
 * This component defines how an entity interacts with the physics system.
 *
 * @property type The physical behavior type of the body, defaults to [BodyDef.BodyType.StaticBody]
 */
data class Body(
    val type: BodyDef.BodyType = BodyDef.BodyType.StaticBody,
    val linearDamping: Float = 0.0F,
    val angularDamping: Float = 0.0F,
    val fixedRotation: Boolean = false,
    val gravityScale: Float = 1.0F,
) : Component<Body> {
    @Transient
    @JsonIgnore
    lateinit var body: Box2DBody

    override fun type() = Body

    override fun World.onAdd(entity: Entity) {
        body = system<Box2DSystem>().box2DWorld.body(type) {
            linearDamping = this@Body.linearDamping
            angularDamping = this@Body.angularDamping
            fixedRotation = this@Body.fixedRotation
            gravityScale = this@Body.gravityScale
        }
    }

    override fun World.onRemove(entity: Entity) {
        system<Box2DSystem>().box2DWorld.destroyBody(body)
    }

    companion object : UdeaComponentType<Body>()
}
