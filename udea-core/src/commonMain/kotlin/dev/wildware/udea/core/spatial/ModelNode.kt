package dev.wildware.udea.core.spatial

/**
 * One named node of a model file - a socket, a mount point, a muzzle, a bone - as gameplay code
 * names it: `Chassis.Nodes.socket_roof` (issue #260).
 *
 * A game does not write these. The asset build reads each `model(...)`'s glTF file and generates
 * one per named node in it, so a node the file does not have is a name that does not compile
 * ([dev.wildware.udea.diagnostics.UdeaRules.UNRESOLVED_MODEL_NODE] adds the did-you-mean), and
 * nothing ever looks a node up by its name at run time. It is the same bargain `AnimationClip`
 * strikes, and for the same reason.
 *
 * ## The frame it is in, and why the numbers are here rather than looked up
 *
 * The transform is the node's place **at rest**, relative to the model's own origin, in the
 * world's Z-up frame ([Transform3D]'s frame): the asset build turns the file's Y-up transform
 * onto Z-up, so a Blender Empty whose +Z points out of the hull has a `+Z` here that points out
 * of the hull as well.
 *
 * The numbers are baked into the generated source and copied into [AttachedTo] when a part is
 * mounted, rather than read from a registry when the simulation needs them. The simulation is
 * headless and knows nothing about which model an entity draws - `ModelRenderer` is the
 * renderer's, never replicated - so a lookup would mean a second, parallel model catalogue on the
 * simulation's side of the line. Ten floats on the component instead, and the part's place is a
 * pure function of what the snapshot already holds.
 *
 * @property index the node's position in the file's `nodes` array. This is the node's identity: it
 *   is what [AttachedTo] stores and what the renderer resolves the live, animated node with.
 * @property name the file's name for the node, for people and tools. Never on the wire.
 * @property qx quaternion, `(x, y, z, w)`. A quaternion rather than three angles because that is
 *   what glTF stores, so the build converts frames and never takes a sine.
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
