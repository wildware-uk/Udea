package dev.wildware.udea.assets.compiler.gen

import dev.wildware.udea.assets.compiler.validate.GltfCheck
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readBytes
import kotlin.math.sqrt

/**
 * One named node of a glTF file, as the asset build sees it: where it is, at rest, in the frame
 * the engine places models in (issue #260).
 *
 * @property index the position in the file's `nodes` array, which is the node's identity - what
 *   goes into an `AttachedTo` and what the renderer resolves the live, animated node with.
 * @property name the file's name for it. A node with no name is not here: it cannot be asked for.
 * @property qx quaternion, `(x, y, z, w)`, with `w` never negative so two builds of one file
 *   cannot emit the two spellings of the same turn.
 * @property extras the node's glTF `extras` as [GltfExtras] reads them: plain values, keyed by
 *   dotted name (issue #271).
 */
internal data class GltfNode(
    val index: Int,
    val name: String,
    val x: Float,
    val y: Float,
    val z: Float,
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val qw: Float,
    val scaleX: Float,
    val scaleY: Float,
    val scaleZ: Float,
    val extras: Map<String, Any> = emptyMap(),
)

/**
 * Everything the asset build reads out of a glTF file for a game to ask about (issue #271): its
 * named nodes, and the `extras` of its default scene - in Blender, the scene's Custom Properties.
 */
internal data class GltfModel(
    val nodes: List<GltfNode>,
    val extras: Map<String, Any>,
)

/**
 * Reads the named nodes out of a glTF 2.0 file, headless and without Kool (issue #260).
 *
 * The asset build runs before anything draws and on machines with no GL, so it cannot ask the
 * renderer's loader where a socket is. It does not need to: a node's place is its own
 * translation, rotation and scale - or its `matrix` - composed with its ancestors', and all of
 * that is in the JSON. The binary buffer is never touched, exactly as [GltfClips] never touches
 * it.
 *
 * ## Y-up to Z-up, exactly
 *
 * glTF is Y-up and Udea's world is Z-up, and `ModelStage` turns a model a quarter turn about X
 * when it draws it. A node's place is therefore reported in the turned frame - `R * M * R⁻¹`
 * for that quarter turn - so `Chassis.Nodes.socket_roof` reads the way the model stands in the
 * world, and a Blender Empty whose +Z points out of the hull still points out of the hull here.
 *
 * The quarter turn is applied as the exact swap it is, `(x, y, z) -> (x, -z, y)`, and never as
 * a sine of ninety degrees: every number this emits is written into generated source that a
 * golden test compares byte for byte on every machine in the CI matrix, so the arithmetic is
 * multiplication, addition, division and `sqrt` - all exactly specified by IEEE 754 - and no
 * transcendental anywhere.
 *
 * Every failure is a message that names what is wrong, returned rather than thrown, because the
 * caller turns it into a located diagnostic.
 */
internal object GltfNodes {

    private val json = Json { ignoreUnknownKeys = true }

    /** Every named node in [file], in the file's order, or a failure saying what is wrong. */
    fun read(file: Path): Result<List<GltfNode>> = readModel(file).map { it.nodes }

    /** Every named node in the binary glTF [glb]: an `.fbx`'s conversion (issue #244). */
    fun read(glb: ByteArray): Result<List<GltfNode>> = readModel(glb).map { it.nodes }

    /** [file]'s named nodes and its own extras (issue #271), or a failure saying what is wrong. */
    fun readModel(file: Path): Result<GltfModel> =
        GltfCheck.jsonOf(file.extension.lowercase(), file.readBytes()).fold(::modelOf) { Result.failure(it) }

    /** [readModel] for the binary glTF [glb]: an `.fbx`'s conversion. */
    fun readModel(glb: ByteArray): Result<GltfModel> =
        GltfCheck.jsonOf(GLB, glb).fold(::modelOf) { Result.failure(it) }

    private const val GLB = "glb"

