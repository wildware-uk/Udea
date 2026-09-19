package dev.wildware.udea.render.model

import de.fabmax.kool.math.MutableMat4f
import de.fabmax.kool.math.MutableVec3f
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Model as KoolModel
import de.fabmax.kool.modules.gltf.GltfFile
import de.fabmax.kool.scene.animation.Skin

/**
 * The joints of one skinned model as it is drawn: where each joint is in the world, and which joint
 * it hangs from (issue #243). Filled by [ModelRenderSystem.skeletonOf]; what the editor's bone
 * overlay draws.
 *
 * Kool-free, like every public type here (UDEA-MG-002). One instance is meant to be reused: filling
 * it again reuses its arrays, which only grow.
 */
public class ModelSkeleton {

    private var xs = FloatArray(INITIAL_JOINTS)
    private var ys = FloatArray(INITIAL_JOINTS)
    private var zs = FloatArray(INITIAL_JOINTS)
    private var parents = IntArray(INITIAL_JOINTS)

    /** How many joints. Zero when nothing skinned was found. */
    public var size: Int = 0
        private set

    /** World x of joint [joint]. */
    public fun x(joint: Int): Float = xs[checked(joint)]

    /** World y of joint [joint]. */
    public fun y(joint: Int): Float = ys[checked(joint)]

    /** World z of joint [joint]. */
    public fun z(joint: Int): Float = zs[checked(joint)]

    /** The joint [joint] hangs from, or [ROOT] for a joint that hangs from none. */
    public fun parent(joint: Int): Int = parents[checked(joint)]

    internal fun clear() {
        size = 0
    }

    internal fun add(x: Float, y: Float, z: Float, parent: Int) {
        if (size == xs.size) {
            val grown = size * 2
            xs = xs.copyOf(grown)
            ys = ys.copyOf(grown)
            zs = zs.copyOf(grown)
            parents = parents.copyOf(grown)
        }
        xs[size] = x
        ys[size] = y
        zs[size] = z
        parents[size] = parent
        size++
    }

    private fun checked(joint: Int): Int {
        if (joint !in 0 until size) throw IndexOutOfBoundsException("joint $joint of a skeleton of $size")
        return joint
    }

    override fun toString(): String = "ModelSkeleton($size joints)"

    public companion object {
        /** [parent] of a joint that hangs from no other joint. */
        public const val ROOT: Int = -1

        private const val INITIAL_JOINTS = 32
    }
}

/**
 * One joint of a Kool skin, with what it takes to place it: the mesh that skin deforms, and the
 * joint's rest position in that mesh's space - its bind matrix applied to the origin.
 *
 * @property parent the index, in the list [skinJointsOf] made, of the nearest joint above this one
 *   in the node tree, or [ModelSkeleton.ROOT].
 */
internal class SkinJoint(
    val skinNode: Skin.SkinNode,
    val mesh: Mesh<*>,
    val bindOrigin: Vec3f,
    val parent: Int,
    /** The joint's node in the glTF file. */
    val node: Int,
)

/**
 * The joints of every skin in [model] that a mesh of [model] is skinned by, in skin order. A joint
 * two skins share is listed once, from the first. Empty for a model with no skin.
 *
 * [model] is a node made from [gltf], and which joint hangs from which is read from [gltf]'s own
 * node tree: the Kool node a joint drives is not reliably under its parent joint's node (the Fox's
 * are all parentless), and a skin keeps its own tree private. Kool makes one skin for each of the
 * file's skins with inverse bind matrices, in the file's order, and lists a skin's joints in the
 * order the file does, so the two line up index for index.
 */
internal fun skinJointsOf(model: KoolModel, gltf: GltfFile): List<SkinJoint> {
    // A node made without the file's animations has no skins at all.
    if (model.skins.isEmpty()) return emptyList()
    val fileSkins = gltf.skins.filter { it.inverseBindMatrices >= 0 }
    check(fileSkins.size == model.skins.size) { "${model.skins.size} skins made from ${fileSkins.size} in the file" }
    val parentNode = IntArray(gltf.nodes.size) { NO_NODE }
    gltf.nodes.forEachIndexed { index, node -> for (child in node.children) parentNode[child] = index }

    val joints = ArrayList<SkinJoint>()
    val indexOfNode = HashMap<Int, Int>()
    val bind = MutableMat4f()
    for ((skinIndex, skin) in model.skins.withIndex()) {
        val mesh = model.meshes.values.firstOrNull { it.skin === skin } ?: continue
        val fileJoints = fileSkins[skinIndex].joints
        check(fileJoints.size == skin.nodes.size) { "a skin of ${skin.nodes.size} joints made from ${fileJoints.size}" }
        val first = joints.size
        for ((place, skinNode) in skin.nodes.withIndex()) {
            val node = fileJoints[place]
            if (node in indexOfNode) continue
            indexOfNode[node] = joints.size
            // The inverse bind matrix takes a vertex into the joint's space; its inverse takes the
            // joint's own origin back to where the joint rests in the mesh.
            skinNode.inverseBindMatrix.invert(bind)
            val origin = bind.transform(MutableVec3f(), 1f)
            joints += SkinJoint(skinNode, mesh, origin, ModelSkeleton.ROOT, node)
        }
        // Parents once every joint of the skin has its index, whatever order the skin lists them in:
        // the nearest joint above in the file's tree, through any node that is not a joint. A file's
        // nodes are a tree, so the walk is bounded by its size even in one that is not.
        for (index in first until joints.size) {
            val joint = joints[index]
            var above = parentNode[joint.node]
            var steps = 0
            while (above != NO_NODE && above !in indexOfNode && steps++ < parentNode.size) above = parentNode[above]
            val parent = indexOfNode[above] ?: continue
            joints[index] = SkinJoint(joint.skinNode, joint.mesh, joint.bindOrigin, parent, joint.node)
        }
    }
    return joints
}

/** A glTF node with no parent: a scene root. */
private const val NO_NODE = -1
