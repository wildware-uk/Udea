package dev.wildware.udea.render.kool

import de.fabmax.kool.KoolContext
import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.pipeline.AttachmentConfig
import de.fabmax.kool.pipeline.ClearColorFill
import de.fabmax.kool.pipeline.OffscreenPass
import de.fabmax.kool.pipeline.OffscreenPass2d
import de.fabmax.kool.pipeline.RenderPass
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.scene.Camera
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.OrthographicCamera
import de.fabmax.kool.scene.Scene
import de.fabmax.kool.util.Color
import dev.wildware.udea.core.SimClock
import dev.wildware.udea.render.FrameSurface
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResource
import dev.wildware.udea.render.RenderTargets
import dev.wildware.udea.render.ScreenTarget
import dev.wildware.udea.render.capture.FrameCaptureSlot
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteBatch2D
import dev.wildware.udea.render.draw.SpriteRecord
import dev.wildware.udea.render.draw.SpriteRegion
import dev.wildware.udea.render.draw.SpriteTexture
import dev.wildware.udea.render.ui.WorldView
import dev.wildware.udea.render.view.EditorCamera
import dev.wildware.udea.render.view.WorldViewport

/**
 * The Kool scene a render pipeline draws into, and the reason an overlay cannot reach a capture.
 *
 * ## Two passes, two batches
 *
 * ```
 * (a system's own passes, e.g. the 3D model pass: see ScenePasses)
 *      |
 *      v  (their colour textures, drawn through the offscreen batch)
 * OffscreenPass2d  <- offscreen batch: every RenderSystem       <- the ONLY thing a capture reads
 *                  <- then its views added by addOnTop: a CapturedUi, the game's HUD
 *      |
 *      v  (its colour texture)
 * Scene main pass  <- present batch: that texture, letterboxed to the window
 *                  <- screen batch: every OverlaySystem
 * ```
 *
 * Spec section 4 says the agent overlay "draws only to the screen target, never into a captured
 * frame". Here that is three facts about objects rather than an ordering rule. A `RenderSystem` is
 * handed the offscreen batch and an `OverlaySystem` is handed the screen batch
 * (`RenderResources` / `OverlayResources`); the screen batch's node is a child of the window's
 * scene and not of the offscreen pass's draw node, so nothing it records is ever drawn into the
 * pass; and [KoolPixelSource] reads the pass's colour texture and nothing else. No frame ordering
 * can change any of the three.
 *
 * ## Editor views
 *
 * An editor's Game and Scene tabs (issue #234) are passes of their own, made by [openView] and put
 * on this scene *beside* the capturable pass rather than before it: the capturable pass never waits
 * on one and never reads one, so the gizmos drawn into them cannot reach a capture either. A Game
 * tab's pass reads the capturable pass's picture; the other direction does not exist.
 *
 * While a view is open the capturable pass is not presented to the window at all: the window is the
 * editor's, and the world is in its views. Captures are untouched - they read the pass, not the window.
 *
 * ## Why the capture's size does not follow the window
 *
 * The pass is created at [width] x [height]. A human dragging the window changes the scene's
 * viewport, which only changes how the present batch letterboxes the texture - so a capture's
 * dimensions and framing are a property of the configuration and not of the window, and two
 * captures of the same tick are comparable whatever the window did in between.
 *
 * The one thing that resizes it is an editor's Game tab ([resize], issue #234): there the game is
 * drawn at the size of the tab it is shown in, as a game is drawn at the size of its screen.
 */
