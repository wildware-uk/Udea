package dev.wildware.udea.core.physics

import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.ReplicatedComponentType
import dev.wildware.udea.core.snapshot.fleksComponentType

/**
 * The snapshot registrations for the physics components, for a game's `ComponentRegistry`.
 *
 * A game that installs a solver appends [all] to the list it builds its registry from, the way
 * `moba` appends its lane's types. Capture walks the registry, so a physics component left out
 * of it is not partly captured, it is invisible: a rewind would put every body back where the
 * future left it, and `SnapshotCoverage` would name the component.
 *
 * `Chain` is absent because it has no `Replicator` - see its KDoc for why that is safe only for
 * static geometry.
 *
 * ## The field kinds, written out
 *
 * `ComponentSchema.of` refuses a list whose length disagrees with `fieldNames`, but a kind typed
 * wrong at the right length is caught by nothing except a round trip. So each list below is in
 * the generated replicator's order, which is the lowered field names sorted ascending:
 *
 * | Component | Fields |
 * |---|---|
 * | `Box` | `halfHeight`, `halfWidth` |
 * | `Capsule` | `halfHeight`, `radius` |
 * | `Circle` | `radius` |
 * | `PhysicsBody` | `angle`, `angularVelocity`, `awake`, `isSensor`, `kind`, `linearX`, `linearY`, `x`, `y` |
 * | `Teleport` | `angle`, `x`, `y` |
 */
public object PhysicsSnapshotTypes {

    /** One registration per `@Replicated` physics component. Built fresh per call; call it once. */
    public fun all(): List<ReplicatedComponentType<*>> = listOf(
        fleksComponentType(
            BoxReplicator,
            ComponentSchema.of(BoxReplicator, "Box", listOf(FieldKind.Float, FieldKind.Float)),
            Box,
        ) { Box() },
        fleksComponentType(
            CapsuleReplicator,
            ComponentSchema.of(CapsuleReplicator, "Capsule", listOf(FieldKind.Float, FieldKind.Float)),
            Capsule,
        ) { Capsule() },
        fleksComponentType(
            CircleReplicator,
            ComponentSchema.of(CircleReplicator, "Circle", listOf(FieldKind.Float)),
            Circle,
        ) { Circle() },
        fleksComponentType(
            PhysicsBodyReplicator,
            ComponentSchema.of(
                PhysicsBodyReplicator,
                "PhysicsBody",
                listOf(
                    FieldKind.Float,
                    FieldKind.Float,
                    FieldKind.Bool,
                    FieldKind.Bool,
                    FieldKind.Int,
                    FieldKind.Float,
                    FieldKind.Float,
                    FieldKind.Float,
                    FieldKind.Float,
                ),
            ),
            PhysicsBody,
        ) { PhysicsBody() },
        fleksComponentType(
            TeleportReplicator,
            ComponentSchema.of(
                TeleportReplicator,
                "Teleport",
                listOf(FieldKind.Float, FieldKind.Float, FieldKind.Float),
            ),
            Teleport,
        ) { Teleport() },
    )
}
