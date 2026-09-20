package dev.wildware.udea.assets

/**
 * A fragment shader a game authored, as GLSL text: `shaders/scanlines.frag`.
 *
 * ## Why this one carries its text and [Model] carries only a path
 *
 * Every other file-backed asset here names a file and leaves reading it to whoever can: a `.glb`
 * is read by `udea-render`'s `ModelFiles`, which is `jvmMain`, because a glTF parser is a
 * platform's. That answer does not work for a shader, and the reason is the defect this type
 * exists to fix. The documented way to hand GLSL to `UdeaShader.fragment` was
 * `javaClass.getResource("/shaders/scanlines.frag").readText()` - a JVM `Class`, a JVM `URL` and
 * a JVM extension function, none of which `commonMain` has. A game following it had to write the
 * line once per platform to load a file that is byte-identical on all of them.
 *
 * So the text travels in the asset. It is read once, at build time, by
 * `udea-assets-compiler`'s `ShaderSources`; it is packed into the `.udeapak` as an ordinary
 * string field of an ordinary graph record; and `BundleReader` hands it back in `commonMain` on
 * every target, with no loader, no `expect`/`actual` and no platform type anywhere on the path.
 * A game writes `UdeaShader.fragment(GameAssets.shaders.scanlines, assets)`.
 *
 * ## What is checked where
 *
 * [file]'s extension is checked here, because a `Shader` can be made in code without passing
 * through the build - the same argument [Model] makes for its own extension.
 *
 * [source] is **not** checked here, because the two things wrong with a screen shader body have
 * two different shapes: at build time they are a `UdeaDiagnostic` with a span and a rule id, and
 * at run time they are an `IllegalArgumentException` a shipped game can print. What this class
 * owns is the *facts* both sides check against - [ENTRY_POINT] and [VERSION_PRAGMA] - so that
 * `udea-assets-compiler`'s `ShaderFileValidator` and `udea-render`'s `UdeaShader.fragment` cannot
 * come to disagree about what a screen shader is. They are here and not in either of them because
 * this module is the only one both can see.
 *
 * That also decides the one degenerate case: a build that could not read the file reports
 * `UDEA0041` and packs an empty [source] rather than failing mid-pack, so an author sees the
 * diagnostic that names their file instead of an exception from inside the packer.
 */
public data class Shader(
    override val id: AssetId,
    /** The `.frag` the body was authored in, relative to the asset root. */
    public val file: ResPath,
    /** The GLSL the file held, verbatim, as the build read it. */
    public val source: String,
) : AssetData {

    init {
        require(file.extension == EXTENSION) {
            "shader '$id' names '$file'; a shader is a GLSL fragment body, a .$EXTENSION file"
        }
    }

    public companion object {
        /** The one extension a shader file may have. */
        public const val EXTENSION: String = "frag"

        /**
         * The one function a screen shader body defines: `vec4 udeaMain(vec2 uv)`.
         *
         * The engine writes the `main` that calls it, so a body that never mentions this name has
         * nothing for the engine to call.
         */
        public const val ENTRY_POINT: String = "udeaMain"

        /**
         * A `#version` pragma anywhere in a body, **comments included**.
         *
         * Deliberately not comment-aware. A `#version` written in a comment is one line away from
         * being uncommented, and a rule a reader can predict beats one that is clever. The engine
         * writes that line itself, per backend, because OpenGL and OpenGL ES disagree about it and
         * about the precision qualifiers a fragment stage needs - so a body that states its own
         * works on the desktop and fails on a phone.
         */
        public val VERSION_PRAGMA: Regex = Regex("""#\s*version\b""")
    }
}
