package dev.wildware.udea.ecs.physics

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.ecs.component.UdeaComponentType
import com.badlogic.gdx.physics.box2d.Body as Box2DBody

/**
 * Represents the physical properties of an entity in the game world.
 * This component defines how an entity interacts with the physics system.
 *
 * @property type The physical behavior type of the body, defaults to [BodyType.Static]
 */
data class Body(
    val type: BodyType = BodyType.Static,
    val linearDamping: Float = 0.0F,
    val angularDamping: Float = 0.0F,
    val fixedRotation: Boolean = false,
    val gravityScale: Float = 1.0F,
) : Component<Body> {
    lateinit var body: Box2DBody
    override fun type() = Body

    override fun World.onAdd(entity: Entity) {
//        body = system<Box2DSystem>().box2DWorld.createBody(bodyDef)
    }

    override fun World.onRemove(entity: Entity) {
//        system<Box2DSystem>().box2DWorld.destroyBody(body)
    }

    companion object : UdeaComponentType<Body>()
}

/**
 * Defines the different types of physical bodies and their behavior in the physics system.
 */
enum class BodyType {
    /** A body that does not move and is not affected by forces */
    Static,

    /** A body that is fully simulated by physics and affected by forces */
    Dynamic,

    /** A body that can be moved programmatically but is not affected by physics forces */
    Kinematic
}
