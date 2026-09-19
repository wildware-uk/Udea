package dev.wildware.udea.render.model

import de.fabmax.kool.math.MutableMat4f
import de.fabmax.kool.math.MutableVec3f
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Model as KoolModel
import de.fabmax.kool.scene.Node
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
)

/**
 * The joints of every skin in [model] that a mesh of [model] is skinned by, in skin order. A joint
 * two skins share is listed once, from the first. Empty for a model with no skin.
 */
internal fun skinJointsOf(model: KoolModel): List<SkinJoint> {
    val joints = ArrayList<SkinJoint>()
    val indexOf = HashMap<Node, Int>()
    val bind = MutableMat4f()
    for (skin in model.skins) {
        val mesh = model.meshes.values.firstOrNull { it.skin === skin } ?: continue
        val first = joints.size
        for (skinNode in skin.nodes) {
            if (skinNode.joint in indexOf) continue
            indexOf[skinNode.joint] = joints.size
            // The inverse bind matrix takes a vertex into the joint's space; its inverse takes the
            // joint's own origin back to where the joint rests in the mesh.
            skinNode.inverseBindMatrix.invert(bind)
            val origin = bind.transform(MutableVec3f(), 1f)
            joints += SkinJoint(skinNode, mesh, origin, ModelSkeleton.ROOT)
        }
        // Parents once every joint of the skin has its index, whatever order the skin lists them in.
        for (index in first until joints.size) {
            val joint = joints[index]
            var above = joint.skinNode.joint.parent
            while (above != null && above !in indexOf) above = above.parent
            if (above != null) joints[index] = SkinJoint(joint.skinNode, joint.mesh, joint.bindOrigin, indexOf.getValue(above))
        }
    }
    return joints
}
