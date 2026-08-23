package dev.wildware.udea.agent.host.overlay

/**
 * An [OverlayCanvas] that records what was drawn instead of drawing it.
 *
 * The overlay's decisions - what is on screen at which verbosity, which marker shape, which
 * colour, whether a stale anchor drew anything at all - are the part with defects in it, and
 * they are all decided before a pixel is touched. So they are asserted here, with no display,
 * and `OverlayCaptureIsolationTest` separately proves the pixel path against a real GL context.
 *
 * The same split `udea-render` uses for its own systems: `RecordingBatch` for what was drawn,
 * `udeaGlTest` for whether it reached the right surface.
 */
internal class RecordingCanvas(
    override val width: Float = 960f,
    override val height: Float = 540f,
    override val lineHeight: Float = 16f,
    /** Pixels per character, so [measure] is a pure function a test can predict. */
    private val charWidth: Float = 7f,
) : OverlayCanvas {

    /** Every draw, in order. */
    val draws: MutableList<Draw> = ArrayList()

    /** Just the text that was drawn, in order. */
    val texts: List<String> get() = draws.filterIsInstance<Draw.Text>().map { it.text }

    val rings: List<Draw.Ring> get() = draws.filterIsInstance<Draw.Ring>()

    val crosses: List<Draw.Cross> get() = draws.filterIsInstance<Draw.Cross>()

    val fills: List<Draw.Fill> get() = draws.filterIsInstance<Draw.Fill>()

    fun clear() {
        draws.clear()
    }

    override fun measure(text: CharSequence): Float = text.length * charWidth

    override fun fill(x: Float, y: Float, w: Float, h: Float, rgba: Int) {
        draws += Draw.Fill(x, y, w, h, rgba)
    }

    override fun text(x: Float, y: Float, text: CharSequence, rgba: Int) {
        draws += Draw.Text(x, y, text.toString(), rgba)
    }

    override fun ring(cx: Float, cy: Float, radius: Float, thickness: Float, rgba: Int) {
        draws += Draw.Ring(cx, cy, radius, thickness, rgba)
    }

    override fun cross(x: Float, y: Float, size: Float, thickness: Float, rgba: Int) {
        draws += Draw.Cross(x, y, size, thickness, rgba)
    }

    /** One recorded draw call. */
    sealed interface Draw {
        class Fill(val x: Float, val y: Float, val w: Float, val h: Float, val rgba: Int) : Draw
        class Text(val x: Float, val y: Float, val text: String, val rgba: Int) : Draw
        class Ring(val cx: Float, val cy: Float, val radius: Float, val thickness: Float, val rgba: Int) : Draw
        class Cross(val x: Float, val y: Float, val size: Float, val thickness: Float, val rgba: Int) : Draw
    }
}

/** A key a test presses and releases by hand. Stands in for real hardware. */
internal class FakeKeys : HardwareKeyState {

    var down: Boolean = false

    override fun isOverlayKeyDown(): Boolean = down

    /** One complete press: down for one poll, then up. */
    fun press(control: OverlayVerbosityControl) {
        down = true
        control.poll()
        down = false
        control.poll()
    }
}

/** World coordinates straight through to screen, so a marker's position is predictable. */
internal class IdentityProjector(
    /** Points outside this square project to `false`, as an off-screen entity does. */
    private val bound: Float = 10_000f,
) : WorldProjector {
    override fun project(worldX: Float, worldY: Float, out: FloatArray): Boolean {
        if (worldX !in -bound..bound || worldY !in -bound..bound) return false
        out[0] = worldX
        out[1] = worldY
        return true
    }
}

/** A locator over a table a test fills in. An absent id is a stale generation. */
internal class MapLocator(private val positions: MutableMap<Int, Pair<Float, Float>> = HashMap()) :
    EntityLocator {

    fun put(netId: Int, x: Float, y: Float) {
        positions[netId] = x to y
    }

    /** Simulates the entity being destroyed and its slot recycled under a new generation. */
    fun remove(netId: Int) {
        positions.remove(netId)
    }

    override fun locate(packedNetId: Int, out: FloatArray): Boolean {
        val found = positions[packedNetId] ?: return false
        out[0] = found.first
        out[1] = found.second
        return true
    }
}
