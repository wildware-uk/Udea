package com.example.newgame.sim

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.annotations.Net
import dev.wildware.udea.annotations.Replicated

/**
 * A thing that drives across the ground: where it is, and how fast.
 *
 * A Fleks component, which is all a piece of simulation state has to be - plus the one
 * annotation that makes it travel.
 *
 * ## `@Replicated` is a wire decision, and it has a file
 *
 * `@Replicated` generates a `RoverReplicator`: one codec that serves delta replication to a
 * client, snapshot capture for `time.rewind`, snapshot restore, and the agent's field access.
 * Every field of it is `@Net` here, so a client sees a rover where the server put it and at the
 * speed the server gave it.
 *
 * The id that codec stamps on the wire is **the position of this class's name in
 * `net-components.lock`**, in this repository's root. That file is the build's whole id space:
 * inserting a name renumbers every name after it, and every connected client and recorded replay
 * with it - which is why it is a reviewed file rather than something a processor counts out.
 *
 * Nothing has to be written into a build script for that to work. The engine's conventions read
 * the file and hand the list to the processor; `gradlew udeaWriteNetComponents` writes the file
 * itself the first time, and again whenever a component is added. See `docs/new-game.md`.
 */
@Replicated
public class Rover(
    /** Metres east of the origin. */
    @Net public var x: Float = 0f,
    /** Metres north of the origin. */
    @Net public var y: Float = 0f,
    /** Metres per second, east. */
    @Net public var speed: Float = 1f,
) : Component<Rover> {

    override fun type(): ComponentType<Rover> = Rover

    override fun toString(): String = "Rover(x=$x, y=$y, speed=$speed)"

    /** Fleks' handle for this component. */
    public companion object : ComponentType<Rover>()
}
