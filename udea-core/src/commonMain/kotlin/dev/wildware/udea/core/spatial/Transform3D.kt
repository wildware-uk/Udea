package dev.wildware.udea.core.spatial

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import kotlinx.serialization.Serializable

/**
 * Where an entity sits in a 3D world: position, rotation and scale, as plain floats.
 *
 * ## Z is up
 *
 * The 3D world's ground plane is `z = 0`, and it is the same plane a 2D game already lives on:
 * a 2D position `(x, y)` is the 3D point `(x, y, 0)`, and a 2D angle is a turn about Z. That is
 * what lets `udea-render`'s model renderer draw an entity that has only a 2D position, and it is
 * why [rotationZ] is the "heading" here the way `PhysicsBody.angle` is in 2D.
 *
 * ## Why floats and not a vector
 *
 * The precedent is `PhysicsBody`'s `x`, `y` and `angle`. A component is read every frame for every
 * entity; a vector object per field is an allocation on every write and an indirection on every
 * read, and it would have to be a renderer's vector type to be useful to one - which is exactly
 * the type `udea-core` must not name.
 *
 * ## What it is not
 *
 * Not replicated: `udea-core` has no `@Replicated` component, and the first one would move the
 * wire contract for a picture. `@Serializable`, so a level file saves it the way it saves
 * `PhysicsBody`.
 */
@Serializable
public class Transform3D(
    public var x: Float = 0f,
    public var y: Float = 0f,
    /** Height above the ground plane. */
    public var z: Float = 0f,
    /** Radians about X, applied first. */
    public var rotationX: Float = 0f,
    /** Radians about Y, applied second. */
    public var rotationY: Float = 0f,
    /** Radians about Z (the heading), applied last. */
    public var rotationZ: Float = 0f,
    public var scaleX: Float = 1f,
    public var scaleY: Float = 1f,
    public var scaleZ: Float = 1f,
) : Component<Transform3D> {

    override fun type(): ComponentType<Transform3D> = Transform3D

    override fun toString(): String =
        "Transform3D(($x, $y, $z) rot=($rotationX, $rotationY, $rotationZ) scale=($scaleX, $scaleY, $scaleZ))"

    public companion object : ComponentType<Transform3D>()
}
