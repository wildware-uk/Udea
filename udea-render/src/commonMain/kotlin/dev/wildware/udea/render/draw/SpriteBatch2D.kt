package dev.wildware.udea.render.draw

/**
 * Where a frame's sprites are handed over: position, size, texture region, tint, rotation.
 *
 * ## What Kool does and does not give a 2D engine
 *
 * Kool draws 2D as meshes on a plane and has no sprite API of its own (spec section 4). This is
 * the thin layer that makes one: a renderer calls [begin], [draw] and [end] exactly as it called
 * LibGDX's `SpriteBatch`, and the batch records one **instance** per draw - pixel rectangle,
 * origin, rotation, texture rectangle, tint. A Kool node (`SpriteBatchNode`) turns the record
 * into one instanced quad mesh per run of draws that share a texture, drawn with Kool's
 * `KslUnlitShader`.
 *
 * ## Why runs and not one mesh per texture
 *
 * A 2D frame is painted back to front, and a sprite from the atlas drawn after a glyph from the
 * font must land on top of it. One mesh per texture would draw every atlas sprite and then every
 * glyph, whatever order they were drawn in. So consecutive draws from one texture share a mesh,
 * and a texture change starts the next one - which is the flush-on-texture-switch rule LibGDX's
 * `SpriteBatch` had, and the reason a packed atlas is still the cheap path: a frame drawn from one
 * page is one draw call.
 *
 * ## Why the record is plain arrays
 *
 * It is written per sprite per frame. Growable `FloatArray`s that are cleared rather than
 * reallocated put nothing on the heap in steady state, which `RenderAllocationTest` measures, and
 * they keep every decision this class makes - where, which texels, which order - assertable in a
 * plain JVM with no Kool object anywhere.
 *
 * ## Coordinates
 *
 * [begin] takes a [Projection2D] from the units the caller draws in (world units, for a camera) to
 * the target's pixels, origin **bottom left**. [draw] applies it and records pixels, so the Kool
 * side needs no camera per renderer: one pixel-space camera serves every pass.
 */
