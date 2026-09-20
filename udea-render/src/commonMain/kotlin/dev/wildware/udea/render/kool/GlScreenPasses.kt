package dev.wildware.udea.render.kool

import de.fabmax.kool.pipeline.BufferedImageData2d
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.pipeline.backend.gl.GlApi
import de.fabmax.kool.pipeline.backend.gl.GlBuffer
import de.fabmax.kool.pipeline.backend.gl.GlFramebuffer
import de.fabmax.kool.pipeline.backend.gl.GlProgram
import de.fabmax.kool.pipeline.backend.gl.GlShader
import de.fabmax.kool.pipeline.backend.gl.GlTexture
import de.fabmax.kool.pipeline.backend.gl.GlVertexArrayObject
import de.fabmax.kool.pipeline.backend.gl.LoadedTextureGl
import de.fabmax.kool.util.Int32Buffer
import de.fabmax.kool.util.Uint8Buffer
import dev.wildware.udea.render.FrameTime
import dev.wildware.udea.render.RenderResource
import dev.wildware.udea.render.draw.SpriteTexture
import dev.wildware.udea.render.shader.TextureUniform
import dev.wildware.udea.render.shader.UdeaShader

/**
 * The ordered screen-effect chain (issues #259, #266): every shader a game registered, run over
 * the finished frame, on the graphics card.
 *
 * ## Where it sits in a frame, and why there
 *
 * It is a view of the **capturable pass**, added by [ScenePasses.addOnTop] when the surface is
 * built - before any `RenderSystem` exists, so before any `CapturedUi` can add a view of its own.
 * That ordering is the whole placement:
 *
 * ```
 * capturable pass
 *   default view   every RenderSystem's sprites, and the 3D stage's picture among them
 *   THIS VIEW      the game's shaders, each one over the whole frame
 *   later views    a CapturedUi: the HUD, which is drawn after the effects and not through them
 *   ---- capture point, then the window ----
 * ```
 *
 * So the effects run at render resolution and before the frame is letterboxed onto the window,
 * which is what #259 asks for; a capture reads the processed frame, because a capture reads this
 * pass; and an editor's gizmos are untouched, because gizmos are drawn into *view* passes that
 * the capturable pass neither feeds nor reads (`KoolSurface`).
 *
 * ## Why it talks to OpenGL directly
 *
 * See [GlScreenProgram]. Kool has no door for GLSL somebody else wrote, `udea-render` is the one
 * module allowed to hold GL, and [PassBlit] already reaches for the same `GlApi` for the same
 * reason. The GL state this touches is saved and put back inside [draw], so nothing Kool draws
 * afterwards can see that it ran.
 *
 * ## Two intermediate pictures, whatever the chain's length
 *
 * The frame is copied into one, and each shader reads one and writes the other, except the last,
 * which writes the frame itself. A shader that has just been read from is free, so the pair is
 * enough for a chain of any length - a third would never be read.
 *
 * Render thread only.
 */
