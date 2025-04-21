package dev.wildware.udea.ecs.physics

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType

/**
 * Represents the physical properties of an entity in the game world.
 * This component defines how an entity interacts with the physics system.
 *
 * @property type The physical behavior type of the body, defaults to [BodyType.Static]
 */
data class Body(
    val type: BodyType = BodyType.Static
) : Component<Body> {
    override fun type() = Body

    companion object : ComponentType<Body>()
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
