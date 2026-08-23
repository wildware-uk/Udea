package dev.wildware.udea.render.backend

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.ScreenUtils
import dev.wildware.udea.render.FrameSurface

/**
 * The real two-target frame: draw into an FBO, capture it, then blit it to the window.
 *
 * ## Why the game is not drawn straight onto the window
 *
 * Spec 3.7 makes the overlay exclusion structural rather than remembered. That only works if
 * there are two *surfaces*, not just two Kotlin types: an overlay drawn onto the window after
 * the capture has already been read out of a separate framebuffer cannot appear in that
 * capture, whatever anyone refactors later. Drawing everything onto the window and "capturing
 * before the overlay" would put the guarantee back into call ordering, which is the version
 * that gets broken silently.
 *
 * It buys a second property the agent epic needs: the capture is [FrameBuffer]-sized, so it
 * does not change when a human resizes the window, and an `Offscreen` capture and a `Windowed`
 * capture of the same scene are the same picture.
 *
 * ## The clear is opaque
 *
 * Both clears write alpha `1`. It does not make the capture correct on its own — LibGDX's
 * default blend func writes junk into destination alpha wherever anything is *drawn*, which is
 * what `GlPixelSource.forceOpaque` exists for — but it does mean the untouched background of a
 * frame is opaque before the stomp rather than after it, so a capture with the stomp removed
 * fails visibly on the drawn pixels instead of on every pixel at once.
 */
internal class GlFrameSurface(
    private val buffer: FrameBuffer,
    /** The batch used for the blit only. Shared with the renderers: there is one per pipeline. */
    private val batch: SpriteBatch,
) : FrameSurface {

    /**
     * The FBO's colour attachment, flipped once here rather than per frame.
     *
     * A framebuffer texture is bottom-up and a `SpriteBatch` draws top-down, so blitting it
     * unflipped presents the whole game upside down. Flipping the region at construction costs
     * nothing per frame; flipping the batch's projection would flip the overlay too.
     */
    private val presented = TextureRegion(buffer.colorBufferTexture).apply { flip(false, true) }

    /** Reused. Assigned to the batch, never mutated through `batch.projectionMatrix`. */
    private val projection = Matrix4()

    override fun begin() {
        buffer.begin()
        ScreenUtils.clear(0f, 0f, 0f, 1f)
    }

    override fun endAndPresent() {
        buffer.end()
        ScreenUtils.clear(0f, 0f, 0f, 1f)

        val windowWidth = Gdx.graphics.width.toFloat()
        val windowHeight = Gdx.graphics.height.toFloat()
        projection.setToOrtho2D(0f, 0f, windowWidth, windowHeight)
        batch.projectionMatrix = projection

        // Letterboxed rather than stretched. The offscreen framebuffer has a fixed size so that
        // two captures of the same tick are the same picture; blitting it to a window of a
        // different aspect ratio without preserving that aspect would mean the human sees a
        // squashed game whenever the window is not exactly 16:9, and would make "what the
        // player sees" and "what the agent captures" different shapes.
        val scale = minOf(windowWidth / buffer.width, windowHeight / buffer.height)
        val drawnWidth = buffer.width * scale
        val drawnHeight = buffer.height * scale

        batch.begin()
        batch.draw(
            presented,
            (windowWidth - drawnWidth) / 2f,
            (windowHeight - drawnHeight) / 2f,
            drawnWidth,
            drawnHeight,
        )
        batch.end()
    }

    override fun toString(): String = "GlFrameSurface(${buffer.width}x${buffer.height})"
}
