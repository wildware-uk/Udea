package dev.wildware.udea.render.model

import de.fabmax.kool.math.AngleF
import de.fabmax.kool.math.MutableMat4f
import de.fabmax.kool.math.MutableVec3f
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.math.rad
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.render.view.PickSink

/**
 * The world box round each model [ModelRenderSystem] draws, for picking (issue #235).
 *
 * A model's own box, in its own units, turned into the world by the same transform the stage draws
 * it with - translate, turn about Z, Y and X, scale, and for an imported file the quarter turn that
 * stands its +Y up along the world's +Z - so the box sits where the model is seen.
 *
 * An imported model's own box is the union of its meshes' vertex bounds, which glTF requires every
 * position accessor to state. It is the bind pose: an animation that swings an arm past the box is
 * not in it. Node transforms inside the file are not applied either; a file whose meshes are placed
 * by a parent node is boxed where its vertices are, not where the node puts them.
 *
 * Render thread only, like the system it belongs to.
 */
internal class ModelBounds {

    /** Each imported model's own box, read out of its file once. */
    private val imported = HashMap<ImportedModel, FloatArray?>()

    private val matrix = MutableMat4f()
    private val axisScale = MutableVec3f()
    private val corner = MutableVec3f()

    /**
     * Reports [source]'s box, placed by the given transform, to [out] as [entity]'s.
     * Nothing is reported for an imported file that states no vertex bounds.
     */
    @Suppress("LongParameterList") // The transform the stage draws with, field for field.
    fun report(
        entity: NetId,
        source: ModelSource,
        x: Float, y: Float, z: Float,
        rotationX: Float, rotationY: Float, rotationZ: Float,
        scaleX: Float, scaleY: Float, scaleZ: Float,
        out: PickSink,
    ) {
        val local = localBox(source) ?: return
        matrix.place(x, y, z, rotationX, rotationY, rotationZ, axisScale.set(scaleX, scaleY, scaleZ))
        if (source is ImportedModel) matrix.rotate(Y_UP_TO_Z_UP, Vec3f.X_AXIS)

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        for (index in 0 until CORNERS) {
            corner.set(
                if (index and 1 == 0) local[MIN_X] else local[MAX_X],
                if (index and 2 == 0) local[MIN_Y] else local[MAX_Y],
                if (index and 4 == 0) local[MIN_Z] else local[MAX_Z],
            )
            matrix.transform(corner, 1f)
            minX = minOf(minX, corner.x)
            minY = minOf(minY, corner.y)
            minZ = minOf(minZ, corner.z)
            maxX = maxOf(maxX, corner.x)
            maxY = maxOf(maxY, corner.y)
            maxZ = maxOf(maxZ, corner.z)
        }
        out.box(entity, minX, minY, minZ, maxX, maxY, maxZ)
    }

    /** [source]'s own box as min x, y, z then max x, y, z, or `null` when it states none. */
    private fun localBox(source: ModelSource): FloatArray? = when (source) {
        is MeshModel -> meshBox(source.mesh)
        is ImportedModel -> imported.getOrPut(source) { fileBox(source) }
    }

    private fun meshBox(mesh: ModelMesh): FloatArray = when (mesh) {
        is ModelMesh.Box -> centred(mesh.sizeX / 2f, mesh.sizeY / 2f, mesh.sizeZ / 2f)
        is ModelMesh.Sphere -> centred(mesh.radius, mesh.radius, mesh.radius)
        is ModelMesh.Plane -> centred(mesh.sizeX / 2f, mesh.sizeY / 2f, 0f)
    }

    private fun centred(halfX: Float, halfY: Float, halfZ: Float): FloatArray =
        floatArrayOf(-halfX, -halfY, -halfZ, halfX, halfY, halfZ)

    private fun fileBox(model: ImportedModel): FloatArray? {
        val gltf = model.gltf
        var box: FloatArray? = null
        for (mesh in gltf.meshes) for (primitive in mesh.primitives) {
            val accessor = primitive.attributes[POSITION]?.let { gltf.accessors.getOrNull(it) } ?: continue
            val min = accessor.min ?: continue
            val max = accessor.max ?: continue
            if (min.size < AXES || max.size < AXES) continue
            val grown = box ?: floatArrayOf(min[0], min[1], min[2], max[0], max[1], max[2])
            for (axis in 0 until AXES) {
                grown[MIN_X + axis] = minOf(grown[MIN_X + axis], min[axis])
                grown[MAX_X + axis] = maxOf(grown[MAX_X + axis], max[axis])
            }
            box = grown
        }
        return box
    }

    override fun toString(): String = "ModelBounds(${imported.size} files boxed)"

    private companion object {
        const val MIN_X = 0
        const val MIN_Y = 1
        const val MIN_Z = 2
        const val MAX_X = 3
        const val MAX_Y = 4
        const val MAX_Z = 5
        const val AXES = 3
        const val CORNERS = 8

        /** The glTF attribute a mesh's vertex positions are under. */
        const val POSITION = "POSITION"
    }
}

/**
 * Sets this matrix to a model's placement: translate to ([x], [y], [z]), then turn about Z, Y and X
 * by the given radians - so X is applied to the model first - then [scale]. What [ModelStage] draws
 * with and [ModelBounds] boxes with, so the box cannot be placed differently from the model.
 */
@Suppress("LongParameterList") // A transform, field for field, as `Transform3D` holds it.
internal fun MutableMat4f.place(
    x: Float, y: Float, z: Float,
    rotationX: Float, rotationY: Float, rotationZ: Float,
    scale: Vec3f,
): MutableMat4f = setIdentity()
    .translate(x, y, z)
    .rotate(rotationZ.rad, Vec3f.Z_AXIS)
    .rotate(rotationY.rad, Vec3f.Y_AXIS)
    .rotate(rotationX.rad, Vec3f.X_AXIS)
    .scale(scale)

/** A quarter turn about X takes a glTF file's +Y, its up, to the world's +Z. */
internal val Y_UP_TO_Z_UP: AngleF = 90f.deg

/**
 * The way back: what takes a place *inside* a drawn model out of the file's frame and into the
 * world's, so a socket reads the way `Transform3D` and `ModelNode` do (issue #260).
 */
internal val Z_UP_TO_Y_UP: AngleF = (-90f).deg