internal class KoolSurface(
    width: Int,
    height: Int,
    private val windowWidth: Int,
    private val windowHeight: Int,
) : FrameSurface, ScenePasses {

    /** Width of the pass every capture reads, in pixels. */
    var width: Int = width
        private set

    /** Height of the pass every capture reads, in pixels. */
    var height: Int = height
        private set

    private val offscreenBatch = SpriteBatch2D(SpriteTexture.whitePixel("udea-offscreen-white"))
    private val screenBatch = SpriteBatch2D(SpriteTexture.whitePixel("udea-screen-white"))
    private val presentBatch = SpriteBatch2D(SpriteTexture.whitePixel("udea-present-white"))

    private val offscreenNode = SpriteBatchNode(offscreenBatch.home, "udea-offscreen-sprites")

    /** The capturable pass. Its draw node holds the offscreen batch's meshes and nothing else. */
    val pass: OffscreenPass2d = OffscreenPass2d(
        drawNode = offscreenNode,
        attachmentConfig = AttachmentConfig {
            addColor(TexFormat.RGBA, clearColor = ClearColorFill(Color.BLACK))
            // No depth: a 2D frame is painted back to front, and an unused depth attachment is
            // memory on every frame of every capture.
            noDepth()
        },
        initialSize = Vec2i(width, height),
        name = "udea-offscreen",
    ).apply {
        camera = pixelCamera()
    }

    /** The window's scene: the presented pass, then the overlay on top of it. */
    val scene: Scene = Scene("udea-screen").apply {
        camera = pixelCamera()
        clearColor = ClearColorFill(Color.BLACK)
        addOffscreenPass(pass)
        addNode(SpriteBatchNode(presentBatch.home, "udea-present"))
        addNode(SpriteBatchNode(screenBatch.home, "udea-overlay-sprites"))
    }

    private val blit = PassBlit(pass)

    /** The capturable pass, as something a `SceneView` can show. See [WorldView]. */
    val worldView: WorldView = WorldView(blit)

    /**
     * The game's screen effects (issues #259, #266), run over the frame every `RenderSystem` drew.
     *
     * Its view is added here, while the surface is being built, and that is what puts it in the
     * right place in the frame: `addOnTop` appends, and nothing else has added a view yet, so the
     * effects run after the sprite batch and before any `CapturedUi` a game adds later. A HUD is
     * drawn over the processed picture rather than through it.
     */
    private val screenPasses = GlScreenPasses(this)

    private val screenPassView: RenderPass.View = addOnTop(SCREEN_PASS_VIEW, screenPasses::draw)

    private val presented = SpriteRegion(
        SpriteTexture.ofPassColour(
            checkNotNull(pass.colorTexture) { "the offscreen pass has no colour attachment" },
            width,
            height,
        ),
    )

    /** The pipeline's targets over this surface. */
    fun targets(): RenderTargets = RenderTargets(
        offscreen = OffscreenTarget(width, height),
        screen = ScreenTarget(windowWidth, windowHeight),
        batch = offscreenBatch,
        screenBatch = screenBatch,
        surface = this,
        pixels = KoolPixelSource(pass),
        passes = this,
        // Construction order, released in reverse: the scene goes before the batches whose
        // textures its meshes sample.
        owned = listOf(
            RenderResource { offscreenBatch.releaseOwned() },
            RenderResource { screenBatch.releaseOwned() },
            RenderResource { presentBatch.releaseOwned() },
            RenderResource { detachAndRelease() },
            // Last, so reverse-order release reaches it first: its view sits on the capturable
            // pass, and taking a view off a pass the scene has already released is a use of a
            // freed object rather than a tidy-up.
            RenderResource { releaseScreenPasses() },
        ),
        screenPasses = screenPasses,
    )

    /** Takes the effects' view off the pass and gives back every GL object the chain made. */
    private fun releaseScreenPasses() {
        removeOnTop(screenPassView)
        screenPasses.release()
    }

    private var attachedTo: KoolContext? = null

    /** Adds the scene to [ctx]. Render thread only. */
    fun attach(ctx: KoolContext) {
        check(attachedTo == null) { "$this is already attached" }
        ctx.addScene(scene)
        attachedTo = ctx
    }

    /** Takes the scene off the context before releasing it: a released scene still listed is drawn. */
    private fun detachAndRelease() {
        blit.release()
        attachedTo?.removeScene(scene)
        attachedTo = null
        scene.release()
    }

    /** How many views [openView] has made, so each pass has a name of its own. */
    private var views = 0

    /** How many of those are still open: while any is, the frame is not presented ([endAndPresent]). */
    private var openViews = 0

    /**
     * An editor view of the world (issue #234): the Game tab when [camera] is `null`, the Scene tab
     * otherwise. Its pass is on this scene and is not a dependency of the capturable pass; the Game
     * tab's depends on the capturable pass instead, whose picture it copies. Render thread only; the
     * caller hands it to the pipeline, which draws it.
     */
    fun openView(camera: EditorCamera?, clock: SimClock): WorldViewport {
        views++
        val name = "udea-view-${if (camera == null) "game" else "scene"}-$views"
        val record = SpriteRecord()
        val kool = ViewportPass(record, width, height, name, scene)
        if (camera == null) kool.dependsOn(pass)
        val batch = SpriteBatch2D(SpriteTexture.whitePixel("$name-white"), home = record)
        val view = WorldViewport(
            camera = camera,
            target = OffscreenTarget(width, height),
            record = record,
            batch = batch,
            frame = if (camera == null) presented else null,
            captures = FrameCaptureSlot(kool.pixels, clock),
            kool = kool,
        )
        view.onClose { batch.releaseOwned() }
        openViews++
        view.onClose { openViews-- }
        return view
    }

    override fun addBeside(pass: OffscreenPass) {
        scene.addOffscreenPass(pass)
    }

    override fun addBeforeCapture(pass: OffscreenPass) {
        scene.addOffscreenPass(pass)
        this.pass.dependsOn(pass)
    }

    override fun remove(pass: OffscreenPass) {
        scene.removeOffscreenPass(pass)
    }

    override fun addOnTop(name: String, draw: () -> Unit): RenderPass.View =
        pass.createView(name, pixelCamera()).apply {
            drawNode = Node(name)
            onSetupView(draw)
        }

    override fun removeOnTop(view: RenderPass.View) {
        pass.removeView(view)
        view.drawNode.release()
    }

    override fun setScreenInputs(depth: () -> Texture2d?, mask: () -> Texture2d?) {
        screenPasses.setSceneInputs(depth, mask)
    }

    override fun begin() {
        offscreenBatch.clear()
    }

    /**
     * Resizes the capturable pass. Everything that shows or reads it follows: the picture a Game
     * tab and the window are drawn from covers the whole texture whatever its size, and a capture
     * reads the texture as it is.
     */
    override fun resize(width: Int, height: Int) {
        if (width == this.width && height == this.height) return
        pass.setSize(width, height)
        this.width = width
        this.height = height
    }

    override val frameWidth: Int get() = width

    override val frameHeight: Int get() = height

    override fun endAndPresent(screen: ScreenTarget) {
        screenBatch.clear()
        presentBatch.clear()
        // An editor shows the world in its views and nowhere else (issue #234): a frame presented
        // under its window as well would show through wherever the window's panels are not opaque.
        if (openViews > 0) return

        // Letterboxed rather than stretched: a window of another aspect ratio shows the frame the
        // agent captures, at the same shape, with bars.
        val fit = Letterbox.fit(width, height, screen.width, screen.height)
        presentBatch.beginPixels()
        presentBatch.draw(presented, fit.x, fit.y, fit.width, fit.height, Rgba.WHITE)
        presentBatch.end()
    }

    override fun toString(): String = "KoolSurface(${width}x$height)"

    internal companion object {

        /**
         * One unit per pixel, origin at the bottom left of whatever viewport the camera is drawing:
         * `isClipToViewport` sets left/right/bottom/top to the viewport's pixels every frame.
         */
        fun pixelCamera(): Camera = OrthographicCamera("udea-pixels").apply {
            isClipToViewport = true
            setupCamera(position = Vec3f(0f, 0f, CAMERA_Z), lookAt = Vec3f.ZERO)
            clipNear = CLIP_NEAR
            clipFar = CLIP_FAR
        }

        /** The name of the view the screen effects draw in, on the capturable pass. */
        const val SCREEN_PASS_VIEW: String = "udea-screen-passes"

        const val CAMERA_Z: Float = 10f
        const val CLIP_NEAR: Float = 1f
        const val CLIP_FAR: Float = 100f
    }
}
