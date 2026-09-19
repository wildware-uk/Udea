package dev.wildware.udea.core.spatial

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.annotations.Net
import dev.wildware.udea.annotations.PositionHandle
import dev.wildware.udea.annotations.Replicated
import dev.wildware.udea.annotations.RotationHandle
import dev.wildware.udea.annotations.ScaleHandle
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.ReplicatedComponentType
import dev.wildware.udea.core.snapshot.fleksComponentType
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
 * ## Replicated, every field on the wire
 *
 * `@Replicated` (issue #237), which generates the `Replicator` the agent and editor tools read and
 * write a component's fields through, so the editor's gizmos can move, turn and scale a model
 * through `editor.*` edit sessions like any other edit. A game puts [snapshotType] in its
 * `ComponentRegistry` to have it captured, rewound and replicated.
 *
 * Every field is `@Net` (issue #246), so a client sees a 3D entity move. Position and heading are
 * what change tick to tick. Pitch, roll and scale are on the wire as well because a client applies
 * a component through its `allMask`: a field that never reached it is written from a column it
 * never filled, and a `@Sim` scale arrives as `0` - a model drawn at no size at all. A delta carries
 * only the fields that changed, so a scale that never changes costs its bits once, in the create.
 *
 * `@Serializable`, so a level file saves it the way it saves `PhysicsBody`.
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
    @Net public var x: Float = 0f,
    @Net public var y: Float = 0f,
    /** Height above the ground plane. */
    @Net public var z: Float = 0f,
    /** Radians about X, applied first. */
    @Net public var rotationX: Float = 0f,
    /** Radians about Y, applied second. */
    @Net public var rotationY: Float = 0f,
    /** Radians about Z (the heading), applied last. */
    @Net public var rotationZ: Float = 0f,
    @Net public var scaleX: Float = 1f,
    @Net public var scaleY: Float = 1f,
    @Net public var scaleZ: Float = 1f,
) : Component<Transform3D> {

    override fun type(): ComponentType<Transform3D> = Transform3D

    override fun toString(): String =
        "Transform3D(($x, $y, $z) rot=($rotationX, $rotationY, $rotationZ) scale=($scaleX, $scaleY, $scaleZ))"

    public companion object : ComponentType<Transform3D>() {

        /**
         * The snapshot registration for a game's `ComponentRegistry`, which is what makes a
         * snapshot, a rewind, a replay hash and replication see a [Transform3D] at all: capture
         * walks the registry, and a component left out of it is invisible rather than partly
         * captured. Built fresh per call, like `Animator.snapshotType()`.
         *
         * Nine floats, in the generated replicator's order - the names sorted: `rotationX`,
         * `rotationY`, `rotationZ`, `scaleX`, `scaleY`, `scaleZ`, `x`, `y`, `z`.
         */
        public fun snapshotType(): ReplicatedComponentType<Transform3D> = fleksComponentType(
            Transform3DReplicator,
            ComponentSchema.of(Transform3DReplicator, "Transform3D", List(FIELD_COUNT) { FieldKind.Float }),
            Transform3D,
        ) { Transform3D() }

        private const val FIELD_COUNT: Int = 9
    }
}
