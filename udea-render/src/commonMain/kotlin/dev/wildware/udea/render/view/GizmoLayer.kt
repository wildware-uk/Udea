package dev.wildware.udea.render.view

import dev.wildware.udea.render.draw.Projection2D
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteBatch2D

/**
 * What a view draws over the world: the editor's gizmos (epic #231).
 *
 * This is the seam, and deliberately no more than one. Issue #234 decides *where* a gizmo is drawn -
 * into an editor view, after the world, and never into the frame a capture reads - and the public
 * gizmo API that decides *what* is drawn (`Gizmo`, handles, shapes) is issue #233's, built on top of
 * this. A layer is handed a [GizmoCanvas] once per frame per view it is set on.
 *
 * ## Why a capture cannot contain one
 *
 * The canvas draws with a batch whose record only a view's own pass reads (`WorldViewport`), and a
 * capture reads the capturable pass. No layer holds anything that reaches the capturable pass, so a
 * gizmo in a screenshot is not a thing somebody has to remember to prevent: there is no route.
 */
public interface GizmoLayer {

    /** Draws this frame's gizmos. Render thread. */
    public fun draw(canvas: GizmoCanvas)

    /**
     * A pointer went down at view pixel ([viewX], [viewY]) in a view where gizmos are interactive -
     * the Scene tab. Returns true when a gizmo took it, and the view then does nothing else with it.
     *
     * Never called from the Game tab, whose pointer belongs to the game even where a gizmo is drawn.
     */
    public fun press(canvas: GizmoCanvas, viewX: Float, viewY: Float): Boolean = false
}

/**
 * Where a [GizmoLayer] draws: one view, in its pixels, with the camera it is seen through.
 *
 * Positions are world-space and turned into view pixels by [project]; sizes are view pixels, so a
 * handle keeps its size on screen however far the camera is zoomed out (spec section 1).
 */
public class GizmoCanvas internal constructor(
    private val batch: SpriteBatch2D,
    /** The Scene tab's camera, or `null` in the Game tab, which is seen through [gameProjection]. */
    private val editor: EditorCamera?,
) {

    /** The game's own 2D projection, for the Game tab. Copied each frame before [GizmoLayer.draw]. */
    private val gameProjection = Projection2D()

    /** Width of the view, in pixels. */
    public var width: Int = 0
        private set

    /** Height of the view, in pixels. */
    public var height: Int = 0
        private set

    /** Which camera the view is seen through: the Game tab is always [ViewDimension.TwoD]. */
    public val dimension: ViewDimension get() = editor?.dimension ?: ViewDimension.TwoD

    /**
     * Writes into [out] the view pixel world point ([x], [y], [z]) is drawn at.
     *
     * @return false when the point is drawn nowhere - behind the 3D eye.
     */
    public fun project(x: Float, y: Float, z: Float, out: ViewPoint): Boolean {
        val camera = editor ?: run {
            out.x = gameProjection.pixelX(x)
            out.y = gameProjection.pixelY(y)
            return true
        }
        return camera.project(x, y, z, out)
    }

    /** Fills a rectangle of the view, in view pixels from the bottom left. Inside [GizmoLayer.draw] only. */
    public fun fill(x: Float, y: Float, width: Float, height: Float, colour: Rgba) {
        batch.fill(x, y, width, height, colour)
    }

    /**
     * Draws a straight line from ([x0], [y0]) to ([x1], [y1]), [thickness] pixels wide, in view
     * pixels from the bottom left: a bone, a spoke, a tether (issue #243). Inside [GizmoLayer.draw]
     * only.
     */
    public fun line(x0: Float, y0: Float, x1: Float, y1: Float, thickness: Float, colour: Rgba) {
        batch.line(x0, y0, x1, y1, thickness, colour)
    }

    /** Sizes the canvas and takes the Game tab's projection, before a layer draws or is pressed. */
    internal fun prepare(width: Int, height: Int, game: Projection2D?) {
        this.width = width
        this.height = height
        if (game != null) gameProjection.set(game) else gameProjection.setIdentity()
    }

    /** Runs [layer] with the batch drawing in view pixels. */
    internal fun drawWith(layer: GizmoLayer) {
        batch.beginPixels()
        try {
            layer.draw(this)
        } finally {
            batch.end()
        }
    }

    override fun toString(): String = "GizmoCanvas(${width}x$height, ${if (editor == null) "game camera" else "editor camera"})"
}
