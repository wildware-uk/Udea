package dev.wildware.moba

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.annotations.Net
import dev.wildware.udea.annotations.Replicated
import dev.wildware.udea.annotations.Sim

/**
 * Where a unit is, and how healthy it is.
 *
 * `moba` is nearly empty and this is deliberately the whole of its state: three floats is
 * enough for the snapshot ring to have something to restore, for `set_component_field` to have
 * something to write, and for a rewind to be observable from outside the process. It is not a
 * design for a MOBA; it is the smallest component that makes the engine's own claims checkable
 * from a running game rather than from a test.
 */
@Replicated
public class Position(
    /** World x. Agent-writable. */
    @Net(agentWritable = true) public var x: Float = 0f,
    /** World y. Agent-writable. */
    @Net(agentWritable = true) public var y: Float = 0f,
    /**
     * Hit points. Snapshotted but never replicated, and deliberately **not** agent-writable, so
     * `field_not_writable` stays reachable from a live instance.
     */
    @Sim public var hp: Float = 100f,
) : Component<Position> {

    override fun type(): ComponentType<Position> = Position

    override fun toString(): String = "Position($x, $y, hp=$hp)"

    /** Fleks' handle for this component. */
    public companion object : ComponentType<Position>()
}