    /** The glTF JSON [text]'s named nodes, each placed relative to the model's origin, and its extras. */
    private fun modelOf(text: String): Result<GltfModel> {
        val document = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            // `parseToJsonElement` reports malformed input this way; the message below says so.
            null
        } ?: return failure("is not a glTF 2.0 file: its JSON does not parse")
        return nodesOf(document).map { GltfModel(it, GltfExtras.read(defaultScene(document)?.get(EXTRAS))) }
    }

    /**
     * The scene the file shows by default: the one its `scene` names, or its first when it names
     * none - the scene a viewer opens, and the one Blender writes a scene's Custom Properties on.
     */
    private fun defaultScene(document: JsonObject): JsonObject? {
        val scenes = document["scenes"] as? JsonArray ?: return null
        val chosen = (document["scene"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: 0
        return scenes.getOrNull(chosen) as? JsonObject
    }

    /** Every named node in [document], each placed relative to the model's origin. */
    private fun nodesOf(document: JsonObject): Result<List<GltfNode>> {
        val nodes = document["nodes"] as? JsonArray ?: return Result.success(emptyList())
        val parents = parentsOf(nodes).getOrElse { return Result.failure(it) }
        val locals = nodes.map { local(it) }
        val out = ArrayList<GltfNode>(nodes.size)
        for (index in nodes.indices) {
            val name = ((nodes[index] as? JsonObject)?.get("name") as? JsonPrimitive)?.content
            if (name.isNullOrEmpty()) continue
            val world = world(index, parents, locals).getOrElse { return failure("has a node `$name` whose ${it.message}") }
            out += decompose(index, name, conjugate(world))
                .copy(extras = GltfExtras.read((nodes[index] as? JsonObject)?.get(EXTRAS)))
        }
        return Result.success(out)
    }

    /** The parent of each node, by index, or -1 for a root. A node claimed twice is a failure. */
    private fun parentsOf(nodes: JsonArray): Result<IntArray> {
        val parents = IntArray(nodes.size) { NO_PARENT }
        for (index in nodes.indices) {
            val children = (nodes[index] as? JsonObject)?.get("children") as? JsonArray ?: continue
            for (child in children) {
                val at = (child as? JsonPrimitive)?.intOrNull ?: continue
                if (at !in nodes.indices) {
                    return failure("names node $at as a child of node $index, which the file does not have")
                }
                if (parents[at] != NO_PARENT) {
                    return failure("gives node $at two parents, nodes ${parents[at]} and $index")
                }
                parents[at] = index
            }
        }
        return Result.success(parents)
    }

    /** [index]'s place relative to the model's origin: its own transform under its ancestors'. */
    private fun world(index: Int, parents: IntArray, locals: List<FloatArray>): Result<FloatArray> {
        val chain = ArrayList<Int>(INITIAL_DEPTH)
        var at = index
        while (at != NO_PARENT) {
            if (chain.size > parents.size) {
                return failure("parents run in a circle, so it has no place relative to the model")
            }
            chain += at
            at = parents[at]
        }
        val world = identity()
        // Outermost ancestor first: each one places everything below it.
        for (position in chain.indices.reversed()) multiply(world, locals[chain[position]])
        return Result.success(world)
    }

    /** A node's own transform: its `matrix` if it has one, otherwise translate, rotate, scale. */
    private fun local(node: JsonElement): FloatArray {
        val fields = node as? JsonObject ?: return identity()
        (fields["matrix"] as? JsonArray)?.takeIf { it.size == MATRIX_FLOATS }?.let { return fromColumnMajor(it) }
        val translation = numbers(fields["translation"], TRIPLE, 0f)
        val rotation = fields["rotation"]?.let { numbers(it, QUAD, 0f) } ?: floatArrayOf(0f, 0f, 0f, 1f)
        val scale = numbers(fields["scale"], TRIPLE, 1f)
        val m = identity()
        setQuaternion(m, rotation[0], rotation[1], rotation[2], rotation[3])
        scaleColumns(m, scale[0], scale[1], scale[2])
        m[3] = translation[0]
        m[STRIDE + 3] = translation[1]
        m[2 * STRIDE + 3] = translation[2]
        return m
    }

    /** [count] numbers out of a JSON array, [fallback] wherever the file gives none. */
    private fun numbers(element: JsonElement?, count: Int, fallback: Float): FloatArray {
        val array = element as? JsonArray
        return FloatArray(count) { at ->
            ((array?.getOrNull(at) as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull)?.toFloat() ?: fallback
        }
    }

    /** glTF's `matrix` is sixteen floats in column-major order; this keeps the top three rows. */
    private fun fromColumnMajor(values: JsonArray): FloatArray {
        val m = identity()
        for (column in 0 until STRIDE) {
            for (row in 0 until ROWS) {
                val at = column * 4 + row
                m[row * STRIDE + column] =
                    ((values[at] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull)?.toFloat() ?: 0f
            }
        }
        return m
    }

    /** `into` becomes `into * right`. */
    private fun multiply(into: FloatArray, right: FloatArray) {
        val product = FloatArray(SIZE)
        for (row in 0 until ROWS) {
            val r = row * STRIDE
            for (column in 0 until ROWS) {
                product[r + column] = into[r] * right[column] +
                    into[r + 1] * right[STRIDE + column] +
                    into[r + 2] * right[2 * STRIDE + column]
            }
            product[r + 3] = into[r] * right[3] +
                into[r + 1] * right[STRIDE + 3] +
                into[r + 2] * right[2 * STRIDE + 3] +
                into[r + 3]
        }
        product.copyInto(into)
    }

    /**
     * `R * m * R⁻¹` for the quarter turn about X that takes the file's up onto the world's up.
     *
     * `R` takes `(x, y, z)` to `(x, -z, y)`, so `R * m` swaps and negates rows and `m * R⁻¹`
     * does the same to columns. Written out as those swaps rather than as a matrix product with
     * a sine, so the result carries no rounding of its own.
     */
    private fun conjugate(m: FloatArray): FloatArray {
        val rows = FloatArray(SIZE)
        for (column in 0 until STRIDE) {
            rows[column] = m[column]
            rows[STRIDE + column] = -m[2 * STRIDE + column]
            rows[2 * STRIDE + column] = m[STRIDE + column]
        }
        val out = FloatArray(SIZE)
        for (row in 0 until ROWS) {
            val r = row * STRIDE
            out[r] = rows[r]
            out[r + 1] = -rows[r + 2]
            out[r + 2] = rows[r + 1]
            // The translation is turned, not re-ordered: it is `R * t`, which the rows above did.
            out[r + 3] = rows[r + 3]
        }
        return out
    }

    /** [m] as a node: its translation, the turn its axes make, and each axis's length. */
    private fun decompose(index: Int, name: String, m: FloatArray): GltfNode {
        var scaleX = length(m, 0)
        val scaleY = length(m, 1)
        val scaleZ = length(m, 2)
        // A mirrored node - an odd number of negative scales - is a left-handed frame, which no
        // rotation can be. Its X carries the flip, which is where a modelling tool puts it.
        if (determinant(m) < 0f) scaleX = -scaleX
        val r = FloatArray(ROWS * ROWS)
        for (row in 0 until ROWS) {
            r[row * ROWS] = m[row * STRIDE] / nonZero(scaleX)
            r[row * ROWS + 1] = m[row * STRIDE + 1] / nonZero(scaleY)
            r[row * ROWS + 2] = m[row * STRIDE + 2] / nonZero(scaleZ)
        }
        val q = quaternion(r)
        return GltfNode(
            index = index,
            name = name,
            x = plain(m[3]),
            y = plain(m[STRIDE + 3]),
            z = plain(m[2 * STRIDE + 3]),
            qx = plain(q[0]),
            qy = plain(q[1]),
            qz = plain(q[2]),
            qw = plain(q[3]),
            scaleX = plain(scaleX),
            scaleY = plain(scaleY),
            scaleZ = plain(scaleZ),
        )
    }

    /**
     * The unit quaternion `(x, y, z, w)` of the rotation [r], with `w` never negative.
     *
     * Shepperd's method: the largest of the four possible denominators is taken, so the division
     * is never by a number near zero, whatever the turn. Multiplication, addition and `sqrt`
     * only - each exactly specified - so every machine emits the same bits.
     */
    private fun quaternion(r: FloatArray): FloatArray {
        val trace = r[0] + r[4] + r[8]
        val q = FloatArray(QUAD)
        if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            q[3] = QUARTER * s
            q[0] = (r[7] - r[5]) / s
            q[1] = (r[2] - r[6]) / s
            q[2] = (r[3] - r[1]) / s
        } else if (r[0] > r[4] && r[0] > r[8]) {
            val s = sqrt(1f + r[0] - r[4] - r[8]) * 2f
            q[3] = (r[7] - r[5]) / s
            q[0] = QUARTER * s
            q[1] = (r[1] + r[3]) / s
            q[2] = (r[2] + r[6]) / s
        } else if (r[4] > r[8]) {
            val s = sqrt(1f + r[4] - r[0] - r[8]) * 2f
            q[3] = (r[2] - r[6]) / s
            q[0] = (r[1] + r[3]) / s
            q[1] = QUARTER * s
            q[2] = (r[5] + r[7]) / s
        } else {
            val s = sqrt(1f + r[8] - r[0] - r[4]) * 2f
            q[3] = (r[3] - r[1]) / s
            q[0] = (r[2] + r[6]) / s
            q[1] = (r[5] + r[7]) / s
            q[2] = QUARTER * s
        }
        // A quaternion and its negative are the same turn; one spelling, so a rebuild cannot
        // flip every sign and produce a diff nobody changed anything to get.
        return if (q[3] < 0f) FloatArray(QUAD) { -q[it] } else q
    }

    private fun length(m: FloatArray, column: Int): Float = sqrt(
        m[column] * m[column] +
            m[STRIDE + column] * m[STRIDE + column] +
            m[2 * STRIDE + column] * m[2 * STRIDE + column],
    )

    private fun determinant(m: FloatArray): Float =
        m[0] * (m[STRIDE + 1] * m[2 * STRIDE + 2] - m[STRIDE + 2] * m[2 * STRIDE + 1]) -
            m[1] * (m[STRIDE] * m[2 * STRIDE + 2] - m[STRIDE + 2] * m[2 * STRIDE]) +
            m[2] * (m[STRIDE] * m[2 * STRIDE + 1] - m[STRIDE + 1] * m[2 * STRIDE])

    private fun setQuaternion(m: FloatArray, x: Float, y: Float, z: Float, w: Float) {
        m[0] = 1f - 2f * (y * y + z * z)
        m[1] = 2f * (x * y - z * w)
        m[2] = 2f * (x * z + y * w)
        m[STRIDE] = 2f * (x * y + z * w)
        m[STRIDE + 1] = 1f - 2f * (x * x + z * z)
        m[STRIDE + 2] = 2f * (y * z - x * w)
        m[2 * STRIDE] = 2f * (x * z - y * w)
        m[2 * STRIDE + 1] = 2f * (y * z + x * w)
        m[2 * STRIDE + 2] = 1f - 2f * (x * x + y * y)
    }

    private fun scaleColumns(m: FloatArray, x: Float, y: Float, z: Float) {
        for (row in 0 until ROWS) {
            m[row * STRIDE] *= x
            m[row * STRIDE + 1] *= y
            m[row * STRIDE + 2] *= z
        }
    }

    private fun identity(): FloatArray = FloatArray(SIZE).also {
        it[0] = 1f
        it[STRIDE + 1] = 1f
        it[2 * STRIDE + 2] = 1f
    }

    private fun nonZero(scale: Float): Float = if (scale == 0f) 1f else scale

    /**
     * [value] with negative zero written as zero.
     *
     * Turning a frame negates zeros, so a node at the origin comes out of the arithmetic as
     * `-0.0`. The two are the same number by `==` and different objects by `equals`, which would
     * make two `ModelNode`s of one node unequal, and `-0.0f` in generated source reads as a
     * defect to everyone who meets it.
     */
    private fun plain(value: Float): Float = if (value == 0f) 0f else value

    /** A failure whose message completes "the file ...", as its caller composes it. */
    private fun <T> failure(why: String): Result<T> = Result.failure(IllegalArgumentException(why))

    /** The glTF property every object may carry for application data. */
    private const val EXTRAS = "extras"

    private const val ROWS = 3
    private const val STRIDE = 4
    private const val SIZE = ROWS * STRIDE
    private const val MATRIX_FLOATS = 16
    private const val NO_PARENT = -1
    private const val INITIAL_DEPTH = 8
    private const val QUARTER = 0.25f

    /** How many numbers a translation or a scale has, and how many a quaternion has. */
    private const val TRIPLE = 3
    private const val QUAD = 4
}
