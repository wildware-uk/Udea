package dev.wildware.hollow.net

import dev.wildware.udea.core.replication.FieldMask
import dev.wildware.udea.core.replication.Replicator
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.ReplicatedComponentType
import dev.wildware.udea.core.snapshot.fleksComponentType
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.core.spatial.Transform3DReplicator
import dev.wildware.udea.net.wire.ProtocolDescriptor

/**
 * The one place Hollow and `udea-net` are joined: what the server and every client replicate, and
 * the protocol both ends derive from it (issue #249; `MobaNet` is moba's).
 *
 * ## What replicates, and what does not
 *
 * The server is the authority for every entity with a `Transform3D`, which is every entity Hollow
 * has. What a client needs to *draw* a prop - which [dev.wildware.hollow.Prop] it is, and how the
 * level is lit - is level content: `clearing.udealevel` ships with the game and every machine loads
 * it, the way a map file is on every machine, so a client stands the static clearing up itself and
 * replication binds the server's entities onto it by `NetId`. Anything the server spawns at run time
 * - the players and creatures of later tickets - reaches a client through replication alone.
 *
 * `Scenery` and `Sunlight` are therefore not `@Replicated`, and nothing here adds a name to
 * `net-components.lock`. A Hollow name there sorts ahead of every `moba` name, renumbering moba's
 * wire ids and invalidating its recorded replays; that is a wire decision for the first ticket that
 * needs a Hollow component on the wire (issue #250's player), not for a static clearing.
 *
 * ## `Transform3D` on the wire before issue #246
 *
 * On `master` every `Transform3D` field is `@Sim` (#237), so its generated replicator's `netMask` is
 * empty, and `udea-net` writes no record at all for an entity whose components have nothing to say
 * - a server holding only `Transform3D` entities sends every client an empty section, and nothing
 * the server does reaches anybody. Issue #246 makes all nine fields `@Net` in `udea-core`. Until it
 * lands, Hollow's registry carries [WireTransform3D], the generated replicator with its `netMask`
 * widened to all nine fields - the mask #246 gives it - so the session works now and the wire format
 * does not change when #246 arrives. The `netMask` is in each component's protocol hash, so a peer
 * built with and one built without this refuse each other at connect rather than misread a packet.
 * When #246 merges this object goes, and [transform3D] becomes `Transform3D.snapshotType()`.
 */
public object HollowNet {

    /** The port a server binds when none is named. Off moba's 27015, so both can run on one machine. */
    public const val DEFAULT_PORT: Int = 27025

    /**
     * The registry both ends of a session share, and the game's snapshot ring captures: every
     * `@Replicated` component Hollow's world holds.
     */
    public fun registry(): ComponentRegistry = ComponentRegistry(listOf(transform3D()))

    /**
     * This build's protocol, derived from [registry]: a server and a client built from different
     * sources refuse each other by name at connect rather than misread each other's bytes.
     */
    public fun protocol(registry: ComponentRegistry = registry()): ProtocolDescriptor = ProtocolDescriptor.of(registry)

    /**
     * `Transform3D`'s place in the registry: its generated replicator, nine float columns, and
     * Fleks' own accessors. `Animator.snapshotType()` is the same thing for `Animator`; issue #246
     * gives `Transform3D` one of its own, and this is replaced by it when that lands.
     */
    private fun transform3D(): ReplicatedComponentType<Transform3D> = fleksComponentType(
        WireTransform3D,
        ComponentSchema.of(WireTransform3D, "Transform3D", List(Transform3DReplicator.FIELD_COUNT) { FieldKind.Float }),
        Transform3D,
    ) { Transform3D() }

    /** `Transform3DReplicator` with every field on the wire: see "`Transform3D` on the wire before issue #246". */
    private object WireTransform3D : Replicator<Transform3D> by Transform3DReplicator {
        override val netMask: FieldMask = Transform3DReplicator.allMask
    }
}