public class SpriteBatch2D internal constructor(
    /** The one white texel [fill] stretches and tints. */
    private val whitePixel: SpriteTexture,
) {

    /** Pixel-space instance data, [FLOATS_PER_INSTANCE] per draw. */
    internal var floats: FloatArray = FloatArray(INITIAL_INSTANCES * FLOATS_PER_INSTANCE)
        private set

    /** One packed tint per draw. */
    internal var tints: IntArray = IntArray(INITIAL_INSTANCES)
        private set

    /** Draws recorded since the last [clear]. */
    internal var instanceCount: Int = 0
        private set

    /** The texture of each run. */
    private val runTextures = ArrayList<SpriteTexture>()

    /** Index of the first instance of each run; the run ends where the next begins. */
    private var runStarts: IntArray = IntArray(INITIAL_RUNS)

    private val projection = Projection2D()

    /** True between [begin] and [end]. */
    public var isDrawing: Boolean = false
        private set

    /** How many runs - and so how many draw calls - the recorded frame needs. */
    internal val runCount: Int get() = runTextures.size

    /** The texture every draw in run [run] samples. */
    internal fun runTexture(run: Int): SpriteTexture = runTextures[run]

    /** First instance index of run [run]. */
    internal fun runStart(run: Int): Int = runStarts[run]

    /** One past the last instance index of run [run]. */
    internal fun runEnd(run: Int): Int = if (run + 1 < runTextures.size) runStarts[run + 1] else instanceCount

    /**
     * Starts a pass of draws in the units [projection] maps to pixels.
     *
     * The projection is **copied**: a caller that reuses one object across frames and mutates it
     * after this returns does not move what was already drawn.
     *
     * @throws IllegalStateException if the batch is already drawing - a renderer that forgot
     *   [end] would otherwise have its draws land in whatever projection came next.
     */
    public fun begin(projection: Projection2D) {
        check(!isDrawing) { "SpriteBatch2D.begin called twice without end" }
        this.projection.set(projection)
        isDrawing = true
    }

    /**
     * Starts a pass of draws in the target's own pixels: what a screen-space renderer - a
     * background, a label, the agent overlay - draws in.
     */
    public fun beginPixels() {
        check(!isDrawing) { "SpriteBatch2D.begin called twice without end" }
        projection.setIdentity()
        isDrawing = true
    }

    /**
     * Records one sprite.
     *
     * @param x left edge of the unrotated quad, in the [begin] projection's units.
     * @param y bottom edge.
     * @param originX the rotation origin, relative to ([x], [y]), in the same units.
     * @param rotationDegrees counter-clockwise, about the origin.
     * @param flipX mirrors the texels horizontally without a second region.
     * @param flipY mirrors them vertically.
     */
    public fun draw(
        region: SpriteRegion,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        tint: Rgba = Rgba.WHITE,
        rotationDegrees: Float = 0f,
        originX: Float = width / 2f,
        originY: Float = height / 2f,
        flipX: Boolean = false,
        flipY: Boolean = false,
    ) {
        check(isDrawing) { "SpriteBatch2D.draw called outside begin/end" }
        val texture = region.texture
        var u0 = region.u0
        var du = region.du
        // `v0` is the top edge of the region in a top-row-first image. A bottom-row-first texture
        // (a pass's colour attachment) holds the same picture upside down, so its top edge is at
        // `1 - v0` and rows run the other way.
        var v0 = if (texture.bottomRowFirst) 1f - region.v0 else region.v0
        var dv = if (texture.bottomRowFirst) -region.dv else region.dv
        if (flipX) {
            u0 += du
            du = -du
        }
        if (flipY) {
            v0 += dv
            dv = -dv
        }
        record(
            texture,
            projection.pixelX(x), projection.pixelY(y),
            width * projection.scaleX, height * projection.scaleY,
            originX * projection.scaleX, originY * projection.scaleY,
            rotationDegrees,
            u0, v0, du, dv,
            tint,
        )
    }

    /** Records a solid rectangle: [whitePixel] stretched and tinted. */
    public fun fill(x: Float, y: Float, width: Float, height: Float, tint: Rgba) {
        check(isDrawing) { "SpriteBatch2D.fill called outside begin/end" }
        record(
            whitePixel,
            projection.pixelX(x), projection.pixelY(y),
            width * projection.scaleX, height * projection.scaleY,
            0f, 0f, 0f,
            0f, 0f, 1f, 1f,
            tint,
        )
    }

    /** Ends the pass [begin] started. */
    public fun end() {
        check(isDrawing) { "SpriteBatch2D.end called without begin" }
        isDrawing = false
    }

    /**
     * Forgets the recorded frame. Called by the frame surface before the first renderer draws,
     * never by a renderer: a renderer that cleared the batch would erase every renderer before it.
     */
    internal fun clear() {
        instanceCount = 0
        runTextures.clear()
        isDrawing = false
    }

    /** The release hook for the white texel this batch owns. */
    internal fun releaseOwned() {
        whitePixel.release()
    }

    private fun record(
        texture: SpriteTexture,
        px: Float, py: Float, pw: Float, ph: Float,
        ox: Float, oy: Float, rotation: Float,
        u0: Float, v0: Float, du: Float, dv: Float,
        tint: Rgba,
    ) {
        if (runTextures.isEmpty() || runTextures[runTextures.size - 1] !== texture) startRun(texture)
        ensureInstanceCapacity(instanceCount + 1)
        var at = instanceCount * FLOATS_PER_INSTANCE
        val data = floats
        data[at++] = px
        data[at++] = py
        data[at++] = pw
        data[at++] = ph
        data[at++] = ox
        data[at++] = oy
        data[at++] = rotation
        data[at++] = u0
        data[at++] = v0
        data[at++] = du
        data[at] = dv
        tints[instanceCount] = tint.packed
        instanceCount++
    }

    private fun startRun(texture: SpriteTexture) {
        val run = runTextures.size
        if (run == runStarts.size) runStarts = runStarts.copyOf(run * 2)
        runStarts[run] = instanceCount
        runTextures += texture
    }

    private fun ensureInstanceCapacity(instances: Int) {
        if (instances <= tints.size) return
        val grown = tints.size * 2
        floats = floats.copyOf(grown * FLOATS_PER_INSTANCE)
        tints = tints.copyOf(grown)
    }

    override fun toString(): String = "SpriteBatch2D($instanceCount instances in $runCount runs)"

    internal companion object {

        /** `x, y, width, height, originX, originY, rotation, u0, v0, du, dv`. */
        const val FLOATS_PER_INSTANCE: Int = 11

        const val X: Int = 0
        const val Y: Int = 1
        const val WIDTH: Int = 2
        const val HEIGHT: Int = 3
        const val ORIGIN_X: Int = 4
        const val ORIGIN_Y: Int = 5
        const val ROTATION: Int = 6
        const val U0: Int = 7
        const val V0: Int = 8
        const val DU: Int = 9
        const val DV: Int = 10

        private const val INITIAL_INSTANCES = 256
        private const val INITIAL_RUNS = 16
    }
}

/**
 * An affine map from the units a renderer draws in to a target's pixels: `pixel = unit * scale +
 * offset` on each axis, origin bottom left.
 *
 * Mutable and reused, because a camera recomputes it every frame and a renderer passes it to
 * [SpriteBatch2D.begin] every frame; [SpriteBatch2D.begin] copies it.
 */
public class Projection2D(
    public var scaleX: Float = 1f,
    public var scaleY: Float = 1f,
    public var offsetX: Float = 0f,
    public var offsetY: Float = 0f,
) {

    /** Copies [other] into this one. */
    public fun set(other: Projection2D) {
        scaleX = other.scaleX
        scaleY = other.scaleY
        offsetX = other.offsetX
        offsetY = other.offsetY
    }

    /** Makes this the identity: units are pixels. */
    public fun setIdentity() {
        scaleX = 1f
        scaleY = 1f
        offsetX = 0f
        offsetY = 0f
    }

    /** The pixel column of unit-space [x]. */
    public fun pixelX(x: Float): Float = x * scaleX + offsetX

    /** The pixel row of unit-space [y], counted from the bottom. */
    public fun pixelY(y: Float): Float = y * scaleY + offsetY

    override fun toString(): String = "Projection2D(scale=($scaleX, $scaleY), offset=($offsetX, $offsetY))"
}
