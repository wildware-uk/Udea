package dev.wildware.udea.render.support

import dev.wildware.udea.render.model.ModelCamera

/**
 * Where a [ModelCamera] puts a world point on the picture, from the matrices it writes and nothing
 * else (issue #257).
 *
 * This is the un-projection of issue #262 run forwards: it multiplies a world point by the camera's
 * view matrix and then its projection matrix, exactly as a game's picking would, and divides by `w`.
 * Written once here because two suites read it - `ModelCameraTest`, which checks the arithmetic
 * against what the shape of a projection demands, and `GlIsoCameraTest`, which checks the pixels it
 * predicts against the pixels a real Kool context drew.
 */
object CameraProjections {

    /** A point after a matrix: the four numbers, `w` not yet divided out. */
    data class Clip(val x: Float, val y: Float, val z: Float, val w: Float) {

        /** This point in normalised device coordinates: -1 to 1 across, up and into the screen. */
        val ndcX: Float get() = x / w
        val ndcY: Float get() = y / w
        val ndcZ: Float get() = z / w
    }

    /** Where on a [width] x [height] picture a point sits, in pixels, y down as an image is read. */
    data class Pixel(val x: Float, val y: Float)

    /** [x], [y], [z] as `w = 1`, through the column-major 4x4 [m]. */
    fun times(m: FloatArray, x: Float, y: Float, z: Float, w: Float = 1f): Clip {
        require(m.size == MATRIX_SIZE) { "a 4x4 matrix is $MATRIX_SIZE floats, this is ${m.size}" }
        return Clip(
            m[0] * x + m[4] * y + m[8] * z + m[12] * w,
            m[1] * x + m[5] * y + m[9] * z + m[13] * w,
            m[2] * x + m[6] * y + m[10] * z + m[14] * w,
            m[3] * x + m[7] * y + m[11] * z + m[15] * w,
        )
    }

    /** The world point [x], [y], [z] in the clip space of [camera] on a picture [aspect] wide per unit tall. */
    fun clipOf(camera: ModelCamera, x: Float, y: Float, z: Float, aspect: Float): Clip {
        val eye = times(camera.writeViewMatrix(FloatArray(MATRIX_SIZE)), x, y, z)
        return times(camera.writeProjectionMatrix(FloatArray(MATRIX_SIZE), aspect), eye.x, eye.y, eye.z, eye.w)
    }

    /** The world point [x], [y], [z] as a pixel of a [width] x [height] picture through [camera]. */
    fun pixelOf(camera: ModelCamera, x: Float, y: Float, z: Float, width: Int, height: Int): Pixel {
        val clip = clipOf(camera, x, y, z, width.toFloat() / height)
        return Pixel(
            (clip.ndcX + 1f) / 2f * width,
            (1f - clip.ndcY) / 2f * height,
        )
    }

    private const val MATRIX_SIZE = 16
}
