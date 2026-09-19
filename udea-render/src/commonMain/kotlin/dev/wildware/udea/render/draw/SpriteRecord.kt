package dev.wildware.udea.render.draw

/**
 * One frame's worth of sprites as a [SpriteBatch2D] recorded them: pixel rectangles, texture
 * rectangles and tints, in draw order, grouped into runs that share a texture.
 *
 * ## Why this is apart from the batch
 *
 * A batch is what a renderer holds; a record is where its draws land and what a Kool node reads.
 * They were one object until issue #234, when a second reader arrived: an editor's Scene view runs
 * the world's render systems again through its own camera, and those systems draw with the batch
 * they were built with. So the batch points at its [SpriteBatch2D.home] record by default and at a
 * view's record for the length of the Scene view's pass, and each Kool node reads exactly one record.
 * The capturable frame's node reads the capturable batch's home record and nothing else.
 *
 * ## Plain arrays, cleared rather than reallocated
 *
 * Written per sprite per frame. Growable arrays that are cleared keep the steady state free of
 * garbage, which `RenderAllocationTest` measures.
 */
internal class SpriteRecord {

    /** Pixel-space instance data, [SpriteBatch2D.FLOATS_PER_INSTANCE] per draw. */
    var floats: FloatArray = FloatArray(INITIAL_INSTANCES * SpriteBatch2D.FLOATS_PER_INSTANCE)
        private set

    /** One packed tint per draw. */
    var tints: IntArray = IntArray(INITIAL_INSTANCES)
        private set

    /** Draws recorded since the last [clear]. */
    var instanceCount: Int = 0
        private set

    /** The texture of each run. */
    private val runTextures = ArrayList<SpriteTexture>()

    /** Index of the first instance of each run; the run ends where the next begins. */
    private var runStarts: IntArray = IntArray(INITIAL_RUNS)

    /** How many runs - and so how many draw calls - the record needs. */
    val runCount: Int get() = runTextures.size

    /** The texture every draw in run [run] samples. */
    fun runTexture(run: Int): SpriteTexture = runTextures[run]

    /** First instance index of run [run]. */
    fun runStart(run: Int): Int = runStarts[run]

    /** One past the last instance index of run [run]. */
    fun runEnd(run: Int): Int = if (run + 1 < runTextures.size) runStarts[run + 1] else instanceCount

    /** Forgets every draw. */
    fun clear() {
        instanceCount = 0
        runTextures.clear()
    }

    /** Appends one draw, already in pixels, starting a run when the texture changes. */
    fun add(
        texture: SpriteTexture,
        px: Float, py: Float, pw: Float, ph: Float,
        ox: Float, oy: Float, rotation: Float,
        u0: Float, v0: Float, du: Float, dv: Float,
        tint: Rgba,
    ) {
        if (runTextures.isEmpty() || runTextures[runTextures.size - 1] !== texture) startRun(texture)
        ensureInstanceCapacity(instanceCount + 1)
        var at = instanceCount * SpriteBatch2D.FLOATS_PER_INSTANCE
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
        floats = floats.copyOf(grown * SpriteBatch2D.FLOATS_PER_INSTANCE)
        tints = tints.copyOf(grown)
    }

    override fun toString(): String = "SpriteRecord($instanceCount instances in $runCount runs)"

    private companion object {
        const val INITIAL_INSTANCES = 256
        const val INITIAL_RUNS = 16
    }
}
