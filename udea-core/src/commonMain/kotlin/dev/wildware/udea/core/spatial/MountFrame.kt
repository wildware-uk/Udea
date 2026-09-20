package dev.wildware.udea.core.spatial

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One 3x4 affine transform - rotation and scale in the left 3x3, translation in the last column -
 * as twelve plain floats, reused rather than allocated (issue #260).
 *
 * Where a mounted part ends up is three transforms multiplied together: the parent entity's
 * [Transform3D], the socket's place inside the parent's model ([ModelNode]) and the mount's own
 * offset. A matrix is the only shape that composes those without special cases, and a component
 * cannot hold one - [Transform3D] is nine floats on purpose - so this is the form the arithmetic
 * is done in, between reading the components and writing one back.
 *
 * It matches the renderer's placement exactly, and the match is load-bearing: `ModelBounds.place`
 * builds a model's matrix as translate, then turn about Z, then Y, then X, then scale, so [set]
 * does the same and a socket resolved here lands where the picture draws it.
 *
 * Every operation is in `Float` and writes into `this`, so a tick resolving a hundred mounts
 * allocates nothing.
 */
internal class MountFrame {

    /** Row-major: `m[row * 4 + column]`, the bottom row `(0, 0, 0, 1)` left implicit. */
    private val m = FloatArray(SIZE)

    /** Scratch for [mul], whose result cannot be written over an operand it is still reading. */
    private val product = FloatArray(SIZE)

    /** This as [transform] places a model: translate, turn about Z, then Y, then X, then scale. */
    fun set(transform: Transform3D): MountFrame {
        setEuler(transform.rotationX, transform.rotationY, transform.rotationZ)
        scaleColumns(transform.scaleX, transform.scaleY, transform.scaleZ)
        setTranslation(transform.x, transform.y, transform.z)
        return this
    }

    /** This as a node's rest place: its translation, its quaternion, its scale. */
    fun setNode(node: AttachedTo): MountFrame = setRotationFromQuaternion(
        node.nodeQx, node.nodeQy, node.nodeQz, node.nodeQw,
    ).also {
        scaleColumns(node.nodeScaleX, node.nodeScaleY, node.nodeScaleZ)
        setTranslation(node.nodeX, node.nodeY, node.nodeZ)
    }

    /** This as a mount's offset: a translation and a turn, in the socket's own frame. */
    fun setOffset(mount: AttachedTo): MountFrame {
        setEuler(mount.offsetRotationX, mount.offsetRotationY, mount.offsetRotationZ)
        setTranslation(mount.offsetX, mount.offsetY, mount.offsetZ)
        return this
    }

    /** This becomes `this * right`: [right] applied first, then this. */
    fun mul(right: MountFrame): MountFrame {
        val b = right.m
        for (row in 0 until ROWS) {
            val r = row * STRIDE
            for (column in 0 until ROWS) {
                product[r + column] =
                    m[r] * b[column] + m[r + 1] * b[STRIDE + column] + m[r + 2] * b[2 * STRIDE + column]
            }
            product[r + 3] =
                m[r] * b[3] + m[r + 1] * b[STRIDE + 3] + m[r + 2] * b[2 * STRIDE + 3] + m[r + 3]
        }
        product.copyInto(m)
        return this
    }

    /**
     * Writes this into [out]: the translation as it stands, each axis's length as that axis's
     * scale, and what is left as the three angles [set] would rebuild the rotation from.
     *
     * Two shapes a `Transform3D` cannot hold come out of the nearest thing to them, because the
     * alternative is refusing to place a part at all:
     *
     * - **Shear**, which a non-uniform scale above a turned socket produces, is dropped: the axes
     *   are taken as they are and normalised one at a time.
     * - **A mirror** - an odd number of negative scales somewhere in the chain - comes back as
     *   three positive scales, because an axis's length carries no sign.
     */
    fun writeTo(out: Transform3D) {
        val scaleX = sqrt(m[0] * m[0] + m[STRIDE] * m[STRIDE] + m[2 * STRIDE] * m[2 * STRIDE])
        val scaleY = sqrt(m[1] * m[1] + m[STRIDE + 1] * m[STRIDE + 1] + m[2 * STRIDE + 1] * m[2 * STRIDE + 1])
        val scaleZ = sqrt(m[2] * m[2] + m[STRIDE + 2] * m[STRIDE + 2] + m[2 * STRIDE + 2] * m[2 * STRIDE + 2])
        val r00 = m[0] / nonZero(scaleX)
        val r10 = m[STRIDE] / nonZero(scaleX)
        val r20 = m[2 * STRIDE] / nonZero(scaleX)
        val r01 = m[1] / nonZero(scaleY)
        val r11 = m[STRIDE + 1] / nonZero(scaleY)
        val r02 = m[2] / nonZero(scaleZ)
        val r12 = m[STRIDE + 2] / nonZero(scaleZ)
        val r22 = m[2 * STRIDE + 2] / nonZero(scaleZ)
        out.x = m[3]
        out.y = m[STRIDE + 3]
        out.z = m[2 * STRIDE + 3]
        out.scaleX = scaleX
        out.scaleY = scaleY
        out.scaleZ = scaleZ
        if (abs(r20) > STRAIGHT_UP) {
            // Straight up or straight down: the turn about X and the turn about Z are the same
            // turn, so all of it is given to Z and the part still faces the way it should.
            out.rotationX = 0f
            out.rotationY = if (r20 < 0f) HALF_PI else -HALF_PI
            out.rotationZ = atan2(-r01, r11)
        } else {
            out.rotationX = atan2(r12, r22)
            out.rotationY = asin(-r20.coerceIn(-1f, 1f))
            out.rotationZ = atan2(r10, r00)
        }
    }

    /**
     * The rotation `Rz * Ry * Rx` - the turn about X applied to the model first - written out.
     *
     * The one place the three angles of a `Transform3D` and of a mount's offset become a matrix,
     * so the two cannot come to mean different turns.
     */
    private fun setEuler(aboutX: Float, aboutY: Float, aboutZ: Float) {
        val sinX = sin(aboutX)
        val cosX = cos(aboutX)
        val sinY = sin(aboutY)
        val cosY = cos(aboutY)
        val sinZ = sin(aboutZ)
        val cosZ = cos(aboutZ)
        setRotation(
            cosZ * cosY, cosZ * sinY * sinX - sinZ * cosX, cosZ * sinY * cosX + sinZ * sinX,
            sinZ * cosY, sinZ * sinY * sinX + cosZ * cosX, sinZ * sinY * cosX - cosZ * sinX,
            -sinY, cosY * sinX, cosY * cosX,
        )
    }

    private fun setRotation(
        r00: Float, r01: Float, r02: Float,
        r10: Float, r11: Float, r12: Float,
        r20: Float, r21: Float, r22: Float,
    ) {
        m[0] = r00
        m[1] = r01
        m[2] = r02
        m[STRIDE] = r10
        m[STRIDE + 1] = r11
        m[STRIDE + 2] = r12
        m[2 * STRIDE] = r20
        m[2 * STRIDE + 1] = r21
        m[2 * STRIDE + 2] = r22
    }

    /** The rotation of the unit quaternion `(x, y, z, w)`, by the standard expansion. */
    private fun setRotationFromQuaternion(x: Float, y: Float, z: Float, w: Float): MountFrame {
        setRotation(
            1f - 2f * (y * y + z * z), 2f * (x * y - z * w), 2f * (x * z + y * w),
            2f * (x * y + z * w), 1f - 2f * (x * x + z * z), 2f * (y * z - x * w),
            2f * (x * z - y * w), 2f * (y * z + x * w), 1f - 2f * (x * x + y * y),
        )
        return this
    }

    private fun scaleColumns(x: Float, y: Float, z: Float) {
        for (row in 0 until ROWS) {
            m[row * STRIDE] *= x
            m[row * STRIDE + 1] *= y
            m[row * STRIDE + 2] *= z
        }
    }

    private fun setTranslation(x: Float, y: Float, z: Float) {
        m[3] = x
        m[STRIDE + 3] = y
        m[2 * STRIDE + 3] = z
    }

    override fun toString(): String = "MountFrame(${m.joinToString()})"

    private companion object {
        const val ROWS = 3
        const val STRIDE = 4
        const val SIZE = ROWS * STRIDE

        /** A quarter turn, the angle about Y that points a socket's own X straight up or down. */
        const val HALF_PI = (kotlin.math.PI / 2.0).toFloat()

        /**
         * How near 1 the third axis's Z part has to be to count as straight up or down.
         *
         * Below this the two `atan2`s that recover the turns about X and Z are dividing by a
         * cosine near zero, and the angles they give swing wildly for a hair of movement.
         */
        const val STRAIGHT_UP = 0.99999f

        /** A zero-length axis divides nothing: the rotation of a collapsed axis is not defined. */
        fun nonZero(scale: Float): Float = if (scale == 0f) 1f else scale
    }
}