internal class GlScreenPasses(
    /** The capturable pass, for its current size: an editor's Game tab can resize it (#234). */
    private val frame: ScenePasses,
    private val gl: () -> GlApi = ::koolGl,
    /** The GLSL version line, from Kool's own generator hints. See [koolGlslVersion]. */
    private val version: () -> String = ::koolGlslVersion,
) : RenderResource {

    /** The shaders to run, in the order the game registered them. */
    private var programs: List<GlScreenProgram> = emptyList()

    /** The shared vertex stage, compiled with the first [install]. */
    private var vertex: GlShader? = null

    /** Where `uTime` comes from, once a pipeline has been built over this. */
    private var frameTime: FrameTime? = null

    /** Render seconds since the first frame with a chain on it. */
    private var elapsed: Float = 0f

    private var vao: GlVertexArrayObject? = null
    private var indices: GlBuffer? = null

    /** The two intermediate pictures, and the size they were made at. */
    private var buffers: Array<Target>? = null
    private var bufferWidth = 0
    private var bufferHeight = 0

    /** Which of [buffers] a shader reads this step. The other is the one it writes. */
    private var readIndex = 0

    /**
     * `uDepth` and `uMask` when nothing published them: transparent black.
     *
     * One texture for both, because that one value reads as empty in both conventions - alpha `0`
     * is "no marked entity here" and red `0` is the far plane in the reversed depth this backend
     * draws with. A game with no 3D in it reads that everywhere rather than whatever the last
     * frame left in a buffer nobody wrote.
     *
     * A real texture rather than leaving the unit unbound: GL says an incomplete texture samples
     * `(0, 0, 0, 1)`, and that alpha of `1` would mask the whole frame.
     */
    private var empty: GlTexture? = null

    /**
     * The GL names this has already taken the depth comparison off. See [prepareInput].
     *
     * Small and rarely added to: two textures, replaced only when the frame is resized.
     */
    private val prepared = HashSet<Int>()

    private var depthSource: () -> Texture2d? = { null }
    private var maskSource: () -> Texture2d? = { null }

    /** A GL texture of our own per [SpriteTexture] a shader samples. See [textureOf]. */
    private val uploaded = HashMap<SpriteTexture, GlTexture>()

    /**
     * Compiles [shaders] and makes them the chain, in order.
     *
     * Called while the pipeline is being built, on the render thread, so a shader the driver
     * refuses stops the game starting rather than drawing nothing.
     *
     * @throws dev.wildware.udea.render.shader.ScreenShaderException if any shader fails to
     *   compile or link, or declares a uniform its source does not.
     */
    fun install(shaders: List<UdeaShader>, frameTime: FrameTime) {
        this.frameTime = frameTime
        if (shaders.isEmpty()) return
        val gl = gl()
        val version = version()
        val stage = vertex ?: GlScreenProgram.compileVertex(gl, version).also { vertex = it }
        // Built one at a time and kept as they succeed: the first failure throws, and `release`
        // still has to be able to give back whatever linked before it.
        val built = ArrayList<GlScreenProgram>(shaders.size)
        programs = built
        for (shader in shaders) built += GlScreenProgram.link(gl, version, stage, shader)
    }

    /**
     * Where the engine's depth and object-mask inputs come from, published by the 3D stage.
     *
     * Lambdas rather than textures because Kool replaces both when the frame is resized (#234):
     * a texture captured once would be the one the last size wrote, and the shader would sample
     * a picture of the wrong shape.
     */
    fun setSceneInputs(depth: () -> Texture2d?, mask: () -> Texture2d?) {
        depthSource = depth
        maskSource = mask
    }

    /** True while at least one installed shader is enabled: what a test asks before it captures. */
    val isActive: Boolean get() = programs.any { it.shader.enabled }

    /**
     * Runs the chain over the frame. Called by Kool, with the capturable pass's framebuffer bound.
     *
     * The clock is read before the early return, so turning an effect off and on again does not
     * make `uTime` jump backwards over the frames it was off for.
     */
    fun draw() {
        elapsed += frameTime?.frameSeconds ?: 0f
        val enabled = programs.filter { it.shader.enabled }
        if (enabled.isEmpty()) return

        val gl = gl()
        val width = frame.frameWidth
        val height = frame.frameHeight
        val targets = ensureTargets(gl, width, height)

        val target = GlFramebuffer(gl.getInteger(DRAW_FRAMEBUFFER_BINDING))
        val previousRead = gl.getInteger(READ_FRAMEBUFFER_BINDING)
        val previousProgram = gl.getInteger(CURRENT_PROGRAM)
        val previousVao = gl.getInteger(VERTEX_ARRAY_BINDING)
        val previousActiveTexture = gl.getInteger(ACTIVE_TEXTURE)
        val previousBlend = gl.getInteger(gl.BLEND)
        val previousDepthTest = gl.getInteger(gl.DEPTH_TEST)
        val previousCull = gl.getInteger(gl.CULL_FACE)

        // A screen effect replaces the frame rather than tinting it, covers every pixel, and has
        // no depth of its own. All three would be wrong under the sprite batch's blend state.
        gl.disable(gl.BLEND)
        gl.disable(gl.DEPTH_TEST)
        gl.disable(gl.CULL_FACE)

        // The frame, into the first intermediate picture: a shader may not sample the framebuffer
        // it is drawing into, so the first thing the chain needs is a copy it may sample.
        gl.bindFramebuffer(gl.READ_FRAMEBUFFER, target)
        gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, targets[readIndex].framebuffer)
        gl.blitFramebuffer(0, 0, width, height, 0, 0, width, height, gl.COLOR_BUFFER_BIT, gl.NEAREST)

        gl.bindVertexArray(requireNotNull(vao) { "the chain has targets but no vertex array" })
        val depth = prepareInput(gl, resolve(depthSource()))
        val mask = prepareInput(gl, resolve(maskSource()))
        var units = 0
        for (index in enabled.indices) {
            val last = index == enabled.lastIndex
            gl.bindFramebuffer(
                gl.DRAW_FRAMEBUFFER,
                if (last) target else targets[1 - readIndex].framebuffer,
            )
            gl.viewport(0, 0, width, height)
            val program = enabled[index]
            program.use(width, height, elapsed)
            program.bindColor(targets[readIndex].texture.handle)
            program.bindDepth(depth)
            program.bindMask(mask)
            program.bindTextures { uniform -> textureOf(gl, uniform).handle }
            gl.drawElements(gl.TRIANGLES, TRIANGLE_INDICES, gl.UNSIGNED_INT)
            units = maxOf(units, program.unitsUsed)
            if (!last) readIndex = 1 - readIndex
        }

        // Every texture unit this touched, emptied: Kool binds its own before each draw, but a
        // unit left pointing at one of our pictures would keep that picture alive on a driver
        // that tracks references, and it costs one call per unit to not find out.
        for (unit in 0 until units) {
            gl.activeTexture(gl.TEXTURE0 + unit)
            gl.bindTexture(gl.TEXTURE_2D, gl.NULL_TEXTURE)
        }
        gl.activeTexture(previousActiveTexture)
        gl.bindVertexArray(GlVertexArrayObject(previousVao))
        gl.useProgram(GlProgram(previousProgram))
        gl.bindFramebuffer(gl.READ_FRAMEBUFFER, GlFramebuffer(previousRead))
        gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, target)
        gl.viewport(0, 0, width, height)
        setCap(gl, gl.BLEND, previousBlend)
        setCap(gl, gl.DEPTH_TEST, previousDepthTest)
        setCap(gl, gl.CULL_FACE, previousCull)
    }

    /** Gives back every GL object this made. The published depth and mask are the stage's. */
    override fun release() {
        val gl = gl()
        for (program in programs) program.release()
        programs = emptyList()
        vertex?.let { gl.deleteShader(it) }
        vertex = null
        buffers?.forEach { it.release(gl) }
        buffers = null
        vao?.let { gl.deleteVertexArray(it) }
        vao = null
        indices?.let { gl.deleteBuffer(it) }
        indices = null
        empty?.let { gl.deleteTexture(it) }
        empty = null
        prepared.clear()
        for (texture in uploaded.values) gl.deleteTexture(texture)
        uploaded.clear()
    }

    override fun toString(): String = "GlScreenPasses(${programs.size} shaders, ${bufferWidth}x$bufferHeight)"

    /**
     * The two intermediate pictures at [width] x [height], made if they are not already, and the
     * fullscreen triangle's index buffer and vertex array with them.
     */
    private fun ensureTargets(gl: GlApi, width: Int, height: Int): Array<Target> {
        val existing = buffers
        if (existing != null && bufferWidth == width && bufferHeight == height) return existing
        existing?.forEach { it.release(gl) }
        val made = Array(INTERMEDIATE_PICTURES) { Target.make(gl, width, height) }
        buffers = made
        bufferWidth = width
        bufferHeight = height
        readIndex = 0

        if (vao == null) {
            val buffer = gl.createBuffer()
            val array = gl.createVertexArray()
            gl.bindVertexArray(array)
            gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, buffer)
            val data = Int32Buffer(TRIANGLE_INDICES)
            for (index in 0 until TRIANGLE_INDICES) data[index] = index
            gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, data, gl.STATIC_DRAW)
            gl.bindVertexArray(gl.NULL_VAO)
            indices = buffer
            vao = array
        }
        if (empty == null) empty = solid(gl, EMPTY, "udea-screen-empty-input")
        return made
    }

    /**
     * The GL texture behind [texture], uploaded once and kept.
     *
     * A texture a shader samples and nothing draws never reaches the GPU on its own: Kool uploads
     * a texture when one of *its* draws first binds it, and this chain is not one of Kool's draws.
     * So the pixels are taken from the sprite texture and uploaded here. A sprite texture that has
     * already given its pixels to Kool is used through Kool's own GL name instead, and one that
     * has done neither answers [NO_TEXTURE], which samples black - the first frame of a picture
     * still being loaded, not an error.
     */
    private fun textureOf(gl: GlApi, uniform: TextureUniform): GlTexture {
        val sprite = uniform.value
        uploaded[sprite]?.let { return it }
        val pixels = sprite.peekRgba()
        if (pixels != null) {
            val made = upload(gl, sprite.width, sprite.height, pixels, sprite.toString())
            uploaded[sprite] = made
            return made
        }
        val loaded = sprite.koolOrNull()?.gpuTexture as? LoadedTextureGl ?: return NO_TEXTURE
        return loaded.glTexture
    }

    /** The GL name of [texture], or the empty stand-in when the 3D stage has not drawn yet. */
    private fun resolve(texture: Texture2d?): Int {
        val loaded = texture?.gpuTexture as? LoadedTextureGl
        return loaded?.glTexture?.handle ?: empty?.handle ?: NO_TEXTURE.handle
    }

    /**
     * Takes the depth comparison off [handle], once per texture, and hands it back.
     *
     * A depth attachment may carry `GL_TEXTURE_COMPARE_MODE`, which makes a `sampler2D` read of it
     * undefined - a shadow map is sampled through `sampler2DShadow` and answers a comparison
     * rather than a depth. A screen shader reads both inputs as plain colours, so the comparison
     * has to be off. It is a no-op on a texture that never had it, which is what both of them are
     * today: the mask is a colour attachment and the scene's depth reaches this as a
     * single-channel colour copy, and neither ever had a comparison to take off.
     */
    private fun prepareInput(gl: GlApi, handle: Int): Int {
        if (handle == NO_TEXTURE.handle || !prepared.add(handle)) return handle
        gl.activeTexture(gl.TEXTURE0)
        gl.bindTexture(gl.TEXTURE_2D, GlTexture(handle))
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_COMPARE_MODE, gl.NONE)
        gl.bindTexture(gl.TEXTURE_2D, gl.NULL_TEXTURE)
        return handle
    }

    /** One intermediate picture: a texture and the framebuffer that draws into it. */
    private class Target(val texture: GlTexture, val framebuffer: GlFramebuffer) {

        fun release(gl: GlApi) {
            gl.deleteFramebuffer(framebuffer)
            gl.deleteTexture(texture)
        }

        companion object {

            fun make(gl: GlApi, width: Int, height: Int): Target {
                val texture = gl.createTexture()
                gl.bindTexture(gl.TEXTURE_2D, texture)
                gl.texStorage2d(gl.TEXTURE_2D, 1, gl.RGBA8, width, height)
                // Nearest and clamped, as every Udea texture is: a screen effect reads the pixel
                // it was given, and a filtered read at the edge would sample the opposite one.
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST)
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST)
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
                gl.bindTexture(gl.TEXTURE_2D, gl.NULL_TEXTURE)

                val framebuffer = gl.createFramebuffer()
                gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, framebuffer)
                gl.framebufferTexture2D(gl.DRAW_FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texture, 0)
                val status = gl.checkFramebufferStatus(gl.DRAW_FRAMEBUFFER)
                check(status == gl.FRAMEBUFFER_COMPLETE) {
                    "a screen-effect picture of ${width}x$height is not a complete framebuffer " +
                        "(status 0x${status.toString(16)}); this driver will not render into it"
                }
                return Target(texture, framebuffer)
            }
        }
    }

    private companion object {

        /** Two: one to read, one to write, and the one just read is free for the step after. */
        const val INTERMEDIATE_PICTURES: Int = 2

        /** The fullscreen triangle: three vertices, built in the vertex stage from their indices. */
        const val TRIANGLE_INDICES: Int = 3

        /** No texture. Sampling an unbound unit reads black, which is what an absent input means. */
        val NO_TEXTURE: GlTexture = GlTexture(0)

        /** Zero in every channel: what both engine inputs read where nothing published one. */
        const val EMPTY: Byte = 0

        /**
         * `GL_DRAW_FRAMEBUFFER_BINDING`, `GL_READ_FRAMEBUFFER_BINDING`, `GL_CURRENT_PROGRAM`,
         * `GL_VERTEX_ARRAY_BINDING` and `GL_ACTIVE_TEXTURE`, from the OpenGL 3.0 and OpenGL ES 3.0
         * specifications. Kool's `GlApi` names the targets but not the binding queries, and a
         * binding has to be read to be put back - the same gap [PassBlit] names for the read one.
         */
        const val DRAW_FRAMEBUFFER_BINDING: Int = 0x8CA6
        const val READ_FRAMEBUFFER_BINDING: Int = 0x8CAA
        const val CURRENT_PROGRAM: Int = 0x8B8D
        const val VERTEX_ARRAY_BINDING: Int = 0x85B5
        const val ACTIVE_TEXTURE: Int = 0x84E0

        /** Puts a capability back the way [draw] found it. */
        fun setCap(gl: GlApi, cap: Int, wasEnabled: Int) {
            if (wasEnabled != 0) gl.enable(cap) else gl.disable(cap)
        }

        /** A 1x1 texture of one value in every channel. */
        fun solid(gl: GlApi, value: Byte, name: String): GlTexture =
            upload(gl, 1, 1, ByteArray(4) { value }, name)

        /** Makes a GL texture and puts [rgba] in it. Nearest and clamped, like every Udea texture. */
        fun upload(gl: GlApi, width: Int, height: Int, rgba: ByteArray, name: String): GlTexture {
            require(rgba.size == width * height * 4) {
                "texture '$name' is ${width}x$height but was given ${rgba.size} bytes"
            }
            val buffer = Uint8Buffer(rgba.size)
            for (index in rgba.indices) buffer[index] = rgba[index].toUByte()
            val texture = gl.createTexture()
            gl.bindTexture(gl.TEXTURE_2D, texture)
            gl.texImage2d(gl.TEXTURE_2D, BufferedImageData2d(buffer, width, height, TexFormat.RGBA))
            gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST)
            gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST)
            gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
            gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
            gl.bindTexture(gl.TEXTURE_2D, gl.NULL_TEXTURE)
            return texture
        }
    }
}
