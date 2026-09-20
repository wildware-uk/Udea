package dev.wildware.udea.core.spatial

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.annotations.Net
import dev.wildware.udea.annotations.Replicated
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.ReplicatedComponentType
import dev.wildware.udea.core.snapshot.fleksComponentType
import kotlinx.serialization.Serializable

/**
 * A part mounted on a named node of another entity's model: a turret in a chassis's roof socket,
 * a wheel on its axle, a gun on the turret's own socket (issue #260).
 *
 * The part is an ordinary entity with its own [Transform3D] and its own model.
 * [AttachmentSystem] writes that transform every tick from the parent's, so the part rides the
 * chassis as it drives and turns, and everything that reads a world position - gameplay, the
 * renderer, an agent tool, a level file - reads the one it always read.
 *
 * ```
 * // mount
 * turret.configure { it += AttachedTo(chassis, Chassis.Nodes.socket_roof) }
 * // swap: the same socket, a different part
 * hijacked.configure { it += AttachedTo(jeep, Jeep.Nodes.socket_wheel_fl) }
 * // detach - an explosion - leaves a free entity where the part was
 * turret.configure { it -= AttachedTo }
 * ```
 *
 * ## The parent is a [NetId]
 *
 * Never a Fleks `Entity`: an entity is an index into one world's tables, and this crosses
 * snapshots, packets and tool calls, which is exactly what spec 5's entity identity contract is
 * about. A parent that cannot be resolved - destroyed, or not yet relevant to this client -
 * leaves the part where it last stood; see [AttachmentSystem].
 *
 * ## What the node fields are
 *
 * [node] is the node's index in the parent model's file, which is what the renderer resolves the
 * live, animated node with, and `node*` is that node's rest transform, copied off the
 * [ModelNode] the part was mounted with. The simulation composes the rest transform, so a socket
 * on an animated bone is answered at the bone's rest place to gameplay code while the picture
 * draws it on the moving bone - the renderer reads the animated node, which the simulation has
 * no keyframes for and cannot (issue #261 is where rigid node animation lands).
 *
 * The `offset*` fields are the part's own place within the socket, applied after it: a muzzle
 * pushed 0.2 forward, a wheel turned to face outwards.
 *
 * ## Replicated
 *
 * Every field is `@Net`, so a client mounts the same part on the same socket with the same
 * offset, and a snapshot, a rewind and a replay carry the mount. Nothing here changes tick to
 * tick, so a delta carries the fields once, in the create, and nothing afterwards.
 *
 * `@Serializable`, so a level file saves an assembled unit the way it saves anything else.
 */
@Serializable
@Replicated
public class AttachedTo(
    /** The entity this part is mounted on. */
    @Net public var parent: NetId = NetId.NONE,
    /** [ModelNode.index] of the socket in the parent's model, or [ModelNode.NONE]. */
    @Net public var node: Int = ModelNode.NONE,
    @Net public var nodeX: Float = 0f,
    @Net public var nodeY: Float = 0f,
    @Net public var nodeZ: Float = 0f,
    @Net public var nodeQx: Float = 0f,
    @Net public var nodeQy: Float = 0f,
    @Net public var nodeQz: Float = 0f,
    @Net public var nodeQw: Float = 1f,
    @Net public var nodeScaleX: Float = 1f,
    @Net public var nodeScaleY: Float = 1f,
    @Net public var nodeScaleZ: Float = 1f,
    /** The part's own place in the socket, applied after it. */
    @Net public var offsetX: Float = 0f,
    @Net public var offsetY: Float = 0f,
    @Net public var offsetZ: Float = 0f,
    /** Radians about the socket's X, applied first, as [Transform3D]'s angles are. */
    @Net public var offsetRotationX: Float = 0f,
    @Net public var offsetRotationY: Float = 0f,
    @Net public var offsetRotationZ: Float = 0f,
) : Component<AttachedTo> {

    /**
     * Mounts on [node] of [parent], with an optional offset within the socket.
     *
     * The node's rest transform is copied in rather than referenced, because this component goes
     * on the wire and into snapshots and the generated `ModelNode` does not: see [ModelNode].
     */
    public constructor(
        parent: NetId,
        node: ModelNode,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        offsetZ: Float = 0f,
        offsetRotationX: Float = 0f,
        offsetRotationY: Float = 0f,
        offsetRotationZ: Float = 0f,
    ) : this(
        parent = parent,
        node = node.index,
        nodeX = node.x,
        nodeY = node.y,
        nodeZ = node.z,
        nodeQx = node.qx,
        nodeQy = node.qy,
        nodeQz = node.qz,
        nodeQw = node.qw,
        nodeScaleX = node.scaleX,
        nodeScaleY = node.scaleY,
        nodeScaleZ = node.scaleZ,
        offsetX = offsetX,
        offsetY = offsetY,
        offsetZ = offsetZ,
        offsetRotationX = offsetRotationX,
        offsetRotationY = offsetRotationY,
        offsetRotationZ = offsetRotationZ,
    )

    override fun type(): ComponentType<AttachedTo> = AttachedTo

    override fun toString(): String = "AttachedTo($parent, node=$node)"

    public companion object : ComponentType<AttachedTo>() {

        /**
         * The snapshot registration for a game's `ComponentRegistry`, which is what makes a
         * snapshot, a rewind, a replay hash and replication see an [AttachedTo] at all: capture
         * walks the registry, and a component left out of it is invisible rather than partly
         * captured. Built fresh per call, like [Transform3D.snapshotType].
         *
         * The kinds are in the generated replicator's order - the field names sorted - which puts
         * `node` and `parent` among the floats rather than at either end: `node`, `nodeQw`,
         * `nodeQx`, `nodeQy`, `nodeQz`, `nodeScaleX`, `nodeScaleY`, `nodeScaleZ`, `nodeX`,
         * `nodeY`, `nodeZ`, `offsetRotationX`, `offsetRotationY`, `offsetRotationZ`, `offsetX`,
         * `offsetY`, `offsetZ`, `parent`. `AttachedToSnapshotTypeTest` changes one field at a
         * time and checks each lands on its own bit.
         */
        public fun snapshotType(): ReplicatedComponentType<AttachedTo> = fleksComponentType(
            AttachedToReplicator,
            ComponentSchema.of(AttachedToReplicator, "AttachedTo", KINDS),
            AttachedTo,
        ) { AttachedTo() }

        /** `node`, sixteen floats, then `parent`: the sorted field order above. */
        private val KINDS: List<FieldKind> =
            listOf(FieldKind.Int) + List(FLOAT_FIELDS) { FieldKind.Float } + FieldKind.NetId
    }
}

/** How many of [AttachedTo]'s lowered fields are floats: the node's ten and the offset's six. */
private const val FLOAT_FIELDS: Int = 16
