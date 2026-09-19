package dev.wildware.udea.core.spatial

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.annotations.PositionHandle
import dev.wildware.udea.annotations.Replicated
import dev.wildware.udea.annotations.RotationHandle
import dev.wildware.udea.annotations.ScaleHandle
import dev.wildware.udea.annotations.Sim
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
 * ## Replicated, and never on the wire
 *
 * `@Replicated` with every field `@Sim` (issue #237), the way `PhysicsBody` is: that generates the
 * `Replicator` the agent and editor tools read and write a component's fields through, so the
 * editor's gizmos can move, turn and scale a model through `editor.*` edit sessions like any other
 * edit. A delta write considers only the `@Net` fields, and there are none, so nothing of it is ever
 * sent to a client: the wire carries no picture. It does take a component id in `udea-core`'s
 * `net-protocol.lock`. `@Serializable`, so a level file saves it the way it saves `PhysicsBody`.
 *
 * ## Its gizmos
 *
 * The handle annotations give it the editor's built-in 3D translate arrows and plane squares, one
 * rotation ring per angle, and the scale boxes, generated into each game's `editor` source set.
 *
 * Every field is named, defaults included, because this module's KSP run reads the annotations
 * through `udea-annotations`' common metadata, where KSP reports no parameter defaults: a handle
 * that relied on one fails `udea-core`'s build with "KSP reported neither a value nor a default".
 */
@Serializable
@Replicated
@PositionHandle(x = "x", y = "y", z = "z")
@RotationHandle(rotation = "rotationZ", aboutX = "rotationX", aboutY = "rotationY")
@ScaleHandle(x = "scaleX", y = "scaleY", z = "scaleZ")
public class Transform3D(
    @Sim public var x: Float = 0f,
    @Sim public var y: Float = 0f,
    /** Height above the ground plane. */
    @Sim public var z: Float = 0f,
    /** Radians about X, applied first. */
    @Sim public var rotationX: Float = 0f,
    /** Radians about Y, applied second. */
    @Sim public var rotationY: Float = 0f,
    /** Radians about Z (the heading), applied last. */
    @Sim public var rotationZ: Float = 0f,
    @Sim public var scaleX: Float = 1f,
    @Sim public var scaleY: Float = 1f,
    @Sim public var scaleZ: Float = 1f,
) : Component<Transform3D> {

    override fun type(): ComponentType<Transform3D> = Transform3D

    override fun toString(): String =
        "Transform3D(($x, $y, $z) rot=($rotationX, $rotationY, $rotationZ) scale=($scaleX, $scaleY, $scaleZ))"

    public companion object : ComponentType<Transform3D>()
}
