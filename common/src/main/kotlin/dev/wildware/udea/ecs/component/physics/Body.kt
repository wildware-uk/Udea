package dev.wildware.udea.ecs.component.physics

import com.badlogic.gdx.physics.box2d.BodyDef
import com.badlogic.gdx.physics.box2d.BodyDef.BodyType.StaticBody
import com.fasterxml.jackson.annotation.JsonIgnore
import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.ecs.component.configureNetwork
import dev.wildware.udea.ecs.component.delegate
import dev.wildware.udea.ecs.system.Box2DSystem
import dev.wildware.udea.network.UdeaNetworked
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import ktx.box2d.body
import com.badlogic.gdx.physics.box2d.Body as Box2DBody

/**
 * Represents the physical properties of an entity in the game world.
 * This component defines how an entity interacts with the physics system.
 *
 * @property type The physical behavior type of the body, defaults to [BodyDef.BodyType.StaticBody]
 */
@Serializable
@UdeaNetworked
data class Body(
    val type: BodyDef.BodyType = StaticBody,
    val linearDamping: Float = 0.0F,
    val angularDamping: Float = 0.0F,
    val fixedRotation: Boolean = false,
    val gravityScale: Float = 1.0F,
) : Component<Body> {
    @Transient
    @JsonIgnore
    lateinit var body: Box2DBody

    @Transient
    val touching = mutableListOf<Entity>()

    val touchingCount: Int
        get() = touching.size

    val grounded: Boolean
        get() = touchingCount > 0

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


    // TODO is this not being serialized?
    companion object : UdeaComponentType<Body>(
        networkComponent = configureNetwork(
            delegates = {
                delegate { body.linearVelocity }
                delegate { body.angularVelocity }
            }
        )
    )
}
