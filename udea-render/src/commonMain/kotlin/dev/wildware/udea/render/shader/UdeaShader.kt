package dev.wildware.udea.render.shader

import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteTexture

/**
 * A fragment program a game writes, as GLSL text plus the parameters it reads.
 *
 * ## What a game writes, and what the engine writes
 *
 * The game writes a body: one function, `vec4 udeaMain(vec2 uv)`, in its own `.frag` file. The
 * engine puts a header in front of it and a `main` behind it, and neither is built by
 * concatenating pieces of Kotlin - each is one constant, chosen for the backend. See
 * [ScreenShaderSource] for the text of both and for what the header declares.
 *
 * That split is the whole reason [fragment] refuses a source containing `#version`. GL and GLES
 * disagree about the version pragma and about which `precision` qualifiers a fragment stage must
 * carry, so a shader that states its own version is a shader that works on the desktop and fails
 * on Android with a driver message its author never sees. The engine states it, from the same
 * hint Kool's own generated shaders use, so the two can never disagree.
 *
 * ## Parameters are Udea types
 *
 * Every uniform a game declares is a float, an int, a 2-vector, an [Rgba] or a [SpriteTexture];
 * `UDEA-MG-002` keeps `de.fabmax.kool:*` off every game's classpath, so an API that took a Kool
 * type would be an API no game could call. Each declaration hands back a handle with a settable
 * `value`, so a game changes a parameter per frame without allocating and without a name lookup.
 *
 * ```kotlin
 * lateinit var levels: FloatUniform
 * val palette = UdeaShader.fragment(
 *     path = "shaders/palette.frag",
 *     source = readText("shaders/palette.frag"),
 * ) {
 *     levels = float("uLevels", 16f)
 *     texture("uRamp", ramp)
 * }
 * // later, in a RenderSystem:
 * levels.value = 8f
 * ```
 *
 * ## Where it runs
 *
 * As a screen effect, registered with `RenderRegistry.screenPass`: the ordered list runs over the
 * finished frame at render resolution, before the picture is letterboxed onto the window, and its
 * result is what a capture reads. See `GlScreenPasses` for the frame it sits in.
 *
 * Instances are compared by identity and are not reusable across pipelines: the GL program behind
 * one belongs to the pipeline that compiled it, and is released with it.
 */
public class UdeaShader internal constructor(
    /**
     * Where the body was authored, repository-relative: `shaders/palette.frag`.
     *
     * This is what a compile failure names, so it is a path and not a nickname. [fragment]
     * refuses an absolute one, because the message it appears in is a message a shipped game can
     * print, and a build machine's directory layout has no business in it - the same reason
     * `SourceSpan` refuses one. It is checked at construction rather than at the moment a driver
     * rejects the shader, so a path nobody can act on is found before it has to be read in anger.
     */
    public val path: String,
    /** The body the game wrote: everything between the engine's header and its `main`. */
    internal val source: String,
    /** The parameters the game declared, in declaration order. */
    internal val declared: List<ShaderUniform>,
) {

    /**
     * Whether the chain runs this shader.
     *
     * A `var` because turning an effect off is the ordinary way a game offers a graphics setting,
     * and because it is how a test compares a processed frame with the same frame unprocessed
     * without rebuilding a pipeline. A disabled shader costs one boolean per frame: the chain
     * skips it, and its program and textures stay compiled and ready.
     */
    public var enabled: Boolean = true

    override fun toString(): String = "UdeaShader('$path', ${declared.size} uniforms, enabled=$enabled)"

    public companion object {

        /**
         * A fragment shader from [source], authored at [path].
         *
         * @param path repository-relative path of the `.frag` the body was read from. Named in
         *   every diagnostic this shader produces.
         * @param source the body. It must define `vec4 udeaMain(vec2 uv)` and must **not** declare
         *   a `#version`: see this class's documentation for why the engine owns that line.
         * @param uniforms declares the parameters the body reads. Each call returns a handle whose
         *   `value` the game writes later.
         * @throws IllegalArgumentException if [path] is blank or absolute, if [source] states a
         *   `#version`, if it does not mention `udeaMain`, or if a uniform declaration repeats a
         *   name or claims one of the engine's own (see [ScreenShaderSource.SUPPLIED]).
         */
        public fun fragment(
            path: String,
            source: String,
            uniforms: ShaderUniforms.() -> Unit = {},
        ): UdeaShader {
            require(path.isNotBlank()) { "a shader's path is where it was authored, and is not blank" }
            require(!ABSOLUTE.containsMatchIn(path)) {
                "a shader's path is repository-relative, and '$path' is absolute. It is printed " +
                    "in an error a shipped game can show, and a build machine's directory layout " +
                    "has no business being in it."
            }
            require(source.isNotBlank()) { "shader '$path' has no source" }
            require(!VERSION.containsMatchIn(source)) {
                "shader '$path' states its own #version. The engine prepends the version and the " +
                    "precision qualifiers the backend needs, which differ between OpenGL and " +
                    "OpenGL ES; a shader that states its own works on one and fails on the other. " +
                    "Delete the #version line and write the body alone."
            }
            require(source.contains(ENTRY_POINT)) {
                "shader '$path' does not mention $ENTRY_POINT. A screen shader is one function - " +
                    "`vec4 $ENTRY_POINT(vec2 uv)` - and the engine writes the `main` that calls it."
            }
            val declared = ShaderUniforms(path).apply(uniforms).declared()
            return UdeaShader(path, source, declared)
        }

        /** The one function a body must define. The engine's `main` calls it and nothing else. */
        internal const val ENTRY_POINT: String = "udeaMain"

        /**
         * A `#version` pragma anywhere in the source, comments included.
         *
         * Deliberately not comment-aware. A `#version` mentioned in a comment is worth the same
         * refusal as one in code: it is about to be copied down one line by whoever reads it, and
         * the message it earns explains the rule. A checker that is easy to state is a checker a
         * reader can predict.
         */
        private val VERSION: Regex = Regex("""#\s*version\b""")

        /** A leading separator, either way round, or a Windows drive letter. */
        private val ABSOLUTE: Regex = Regex("""^([/\\]|[A-Za-z]:)""")
    }
}

