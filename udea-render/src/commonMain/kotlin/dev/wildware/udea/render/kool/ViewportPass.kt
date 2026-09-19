package dev.wildware.udea.render.kool

import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.pipeline.AttachmentConfig
import de.fabmax.kool.pipeline.ClearColorFill
import de.fabmax.kool.pipeline.OffscreenPass
import de.fabmax.kool.pipeline.OffscreenPass2d
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.scene.Scene
import de.fabmax.kool.util.Color
import dev.wildware.udea.render.capture.PixelSource
import dev.wildware.udea.render.draw.SpriteRecord

/**
 * The Kool half of a `WorldViewport`: a pass of its own that draws the view's [SpriteRecord], on the
 * window's scene, and the blit that shows it in a `SceneView`.
 *
 * The pass is **not** a dependency of the capturable pass and the capturable pass reads nothing of
 * it, so what is drawn here - the Scene tab's world, and every gizmo - cannot reach a capture. A Game
 * tab's pass depends on the capturable pass instead, because it samples its picture.
 *
 * Render thread only, like everything that touches the context.
 */
internal class ViewportPass(
    record: SpriteRecord,
    width: Int,
    height: Int,
    name: String,
    private val scene: Scene,
) {

    private val node = SpriteBatchNode(record, "$name-sprites")

    /** The view's picture. The same pixel camera as the capturable pass: one unit per pixel. */
    val pass: OffscreenPass2d = OffscreenPass2d(
        drawNode = node,
        attachmentConfig = AttachmentConfig {
            addColor(TexFormat.RGBA, clearColor = ClearColorFill(Color.BLACK))
            noDepth()
        },
        initialSize = Vec2i(width, height),
        name = name,
    ).apply {
        camera = KoolSurface.pixelCamera()
    }

    private val blit = PassBlit(pass)

    /** Reads the picture back, for `editor.screenshot`. */
    val pixels: PixelSource = KoolPixelSource(pass)

    init {
        scene.addOffscreenPass(pass)
    }

    /** Makes the picture [width] x [height] pixels from the next time Kool draws it. */
    fun resize(width: Int, height: Int) {
        pass.setSize(width, height)
    }

    /** Draws this pass after [other]. */
    fun dependsOn(other: OffscreenPass) {
        pass.dependsOn(other)
    }

    /** Copies the picture into the bound draw framebuffer, letterboxed. See [PassBlit.into]. */
    fun blitInto(width: Int, height: Int) {
        blit.into(width, height)
    }

    /** Takes the pass off the scene and releases it, with its node. */
    fun release() {
        blit.release()
        scene.removeOffscreenPass(pass)
        pass.release()
    }

    override fun toString(): String = "ViewportPass(${pass.name})"
}
