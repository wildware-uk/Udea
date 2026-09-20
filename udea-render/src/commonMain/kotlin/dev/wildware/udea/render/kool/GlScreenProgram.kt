package dev.wildware.udea.render.kool

import de.fabmax.kool.pipeline.backend.gl.GlApi
import de.fabmax.kool.pipeline.backend.gl.GlProgram
import de.fabmax.kool.pipeline.backend.gl.GlShader
import de.fabmax.kool.pipeline.backend.gl.GlTexture
import dev.wildware.udea.render.shader.ColorUniform
import dev.wildware.udea.render.shader.FloatUniform
import dev.wildware.udea.render.shader.IntUniform
import dev.wildware.udea.render.shader.ScreenShaderException
import dev.wildware.udea.render.shader.ScreenShaderSource
import dev.wildware.udea.render.shader.ShaderUniform
import dev.wildware.udea.render.shader.TextureUniform
import dev.wildware.udea.render.shader.UdeaShader
import dev.wildware.udea.render.shader.Vec2Uniform

/**
 * One screen shader compiled and linked on this backend, with its uniform locations resolved.
 *
 * ## Why this speaks OpenGL directly instead of going through Kool
 *
 * Kool draws through `KslShader`, whose source is a Kotlin AST its backend turns into GLSL. There
 * is no door in it for GLSL somebody else wrote: a `DrawShader` would have to hand back a
 * `DrawPipeline` with hand-built bind-group layouts whose member names had to agree, invisibly,
 * with the text of the shader. Talking to the driver is the smaller and far more legible thing,
 * and it is the route `PassBlit` already takes for the same reason - `udea-render` is allowed to
 * hold GL, and `GlApi` is the binding Kool itself calls.
 *
 * What is *not* taken from the driver is the language version: that comes from Kool's own
 * `GlslGenerator.Hints`, so a game's shader and an engine shader are always compiled against the
 * same GLSL version on the same machine. See [ScreenShaderSource].
 *
 * ## What a failure looks like
 *
 * A driver reports a line of the text it was handed, which is the author's body with the engine's
 * header in front of it. [diagnose] subtracts the header so the line named is a line of the
 * author's `.frag`, and an error inside the header itself is reported at line 0 rather than at a
 * line of a file that does not contain it. The driver's own words are quoted, never paraphrased.
 *
 * Render thread only, like everything that touches the context.
 */
