package dev.wildware.udea.render.kool

import de.fabmax.kool.pipeline.OffscreenPass2d
import de.fabmax.kool.pipeline.backend.gl.GlApi
import de.fabmax.kool.pipeline.backend.gl.GlFramebuffer
import de.fabmax.kool.pipeline.backend.gl.GlTexture
import de.fabmax.kool.pipeline.backend.gl.LoadedTextureGl

/**
 * Copies the capturable pass into whatever framebuffer is bound for drawing: the body of
 * `WorldView.drawInto`, which runs inside a ComposeGL `SceneView`'s `raw` block.
 *
 * ## Why a framebuffer blit and not a Kool draw
 *
 * Inside `raw` the `SceneView`'s picture is bound, and Kool can draw only through its own passes, so
 * nothing Kool owns can be asked to draw into it (ComposeGL's `docs/wiki/Kool.md`, "Scene view").
 * What is left is OpenGL on Kool's context, behind Kool's back - which the Kool frontend allows for
 * exactly this, and undoes: its `HostState.Restore` puts Kool's own state back when the block returns.
 * A blit is the smallest such thing. It binds no program, no texture and no vertex array, so the one
 * piece of state it changes is the read framebuffer binding, and that is put back here before
 * returning rather than left to anyone else.
 *
 * The pass's colour texture is attached to a framebuffer of this class's own, made once on the render
 * thread and re-attached only if Kool replaces the texture.
 *
 * Render thread only, like everything that touches the context.
 */
internal class PassBlit(
    private val pass: OffscreenPass2d,
    private val gl: () -> GlApi = ::koolGl,
) {

    private var framebuffer: GlFramebuffer? = null

    private var attached: GlTexture? = null

    /**
     * Copies the pass into the bound draw framebuffer, letterboxed into [width] x [height].
     *
     * Before Kool has rendered the pass for the first time there is no colour texture on the GPU and
     * nothing to copy; the picture keeps what the caller cleared it to, and the next invalidation
     * draws again. That is the first frame of a window, not an error.
     */
    fun into(width: Int, height: Int) {
        val colour = pass.colorTexture?.gpuTexture as? LoadedTextureGl ?: return
        val gl = gl()
        val read = framebuffer ?: gl.createFramebuffer().also { framebuffer = it }
        val previousRead = gl.getInteger(READ_FRAMEBUFFER_BINDING)
        gl.bindFramebuffer(gl.READ_FRAMEBUFFER, read)
        val texture = colour.glTexture
        if (attached != texture) {
            gl.framebufferTexture2D(gl.READ_FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texture, 0)
            attached = texture
        }
        val fit = Letterbox.fit(colour.width, colour.height, width, height)
        gl.blitFramebuffer(
            0,
            0,
            colour.width,
            colour.height,
            fit.x.toInt(),
            fit.y.toInt(),
            (fit.x + fit.width).toInt(),
            (fit.y + fit.height).toInt(),
            gl.COLOR_BUFFER_BIT,
            gl.LINEAR,
        )
        gl.bindFramebuffer(gl.READ_FRAMEBUFFER, GlFramebuffer(previousRead))
    }

    /** Deletes the framebuffer this made. Render thread only; the pass's texture is Kool's. */
    fun release() {
        framebuffer?.let { gl().deleteFramebuffer(it) }
        framebuffer = null
        attached = null
    }

    override fun toString(): String = "PassBlit(${pass.name})"

    private companion object {

        /**
         * `GL_READ_FRAMEBUFFER_BINDING`, from the OpenGL 3.0 and OpenGL ES 3.0 specifications. Kool's
         * `GlApi` names the targets but not the binding queries, and the binding has to be read to be
         * put back.
         */
        const val READ_FRAMEBUFFER_BINDING: Int = 0x8CAA
    }
}
