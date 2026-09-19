package dev.wildware.udea.render.kool

import de.fabmax.kool.KoolContext
import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.pipeline.AttachmentConfig
import de.fabmax.kool.pipeline.ClearColorFill
import de.fabmax.kool.pipeline.OffscreenPass
import de.fabmax.kool.pipeline.OffscreenPass2d
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.scene.Camera
import de.fabmax.kool.scene.OrthographicCamera
import de.fabmax.kool.scene.Scene
import de.fabmax.kool.util.Color
import dev.wildware.udea.render.FrameSurface
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResource
import dev.wildware.udea.render.RenderTargets
import dev.wildware.udea.render.ScreenTarget
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteBatch2D
import dev.wildware.udea.render.draw.SpriteRegion
import dev.wildware.udea.render.draw.SpriteTexture

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
 * ## Why the capture's size never moves
 *
 * The pass is created at [width] x [height] and never resized. A human dragging the window changes
 * the scene's viewport, which only changes how the present batch letterboxes the texture - so a
 * capture's dimensions and framing are a property of the configuration and of nothing else, and
 * two captures of the same tick are comparable whatever the window did in between.
 */
internal class KoolSurface(
    /** Width of the pass every capture reads, in pixels. */
    val width: Int,
    /** Height of the pass every capture reads, in pixels. */
    val height: Int,
    private val windowWidth: Int,
    private val windowHeight: Int,
) : FrameSurface, ScenePasses {

    private val offscreenBatch = SpriteBatch2D(SpriteTexture.whitePixel("udea-offscreen-white"))
    private val screenBatch = SpriteBatch2D(SpriteTexture.whitePixel("udea-screen-white"))
    private val presentBatch = SpriteBatch2D(SpriteTexture.whitePixel("udea-present-white"))

    private val offscreenNode = SpriteBatchNode(offscreenBatch, "udea-offscreen-sprites")

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
        addNode(SpriteBatchNode(presentBatch, "udea-present"))
        addNode(SpriteBatchNode(screenBatch, "udea-overlay-sprites"))
    }

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
        ),
    )

    private var attachedTo: KoolContext? = null

    /** Adds the scene to [ctx]. Render thread only. */
    fun attach(ctx: KoolContext) {
        check(attachedTo == null) { "$this is already attached" }
        ctx.addScene(scene)
        attachedTo = ctx
    }

    /** Takes the scene off the context before releasing it: a released scene still listed is drawn. */
    private fun detachAndRelease() {
        attachedTo?.removeScene(scene)
        attachedTo = null
        scene.release()
    }

    override fun addBeforeCapture(pass: OffscreenPass) {
        scene.addOffscreenPass(pass)
        this.pass.dependsOn(pass)
    }

    override fun remove(pass: OffscreenPass) {
        scene.removeOffscreenPass(pass)
    }

    override fun begin() {
        offscreenBatch.clear()
    }

    override fun endAndPresent(screen: ScreenTarget) {
        screenBatch.clear()
        presentBatch.clear()

        // Letterboxed rather than stretched: a window of another aspect ratio shows the frame the
        // agent captures, at the same shape, with bars.
        val scale = minOf(screen.width.toFloat() / width, screen.height.toFloat() / height)
        val drawnWidth = width * scale
        val drawnHeight = height * scale
        presentBatch.beginPixels()
        presentBatch.draw(
            presented,
            (screen.width - drawnWidth) / 2f,
            (screen.height - drawnHeight) / 2f,
            drawnWidth,
            drawnHeight,
            Rgba.WHITE,
        )
        presentBatch.end()
    }

    override fun toString(): String = "KoolSurface(${width}x$height)"

    private companion object {

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

        const val CAMERA_Z: Float = 10f
        const val CLIP_NEAR: Float = 1f
        const val CLIP_FAR: Float = 100f
    }
}
