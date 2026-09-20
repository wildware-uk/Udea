package dev.wildware.udea.render.shader

/**
 * The text the engine puts around a screen shader's body, and how a driver's line number gets back
 * to the author's file.
 *
 * ## Three constants, and one line that is not
 *
 * [VERTEX_BODY], [FRAGMENT_PREAMBLE] and [FRAGMENT_FOOTER] are constants. The only part chosen at
 * run time is the `#version` line, which comes from the backend, because OpenGL and OpenGL ES
 * disagree about it and about the `precision` qualifiers a fragment stage must carry. [fragment]
 * joins those pieces; it does not *build* GLSL, and nothing here inspects or rewrites what the
 * game wrote. A shader is text somebody authored, the way a `.udea.kts` is, and the engineering
 * standards' ban on generated code built by string concatenation is about generators.
 *
 * ## Why the version comes from Kool rather than from a table here
 *
 * Kool compiles its own shaders through `GlslGenerator`, whose `Hints.glslVersionStr` is decided
 * once from the context's actual GL version and flavour. Reading that same hint means an engine
 * shader and a game shader can never be compiled against different language versions on the same
 * driver - a table of our own would be a second opinion, and the first time it differed the
 * symptom would be a game-only compile error nobody could reproduce with an engine shader.
 *
 * ## The fullscreen triangle
 *
 * [VERTEX_BODY] reads no attributes. It builds one oversized triangle from `gl_VertexID` alone,
 * which covers the whole target with three vertices and no vertex buffer, no attribute pointers
 * and no interpolation seam down the middle of the screen where two triangles would meet. The
 * chain draws it with three indices, because Kool's `GlApi` publishes `drawElements` and no
 * `drawArrays`.
 */
internal object ScreenShaderSource {

    /**
     * The uniforms the engine declares, which a body reads without declaring anything.
     *
     * The list rather than a count: a list invites an addition, and a sentence saying how many
     * there are has to be corrected every time one is added. [ShaderUniforms] refuses a game
     * declaration that repeats any of them.
     */
    val SUPPLIED: List<String> = listOf("uColor", "uDepth", "uMask", "uResolution", "uTexel", "uTime")

    /**
     * The vertex stage, whole. It has no parameters, so every screen shader in a pipeline shares
     * one compiled vertex shader.
     */
    val VERTEX_BODY: String = """
        out vec2 vUv;

        void main() {
            // (0,0), (2,0), (0,2) in UV space; (-1,-1), (3,-1), (-1,3) in clip space.
            vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
            vUv = corner;
            gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
        }
    """.trimIndent() + "\n"

    /**
     * Everything in front of a body: the precision block, the engine's inputs, the prototype of
     * the function the body must define, and the helpers a body may call.
     *
     * The precision block is Kool's, qualifier for qualifier, because Kool ships the same block to
     * the same drivers on both flavours and a block of our own would be a second opinion about
     * something that has exactly one right answer per backend.
     *
     * ## The two inputs besides the colour
     *
     * `uDepth` and `uMask` are both **depth** pictures, and they read the same way: `1.0` is the
     * far plane, which is also "nothing here", and anything less is how far away the nearest
     * surface is. `uDepth` holds everything the 3D stage drew; `uMask` holds only the entities
     * whose `ModelRenderer.mask` is set - the game's units, say, and not its ground. That is what
     * lets a one-pixel outline be found without the scene being drawn a second time.
     *
     * A game with no 3D in it, and a game that has marked nothing, gets `1.0` everywhere in the
     * one it is missing, so a shader written against either reads "nothing here" rather than
     * whatever the last frame left behind. [udeaMasked] is the `0`/`1` form most bodies want.
     */
    val FRAGMENT_PREAMBLE: String = """
        precision highp float;
        precision highp int;
        precision highp sampler2DArray;
        precision highp sampler2DShadow;
        precision highp sampler3D;

        // Supplied by the engine. A body reads these and declares none of them.
        uniform sampler2D uColor;   // the frame so far
        uniform sampler2D uDepth;   // depth of the 3D scene; 1.0 where there is none
        uniform sampler2D uMask;    // depth of the marked entities only; 1.0 where there are none
        uniform vec2 uResolution;   // the frame, in pixels
        uniform vec2 uTexel;        // 1.0 / uResolution
        uniform float uTime;        // render seconds since the first frame

        in vec2 vUv;
        out vec4 udea_fragColor;

        // What the body defines.
        vec4 udeaMain(vec2 uv);

        // 1 where a marked entity was drawn, 0 where none was.
        float udeaMasked(vec2 uv) {
            return step(texture(uMask, uv).r, 0.999999);
        }

        // 1 on a pixel that is not marked but touches one that is, within `width` pixels: the
        // outside edge of the silhouette, which is where a one-pixel outline belongs.
        float udeaOutline(vec2 uv, float width) {
            if (udeaMasked(uv) > 0.5) return 0.0;
            vec2 offset = uTexel * width;
            float up = udeaMasked(uv + vec2(0.0, offset.y));
            float down = udeaMasked(uv - vec2(0.0, offset.y));
            float right = udeaMasked(uv + vec2(offset.x, 0.0));
            float left = udeaMasked(uv - vec2(offset.x, 0.0));
            return step(0.5, max(max(up, down), max(right, left)));
        }

        // The body starts here.
    """.trimIndent() + "\n"

    /** Everything behind a body. The one `main` in a screen shader, and it calls one function. */
    val FRAGMENT_FOOTER: String = "\n" + """
        void main() {
            udea_fragColor = udeaMain(vUv);
        }
    """.trimIndent() + "\n"

    /** The vertex stage for [version], whole and ready to compile. */
    fun vertex(version: String): String = "$version\n$VERTEX_BODY"

    /** [body] compiled for [version]: the preamble, the author's text, and the engine's `main`. */
    fun fragment(version: String, body: String): String =
        "$version\n$FRAGMENT_PREAMBLE$body$FRAGMENT_FOOTER"

    /**
     * How many lines [fragment] puts in front of a body, so a driver's line number can be turned
     * back into a line of the author's file.
     *
     * Counted from the strings rather than written down, because a number written down beside text
     * that may be edited is a number that goes wrong silently and reports the wrong line for ever
     * afterwards.
     */
    fun fragmentLineOffset(version: String): Int =
        "$version\n$FRAGMENT_PREAMBLE".count { it == '\n' }
}
