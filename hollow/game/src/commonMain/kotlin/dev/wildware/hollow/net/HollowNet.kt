package dev.wildware.hollow.net

import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.spatial.Transform3D
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
 */
public object HollowNet {

    /** The port a server binds when none is named. Off moba's 27015, so both can run on one machine. */
    public const val DEFAULT_PORT: Int = 27025

    /**
     * The registry both ends of a session share, and the game's snapshot ring captures: every
     * `@Replicated` component Hollow's world holds.
     */
    internal fun registry(): ComponentRegistry = ComponentRegistry(listOf(Transform3D.snapshotType()))

    /**
     * This build's protocol, derived from [registry]: a server and a client built from different
     * sources refuse each other by name at connect rather than misread each other's bytes.
     */
    public fun protocol(registry: ComponentRegistry = registry()): ProtocolDescriptor = ProtocolDescriptor.of(registry)
}