/**
 * Collects the uniform declarations of one [UdeaShader], and refuses the names that cannot work.
 *
 * Three refusals, each for a failure that is silent without it:
 *
 * - **A name the engine supplies** ([ScreenShaderSource.SUPPLIED]) would be declared twice in the
 *   assembled source, and GLSL rejects the redeclaration with an error pointing at the engine's
 *   header rather than at the game's line.
 * - **A repeated name** would give two handles the same location, so writing one would silently
 *   move the other.
 * - **A name that is not a GLSL identifier** cannot be looked up at all, and its handle would do
 *   nothing for the life of the game.
 */
public class ShaderUniforms internal constructor(private val path: String) {

    private val uniforms = ArrayList<ShaderUniform>()

    /** A `float uniform`, starting at [value]. */
    public fun float(name: String, value: Float = 0f): FloatUniform =
        add(FloatUniform(checked(name), value))

    /** An `int uniform`, starting at [value]. */
    public fun int(name: String, value: Int = 0): IntUniform =
        add(IntUniform(checked(name), value))

    /** A `vec2 uniform`, starting at ([x], [y]). */
    public fun vec2(name: String, x: Float = 0f, y: Float = 0f): Vec2Uniform =
        add(Vec2Uniform(checked(name), x, y))

    /** A `vec4 uniform` carrying a colour, red first, alpha last. */
    public fun color(name: String, value: Rgba = Rgba.WHITE): ColorUniform =
        add(ColorUniform(checked(name), value))

    /**
     * A `sampler2D uniform` reading [value].
     *
     * The texture stays the caller's to release. A shader that samples a texture nothing else
     * draws is the ordinary case - a palette ramp, a dither pattern - and the chain uploads it
     * once rather than waiting for a draw that never happens.
     */
    public fun texture(name: String, value: SpriteTexture): TextureUniform =
        add(TextureUniform(checked(name), value))

    internal fun declared(): List<ShaderUniform> = uniforms.toList()

    private fun <T : ShaderUniform> add(uniform: T): T {
        uniforms += uniform
        return uniform
    }

    private fun checked(name: String): String {
        require(IDENTIFIER.matches(name)) {
            "'$name' is not a GLSL identifier, so shader '$path' could never bind it"
        }
        require(name !in ScreenShaderSource.SUPPLIED) {
            "shader '$path' declares '$name', which the engine already supplies. The header " +
                "declares these and a body reads them without declaring anything: " +
                "${ScreenShaderSource.SUPPLIED.joinToString()}."
        }
        require(uniforms.none { it.name == name }) {
            "shader '$path' declares '$name' twice; the second handle would write the first's value"
        }
        return name
    }

    private companion object {
        val IDENTIFIER: Regex = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

/**
 * One parameter of a [UdeaShader], and the handle a game writes it through.
 *
 * Sealed, and each case is one GLSL type. A game holds the handle its declaration returned and
 * assigns `value`; the chain reads every handle once per frame, in declaration order, straight
 * into the location it resolved when the program linked. There is no name lookup on the drawing
 * path and no boxing: a `Float` written into a `var Float` is a `Float`.
 */
public sealed class ShaderUniform internal constructor(
    /** The identifier the body declares and reads. */
    public val name: String,
) {
    override fun toString(): String = "${this::class.simpleName}('$name')"
}

/** A `uniform float`. */
public class FloatUniform internal constructor(name: String, public var value: Float) : ShaderUniform(name)

/** A `uniform int`. */
public class IntUniform internal constructor(name: String, public var value: Int) : ShaderUniform(name)

/** A `uniform vec2`. */
public class Vec2Uniform internal constructor(
    name: String,
    public var x: Float,
    public var y: Float,
) : ShaderUniform(name) {

    /** Sets both components. */
    public fun set(x: Float, y: Float) {
        this.x = x
        this.y = y
    }
}

/** A `uniform vec4` carrying a colour: `r, g, b, a`, each `0..1`. */
public class ColorUniform internal constructor(name: String, public var value: Rgba) : ShaderUniform(name)

/**
 * A `uniform sampler2D`.
 *
 * The chain gives each texture uniform a texture unit of its own for the life of the shader, so
 * changing [value] between frames costs a bind and nothing else.
 */
public class TextureUniform internal constructor(name: String, public var value: SpriteTexture) : ShaderUniform(name)
