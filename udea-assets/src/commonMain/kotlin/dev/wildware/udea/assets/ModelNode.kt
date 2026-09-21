package dev.wildware.udea.assets

/**
 * One named node of a model file - a socket, a mount point, a muzzle, a bone - as gameplay code
 * names it: `Chassis.Nodes.socket_roof` (issue #260).
 *
 * A game does not write these. The asset build reads each `model(...)`'s glTF file once and
 * produces the same nodes twice, from that one reading:
 *
 * - **by name, in code**: one generated property per named node, so a node the file does not have
 *   is a name that does not compile (`UDEA0018` adds the did-you-mean). It is the same bargain
 *   `AnimationClip` strikes, and for the same reason.
 * - **as a list, on the asset**: [Model.nodes], packed into the bundle, so a game holding a
 *   `Ref<Model>` can ask what sockets it has without a table of its own (issue #271). The two
 *   are equal, node for node.
 *
 * It lives here, in the asset model, rather than in `udea-core` beside the mount it feeds,
 * because it is data about a model file and [Model] carries it; `udea-core` depends on this
 * module, so the kernel's `AttachedTo` still takes one.
 *
 * ## The frame it is in, and why the numbers are here rather than looked up
 *
 * The transform is the node's place **at rest**, relative to the model's own origin, in the
 * world's Z-up frame (`Transform3D`'s frame): the asset build turns the file's Y-up transform
 * onto Z-up, so a Blender Empty whose +Z points out of the hull has a `+Z` here that points out
 * of the hull as well.
 *
 * The numbers are baked into the generated source and copied into `AttachedTo` when a part is
 * mounted, rather than read from a registry when the simulation needs them. The simulation
 * places a part from its own components alone, so the part's place is a pure function of what
 * the snapshot already holds.
 *
 * @property index the node's position in the file's `nodes` array. This is the node's identity: it
 *   is what `AttachedTo` stores and what the renderer resolves the live, animated node with.
 * @property name the file's name for the node, for people and tools. Never on the wire.
 * @property qx quaternion, `(x, y, z, w)`. A quaternion rather than three angles because that is
 *   what glTF stores, so the build converts frames and never takes a sine.
 * @property extras what the artist attached to this node - in Blender, the object's Custom
 *   Properties: a socket's accepted size, a module's mass (issue #271).
 */
public data class ModelNode(
    public val index: Int,
    public val name: String,
    public val x: Float = 0f,
    public val y: Float = 0f,
    public val z: Float = 0f,
    public val qx: Float = 0f,
    public val qy: Float = 0f,
    public val qz: Float = 0f,
    public val qw: Float = 1f,
    public val scaleX: Float = 1f,
    public val scaleY: Float = 1f,
    public val scaleZ: Float = 1f,
    public val extras: ModelExtras = ModelExtras.EMPTY,
) {
    init {
        require(index >= NONE) { "node '$name' has index $index; a node index is a position in a list" }
    }

    public companion object {

        /** [index] when a mount names no node of a file: the parent's own origin. */
        public const val NONE: Int = -1

        /**
         * The parent entity's own origin, for a part mounted on something that has no nodes at
         * all - a built-in box, a sprite - or on the model's origin itself.
         */
        public val ORIGIN: ModelNode = ModelNode(NONE, "")
    }
}