internal class GlScreenProgram private constructor(
    private val gl: GlApi,
    val shader: UdeaShader,
    private val program: GlProgram,
    /** Every declared uniform with the location it linked to, in declaration order. */
    private val bound: List<Bound>,
    /** Location of `uColor`, `uDepth`, `uMask`, `uResolution`, `uTexel` and `uTime`, or -1. */
    private val supplied: Supplied,
    /** Which texture unit each engine input and each texture uniform samples from. */
    private val firstFreeUnit: Int,
) {

    /** Makes this program current and writes every uniform the game declared. Render thread. */
    fun use(width: Int, height: Int, seconds: Float) {
        gl.useProgram(program)
        if (supplied.resolution >= 0) gl.uniform2f(supplied.resolution, width.toFloat(), height.toFloat())
        if (supplied.texel >= 0) gl.uniform2f(supplied.texel, 1f / width, 1f / height)
        if (supplied.time >= 0) gl.uniform1f(supplied.time, seconds)
        for (index in bound.indices) bound[index].write()
    }

    /** Binds [texture] to the unit `uColor` reads, and tells the program which unit that is. */
    fun bindColor(texture: Int) = bindSupplied(supplied.color, COLOR_UNIT, texture)

    /** Binds [texture] to the unit `uDepth` reads. */
    fun bindDepth(texture: Int) = bindSupplied(supplied.depth, DEPTH_UNIT, texture)

    /** Binds [texture] to the unit `uMask` reads. */
    fun bindMask(texture: Int) = bindSupplied(supplied.mask, MASK_UNIT, texture)

    /**
     * Binds every texture uniform the game declared, asking [resolve] for each one's GL name.
     *
     * `resolve` may answer `0` for a texture whose pixels have not reached the GPU, in which case
     * the sampler reads an unbound unit and the shader sees black - the first-frame case, not an
     * error.
     */
    fun bindTextures(resolve: (TextureUniform) -> Int) {
        for (index in bound.indices) {
            val entry = bound[index]
            if (entry !is Bound.Texture) continue
            bindSupplied(entry.location, entry.unit, resolve(entry.uniform))
        }
    }

    private fun bindSupplied(location: Int, unit: Int, texture: Int) {
        if (location < 0) return
        gl.activeTexture(gl.TEXTURE0 + unit)
        gl.bindTexture(gl.TEXTURE_2D, GlTexture(texture))
        gl.uniform1i(location, unit)
    }

    /** How many texture units this program binds, so the chain can unbind exactly those. */
    val unitsUsed: Int get() = firstFreeUnit

    /** Deletes the GL program. The shaders were detached and deleted when it linked. */
    fun release() {
        gl.deleteProgram(program)
    }

    override fun toString(): String = "GlScreenProgram('${shader.path}')"

    /** One declared uniform, its resolved location, and how to write its current value. */
    private sealed class Bound(val location: Int) {

        abstract fun write()

        class Float1(private val gl: GlApi, location: Int, private val uniform: FloatUniform) : Bound(location) {
            override fun write() {
                gl.uniform1f(location, uniform.value)
            }
        }

        class Int1(private val gl: GlApi, location: Int, private val uniform: IntUniform) : Bound(location) {
            override fun write() {
                gl.uniform1i(location, uniform.value)
            }
        }

        class Vec2(private val gl: GlApi, location: Int, private val uniform: Vec2Uniform) : Bound(location) {
            override fun write() {
                gl.uniform2f(location, uniform.x, uniform.y)
            }
        }

        class Color(private val gl: GlApi, location: Int, private val uniform: ColorUniform) : Bound(location) {
            override fun write() {
                val value = uniform.value
                gl.uniform4f(location, value.r, value.g, value.b, value.a)
            }
        }

        /** A sampler: the unit is written once per frame by [bindTextures], not here. */
        class Texture(location: Int, val uniform: TextureUniform, val unit: Int) : Bound(location) {
            override fun write() = Unit
        }
    }

    /** Where the engine's own inputs linked to. `-1` for one the shader never reads. */
    private class Supplied(
        val color: Int,
        val depth: Int,
        val mask: Int,
        val resolution: Int,
        val texel: Int,
        val time: Int,
    )

    companion object {

        /** `uColor`'s texture unit. The engine's inputs take the first units; a game's follow. */
        const val COLOR_UNIT: Int = 0
        private const val DEPTH_UNIT: Int = 1
        private const val MASK_UNIT: Int = 2

        /** The first unit a game's own texture uniform may use. */
        private const val FIRST_GAME_UNIT: Int = 3

        /**
         * Compiles [shader] against [version] and links it to [vertex], which every screen shader
         * in a pipeline shares.
         *
         * @throws ScreenShaderException with `UDEA0019` if the driver refuses it, or `UDEA0040`
         *   if a uniform declared in Kotlin is not declared in the shader's own source.
         */
        fun link(gl: GlApi, version: String, vertex: GlShader, shader: UdeaShader): GlScreenProgram {
            checkUniformsAreDeclared(shader)
            val offset = ScreenShaderSource.fragmentLineOffset(version)
            val fragment = compile(
                gl,
                gl.FRAGMENT_SHADER,
                ScreenShaderSource.fragment(version, shader.source),
                shader,
                offset,
            )
            val program = gl.createProgram()
            gl.attachShader(program, vertex)
            gl.attachShader(program, fragment)
            gl.linkProgram(program)
            // The fragment stage is this program's alone; the vertex stage is shared and stays.
            gl.deleteShader(fragment)
            if (gl.getProgramParameter(program, gl.LINK_STATUS) != gl.TRUE) {
                val log = gl.getProgramInfoLog(program)
                gl.deleteProgram(program)
                throw ScreenShaderException(
                    ruleId = ScreenShaderException.COMPILE_FAILED,
                    path = shader.path,
                    line = NO_LINE,
                    detail = "the shader linked against this backend's vertex stage and the " +
                        "driver refused it: ${log.trim()}",
                )
            }

            var unit = FIRST_GAME_UNIT
            val bound = ArrayList<Bound>(shader.declared.size)
            for (uniform in shader.declared) {
                val location = gl.getUniformLocation(program, uniform.name)
                bound += when (uniform) {
                    is FloatUniform -> Bound.Float1(gl, location, uniform)
                    is IntUniform -> Bound.Int1(gl, location, uniform)
                    is Vec2Uniform -> Bound.Vec2(gl, location, uniform)
                    is ColorUniform -> Bound.Color(gl, location, uniform)
                    is TextureUniform -> Bound.Texture(location, uniform, unit++)
                }
            }
            return GlScreenProgram(
                gl = gl,
                shader = shader,
                program = program,
                bound = bound,
                supplied = Supplied(
                    color = gl.getUniformLocation(program, "uColor"),
                    depth = gl.getUniformLocation(program, "uDepth"),
                    mask = gl.getUniformLocation(program, "uMask"),
                    resolution = gl.getUniformLocation(program, "uResolution"),
                    texel = gl.getUniformLocation(program, "uTexel"),
                    time = gl.getUniformLocation(program, "uTime"),
                ),
                firstFreeUnit = unit,
            )
        }

        /**
         * Compiles the vertex stage, which has no parameters and so is made once per pipeline.
         *
         * Its failures carry no author's file, because nobody outside this module wrote it: a
         * failure here means the version string and [ScreenShaderSource.VERTEX_BODY] disagree on
         * this driver, which is an engine defect and is reported as one.
         */
        fun compileVertex(gl: GlApi, version: String): GlShader =
            compile(gl, gl.VERTEX_SHADER, ScreenShaderSource.vertex(version), shader = null, offset = 0)

        private fun compile(
            gl: GlApi,
            stage: Int,
            source: String,
            shader: UdeaShader?,
            offset: Int,
        ): GlShader {
            val handle = gl.createShader(stage)
            gl.shaderSource(handle, source)
            gl.compileShader(handle)
            if (gl.getShaderParameter(handle, gl.COMPILE_STATUS) == gl.TRUE) return handle
            val log = gl.getShaderInfoLog(handle)
            gl.deleteShader(handle)
            throw diagnose(log, shader, offset)
        }

        /**
         * Turns a driver's compile log into a diagnostic that names the author's file and line.
         *
         * The first line number in the log is the one used, because a diagnostic sink ranks
         * root-cause first and a GLSL compiler reports its errors in source order: the first is
         * the one whose fix makes the rest go away.
         */
        private fun diagnose(log: String, shader: UdeaShader?, offset: Int): ScreenShaderException {
            val reported = LINE.find(log)?.groupValues?.get(1)?.toIntOrNull()
            val authored = if (reported == null) NO_LINE else (reported - offset).coerceAtLeast(NO_LINE)
            val note = when {
                shader == null -> "the engine's own vertex stage did not compile on this driver, " +
                    "which is a defect in udea-render rather than in a game's shader"
                reported != null && authored == NO_LINE ->
                    "the driver reported line $reported, which is inside the header the engine " +
                        "prepends rather than inside the body"
                else -> "the driver refused the shader"
            }
            return ScreenShaderException(
                ruleId = ScreenShaderException.COMPILE_FAILED,
                path = shader?.path ?: ENGINE_SOURCE,
                line = authored,
                detail = "$note: ${log.trim()}",
            )
        }

        /**
         * Fails a uniform that the shader's source never declares.
         *
         * A uniform the *driver* dropped is a different thing and stays silent: GLSL compilers
         * remove a uniform nothing reads, so `getUniformLocation` answering `-1` proves nothing.
         * A name the source does not declare at all cannot be either, so it is a misspelling on
         * one side or the other, and the handle would write into nothing for the life of the game.
         *
         * The spec's mandatory did-you-mean is served by naming **every** uniform the source
         * declares rather than by guessing the nearest one. A shader declares a handful, so the
         * complete list is short enough to read and tells the author strictly more than one
         * guess would - and it needs no edit-distance of its own here, which would be the
         * `udea-diagnostics` one copied into a module that does not ship it.
         */
        private fun checkUniformsAreDeclared(shader: UdeaShader) {
            if (shader.declared.isEmpty()) return
            val inSource = DECLARATION.findAll(shader.source).map { it.groupValues[1] }.toList()
            for (uniform: ShaderUniform in shader.declared) {
                if (uniform.name in inSource) continue
                val candidates =
                    if (inSource.isEmpty()) "The source declares no uniforms at all."
                    else "The source declares ${inSource.joinToString()}."
                throw ScreenShaderException(
                    ruleId = ScreenShaderException.UNIFORM_NOT_DECLARED,
                    path = shader.path,
                    line = NO_LINE,
                    detail = "'${uniform.name}' is declared in Kotlin but the shader source never " +
                        "declares it, so writing its value would do nothing. $candidates",
                )
            }
        }

        /** No line to name: the header, the link, or a check about the shader as a whole. */
        private const val NO_LINE: Int = 0

        /** The path a diagnostic names when the engine's own stage is the one that failed. */
        private const val ENGINE_SOURCE: String = "udea-render/shader/screen.vert"

        /**
         * A line number in a driver's log.
         *
         * Two spellings cover the drivers this engine runs on: Mesa and ANGLE write `0:23(9)`,
         * NVIDIA writes `0(23)`. Both start with the source-string index, which is always `0`
         * here because one string is handed to `glShaderSource`.
         */
        private val LINE: Regex = Regex("""0[:(](\d+)""")

        /**
         * A `uniform` declaration in GLSL, with an optional precision qualifier.
         *
         * Deliberately shallow: it is asked one question - does this source declare this name -
         * and a shallow answer to that question is right far more often than a GLSL parser in
         * this module would be worth.
         */
        private val DECLARATION: Regex =
            Regex("""\buniform\s+(?:lowp\s+|mediump\s+|highp\s+)?\w+\s+(\w+)""")
    }
}
