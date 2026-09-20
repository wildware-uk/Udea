package dev.wildware.hollow.net

import dev.wildware.hollow.Player
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.spatial.Animator
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
 * `Scenery` and `Sunlight` are therefore not `@Replicated`. [dev.wildware.hollow.Player] is: issue
 * #250 is the ticket this file's H1 note said would have to make that wire decision, and it made
 * it. `dev.wildware.hollow.Player` sorts ahead of every `moba` name in `net-components.lock`, so
 * every id in that file moved up by one and `moba`'s two checked-in `.udearep` fixtures were
 * regenerated in the same change.
 *
 * ## What is captured but not sent, and what is neither
 *
 * `Animator` is here because a client has to play the clip the server is playing, from the tick the
 * server started it (issue #241); every field of it is `@Net`.
 *
 * The physics components are **not** here, and that is a gap rather than a decision to leave alone.
 * `Physics2DModule`'s own KDoc asks a game to add `PhysicsSnapshotTypes.all()` to its registry "or a
 * rewind cannot see a body at all", and this registry is also the replication protocol: a component
 * whose every field is `@Sim` is packed by nobody and applied by a client through its `allMask`, so
 * a client would write a zeroed `PhysicsBody` over its own. Hollow reaches no rewind today - it has
 * no agent surface until issue #255, and nothing else calls `time.rewind` - so the cost is nil and
 * the risk of guessing is not. The hardening ticket, which is where replay and rewind arrive, is
 * where the two halves get told apart properly.
 */
public object HollowNet {

    /** The port a server binds when none is named. Off moba's 27015, so both can run on one machine. */
    public const val DEFAULT_PORT: Int = 27025

    /**
     * The registry both ends of a session share, and the game's snapshot ring captures: every
     * `@Replicated` component Hollow's world holds.
     */
    internal fun registry(): ComponentRegistry = ComponentRegistry(
        listOf(
            Player.snapshotType(),
            Animator.snapshotType(),
            Transform3D.snapshotType(),
        ),
    )

    /**
     * This build's protocol, derived from [registry]: a server and a client built from different
     * sources refuse each other by name at connect rather than misread each other's bytes.
     */
    public fun protocol(registry: ComponentRegistry = registry()): ProtocolDescriptor = ProtocolDescriptor.of(registry)
}
