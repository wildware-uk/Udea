package dev.wildware.udea.render.view

import de.fabmax.kool.pipeline.OffscreenPass
import dev.wildware.composegl.ui.widget.SceneDrawScope
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.CaptureResult
import dev.wildware.udea.render.capture.CaptureStalledException
import dev.wildware.udea.render.capture.FrameCaptureSlot
import dev.wildware.udea.render.draw.Projection2D
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteBatch2D
import dev.wildware.udea.render.draw.SpriteRecord
import dev.wildware.udea.render.draw.SpriteRegion
import dev.wildware.udea.render.draw.SpriteTexture
import dev.wildware.udea.render.kool.Letterbox
import dev.wildware.udea.render.kool.ViewportPass
import dev.wildware.udea.render.model.ModelPreview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * One editor view of the world (issue #234): the Game tab or the Scene tab, drawn into a picture of
 * its own that a ComposeGL `SceneView` shows.
 *
 * ## Two kinds
 *
 * - **The Game tab** ([camera] is `null`) is the capturable frame itself, copied into this view's
 *   picture, then the gizmos over it when [showGizmos] is on - off by default. It is exactly what the
 *   game shows, because it is the same pixels.
 * - **The Scene tab** has an [EditorCamera]. The pipeline runs the world's render systems a second
 *   time into this view, with `CameraRig`'s projection swapped for the editor camera's and every
 *   system except the `UI` phase's, then the gizmos. The same tick, the same alpha, the same systems:
 *   only the camera differs.
 *
 * ## Why a capture never contains a gizmo
 *
 * Three facts about objects, the same shape as the agent overlay's (spec 3.7):
 *
 * - the gizmos are drawn through this view's own [SpriteBatch2D], whose record is this view's;
 * - this view's record is read by this view's own Kool pass and by no other;
 * - `render.screenshot` reads the capturable pass, and [capture] reads this one.
 *
 * So `render.screenshot` has no route to a gizmo, and `editor.screenshot` shows a view as it is drawn,
 * gizmos included. `GlWorldViewportTest` holds both halves.
 *
 * ## Threads
 *
 * Render thread for everything but [capture], which any thread may call - the same rule as the
 * pipeline's own capture slot.
 */
public class WorldViewport internal constructor(
    /** The Scene tab's camera, or `null` for the Game tab, which is seen through the game's own. */
    public val camera: EditorCamera?,
    target: OffscreenTarget,
    /** What this view's pass draws: the world (Scene) or the frame (Game), then the gizmos. */
    internal val record: SpriteRecord,
    /** Draws into [record]: the Game tab's copy of the frame, and every gizmo. */
    private val batch: SpriteBatch2D,
    /** The capturable frame as a sprite, for the Game tab; `null` in the Scene tab, or with no GL. */
    private val frame: SpriteRegion?,
    /** Reads this view's pass back, for `editor.screenshot`; `null` with no GL behind it. */
    internal val captures: FrameCaptureSlot?,
    /** The Kool half: the pass, the blit, the release. `null` for a view built with no GL. */
    private val kool: ViewportPass?,
) {

    /**
     * The picture's size. It opens at the capturable frame's, and follows the `SceneView` it is
     * shown in from then on ([resizeTo]); a Game tab takes the frame with it.
     */
    internal var target: OffscreenTarget = target
        private set

    /** The size [resizeTo] last asked for, applied by the pipeline at the top of a frame. 0 for none. */
    internal var wantedWidth: Int = 0
        private set

    internal var wantedHeight: Int = 0
        private set

    /** What is drawn over the world. `null` draws none. Render thread. */
    public var gizmos: GizmoLayer? = null

    /**
     * Whether [gizmos] are drawn. On in the Scene tab; off in the Game tab until its overlay toggle
     * is turned on - and even then they are only drawn there, never pressed.
     */
    public var showGizmos: Boolean = camera != null

    /**
     * What this view shows in place of the simulated pose (issue #243): a scrub preview of one
     * entity, or a model asset on its own. A view setting like [camera], so it changes this view's
     * picture and nothing else - see [ModelPreview]. `null`, the default, shows the world as
     * simulated. Render thread.
     *
     * @throws IllegalArgumentException when set on the Game tab: that tab is the capturable frame,
     *   which shows the simulation and nothing else.
     */
    public var modelPreview: ModelPreview? = null
        set(value) {
            require(value == null || camera != null) { "$this is the Game tab, which shows the simulated pose only" }
            field = value
        }

    /** Width of the picture, in pixels. */
    public val width: Int get() = target.width

    /** Height of the picture, in pixels. */
    public val height: Int get() = target.height

    private val canvas = GizmoCanvas(batch, camera)

    private val closers = ArrayList<() -> Unit>()

    private var closed = false

    /**
     * Copies this view into the picture [scene] is drawing, and asks for this view to be drawn at
     * that picture's size from the next frame on ([resizeTo]), so it fills the picture with no bars.
     * Until it is, the copy is letterboxed. Call it inside a `SceneView`'s draw block, like
     * `WorldView.drawInto`.
     */
    public fun drawInto(scene: SceneDrawScope) {
        val width = scene.width
        val height = scene.height
        resizeTo(width, height)
        val pass = kool ?: return
        scene.raw { pass.blitInto(width, height) }
    }

    /**
     * Asks for the picture to be [width] x [height] pixels from the next frame on (issue #234): the
     * view's shape is the rectangle it is shown in, not the game's launch size. The Scene tab's camera
     * fits whatever size it is. A Game tab resizes the capturable frame with it, because the Game tab
     * is that frame: the game is drawn at the size of the tab, `render.screenshot` included. Render
     * thread. A size of zero or less is no size, and is ignored.
     */
    public fun resizeTo(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        wantedWidth = width
        wantedHeight = height
    }

    /** True when [resizeTo] has asked for a size the picture is not yet. */
    internal val wantsResize: Boolean
        get() = wantedWidth > 0 && (wantedWidth != target.width || wantedHeight != target.height)

    /** Makes the picture the size [resizeTo] asked for. The pipeline's, at the top of a frame. */
    internal fun applySize() {
        if (!wantsResize) return
        kool?.resize(wantedWidth, wantedHeight)
        target = OffscreenTarget(wantedWidth, wantedHeight)
    }

    /**
     * Writes into [out] the view pixel under point ([pictureX], [pictureY]) of a `SceneView`'s
     * picture of [pictureWidth] x [pictureHeight], measured from its top left as ComposeGL reports a
     * pointer. The inverse of [drawInto]'s letterbox.
     *
     * @return false when the point is on a letterbox bar rather than on the view.
     */
    public fun toView(pictureX: Float, pictureY: Float, pictureWidth: Int, pictureHeight: Int, out: ViewPoint): Boolean {
        if (pictureWidth <= 0 || pictureHeight <= 0) return false
        val fit = Letterbox.fit(width, height, pictureWidth, pictureHeight)
        val fromBottom = pictureHeight - pictureY
        out.x = (pictureX - fit.x) * width / fit.width
        out.y = (fromBottom - fit.y) * height / fit.height
        return out.x in 0f..width.toFloat() && out.y in 0f..height.toFloat()
    }

    /**
     * Offers a press at view pixel ([viewX], [viewY]) to the gizmos, and says whether one took it.
     *
     * Always false in the Game tab: its pointer is the game's, and a gizmo drawn there is read-only.
     */
    public fun pressGizmo(viewX: Float, viewY: Float): Boolean {
        if (camera == null || !showGizmos) return false
        val layer = gizmos ?: return false
        return layer.press(canvas, viewX, viewY)
    }

    /**
     * Captures the next frame of this view, gizmos included, as a PNG - what `editor.screenshot`
     * files. Any thread.
     */
    public fun capture(): Deferred<CaptureResult> =
        captures?.submit(CaptureRequest()) ?: CompletableDeferred<CaptureResult>().apply {
            completeExceptionally(CaptureStalledException("$this has no pass to read: it was built with no render context"))
        }

    /** Takes this view off the pipeline and releases its pass. Render thread. Idempotent. */
    public fun close() {
        if (closed) return
        closed = true
        captures?.close()
        // The pass before what it samples: a released texture a listed pass still draws is a GL
        // error on the next frame.
        kool?.release()
        for (index in closers.indices.reversed()) closers[index]()
        closers.clear()
    }

    /** True once [close] has run. */
    public val isClosed: Boolean get() = closed

    /** Runs [action] when this view closes: how the pipeline and the 3D stage forget it. */
    internal fun onClose(action: () -> Unit) {
        check(!closed) { "$this is closed" }
        closers += action
    }

    /** Makes this view's pass draw after [pass]: a 3D stage's pass for this view. */
    internal fun dependsOn(pass: OffscreenPass) {
        kool?.dependsOn(pass)
    }

    /** Forgets the last frame. The first thing a frame does to a view. */
    internal fun begin() {
        record.clear()
    }

    /** The Game tab's picture: the capturable frame, full size. */
    internal fun drawFrame() {
        val picture = frame ?: return
        batch.beginPixels()
        try {
            batch.draw(picture, 0f, 0f, width.toFloat(), height.toFloat(), Rgba.WHITE)
        } finally {
            batch.end()
        }
    }

    /** Draws the gizmos, if there are any and they are shown, seen through [game] in the Game tab. */
    internal fun drawGizmos(game: Projection2D?) {
        canvas.prepare(width, height, game)
        val layer = gizmos ?: return
        if (!showGizmos) return
        canvas.drawWith(layer)
    }

    override fun toString(): String = "WorldViewport(${if (camera == null) "game" else "scene"}, ${width}x$height)"

    public companion object {

        /**
         * A view with no render context behind it: [camera] is fitted to [width] x [height], a
         * pointer maps and a press reaches [gizmos] exactly as on a live view, but no pass draws it,
         * [drawInto] shows nothing and [capture] fails. What an editor window is built on when there
         * is no GL - its headless tests, and a launcher's own.
         */
        public fun detached(camera: EditorCamera?, width: Int, height: Int): WorldViewport {
            val record = SpriteRecord()
            val view = WorldViewport(
                camera = camera,
                target = OffscreenTarget(width, height),
                record = record,
                batch = SpriteBatch2D(SpriteTexture.whitePixel("udea-detached-view-white"), home = record),
                frame = null,
                captures = null,
                kool = null,
            )
            camera?.fit(width, height)
            view.canvas.prepare(width, height, null)
            return view
        }
    }
}
