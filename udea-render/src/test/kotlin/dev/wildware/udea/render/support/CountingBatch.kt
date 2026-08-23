package dev.wildware.udea.render.support

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Affine2
import com.badlogic.gdx.math.Matrix4

/**
 * A [Batch] that counts calls and allocates nothing.
 *
 * ## Why not [RecordingBatch]
 *
 * [RecordingBatch] is a `java.lang.reflect.Proxy`, which is the right trade for the port tests:
 * `Batch` has forty-odd methods, thirty of which are `draw` overloads nobody calls, and writing
 * them out would bury the handful that carry the assertions. But every proxied call allocates
 * an `Object[]` for its arguments and boxes each `float` into it, and it appends a `Draw`
 * object per draw call — so measuring a renderer's allocation through one measures the
 * recorder. `RenderAllocationTest` needs a batch whose own allocation is zero, and the only way
 * to get that is to write the interface out by hand.
 *
 * Nothing here is asserted on beyond the counters: what was drawn, where, and in what order is
 * `DrawSystemPortTest`'s job, through the proxy. This one answers "how much garbage did a
 * steady-state frame make", and its whole contract is that the answer is not about itself.
 */
internal class CountingBatch : Batch {

    /** Draw calls since construction. Proves the measured frames actually drew something. */
    var drawCalls: Long = 0L
        private set

    /** `begin()` calls since construction. */
    var beginCalls: Long = 0L
        private set

    private val color = Color(Color.WHITE)
    private val projection = Matrix4()
    private val transform = Matrix4()
    private var drawing = false
    private var packed: Float = Color.WHITE.toFloatBits()
    private var blending = true

    override fun begin() {
        drawing = true
        beginCalls++
    }

    override fun end() {
        drawing = false
    }

    override fun setColor(tint: Color) {
        color.set(tint)
    }

    override fun setColor(r: Float, g: Float, b: Float, a: Float) {
        color.set(r, g, b, a)
    }

    override fun getColor(): Color = color

    override fun setPackedColor(packedColor: Float) {
        packed = packedColor
    }

    override fun getPackedColor(): Float = packed

    // --- the draw overloads: counted, and nothing else ------------------------------------

    override fun draw(
        texture: Texture,
        x: Float,
        y: Float,
        originX: Float,
        originY: Float,
        width: Float,
        height: Float,
        scaleX: Float,
        scaleY: Float,
        rotation: Float,
        srcX: Int,
        srcY: Int,
        srcWidth: Int,
        srcHeight: Int,
        flipX: Boolean,
        flipY: Boolean,
    ) {
        drawCalls++
    }

    override fun draw(
        texture: Texture,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        srcX: Int,
        srcY: Int,
        srcWidth: Int,
        srcHeight: Int,
        flipX: Boolean,
        flipY: Boolean,
    ) {
        drawCalls++
    }

    override fun draw(
        texture: Texture,
        x: Float,
        y: Float,
        srcX: Int,
        srcY: Int,
        srcWidth: Int,
        srcHeight: Int,
    ) {
        drawCalls++
    }

    override fun draw(
        texture: Texture,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        u: Float,
        v: Float,
        u2: Float,
        v2: Float,
    ) {
        drawCalls++
    }

    override fun draw(texture: Texture, x: Float, y: Float) {
        drawCalls++
    }

    override fun draw(texture: Texture, x: Float, y: Float, width: Float, height: Float) {
        drawCalls++
    }

    override fun draw(texture: Texture, spriteVertices: FloatArray, offset: Int, count: Int) {
        drawCalls++
    }

    override fun draw(region: TextureRegion, x: Float, y: Float) {
        drawCalls++
    }

    override fun draw(region: TextureRegion, x: Float, y: Float, width: Float, height: Float) {
        drawCalls++
    }

    override fun draw(
        region: TextureRegion,
        x: Float,
        y: Float,
        originX: Float,
        originY: Float,
        width: Float,
        height: Float,
        scaleX: Float,
        scaleY: Float,
        rotation: Float,
    ) {
        drawCalls++
    }

    override fun draw(
        region: TextureRegion,
        x: Float,
        y: Float,
        originX: Float,
        originY: Float,
        width: Float,
        height: Float,
        scaleX: Float,
        scaleY: Float,
        rotation: Float,
        clockwise: Boolean,
    ) {
        drawCalls++
    }

    override fun draw(region: TextureRegion, width: Float, height: Float, transform: Affine2) {
        drawCalls++
    }

    // --- state a renderer sets, kept so a getter answers honestly -------------------------

    override fun flush(): Unit = Unit

    override fun disableBlending() {
        blending = false
    }

    override fun enableBlending() {
        blending = true
    }

    override fun setBlendFunction(srcFunc: Int, dstFunc: Int): Unit = Unit

    override fun setBlendFunctionSeparate(
        srcFuncColor: Int,
        dstFuncColor: Int,
        srcFuncAlpha: Int,
        dstFuncAlpha: Int,
    ): Unit = Unit

    override fun getBlendSrcFunc(): Int = 0

    override fun getBlendDstFunc(): Int = 0

    override fun getBlendSrcFuncAlpha(): Int = 0

    override fun getBlendDstFuncAlpha(): Int = 0

    override fun getProjectionMatrix(): Matrix4 = projection

    override fun getTransformMatrix(): Matrix4 = transform

    /** `Matrix4.set` is an array copy: assigning a projection per frame allocates nothing. */
    override fun setProjectionMatrix(projection: Matrix4) {
        this.projection.set(projection)
    }

    override fun setTransformMatrix(transform: Matrix4) {
        this.transform.set(transform)
    }

    override fun setShader(shader: ShaderProgram?): Unit = Unit

    override fun getShader(): ShaderProgram? = null

    override fun isBlendingEnabled(): Boolean = blending

    override fun isDrawing(): Boolean = drawing

    override fun dispose(): Unit = Unit
}
