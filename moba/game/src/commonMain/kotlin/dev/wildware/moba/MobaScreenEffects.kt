package dev.wildware.moba

import dev.wildware.udea.assets.AssetRegistry
import dev.wildware.udea.generated.GameAssets
import dev.wildware.udea.render.shader.FloatUniform
import dev.wildware.udea.render.shader.UdeaShader

/**
 * The game's own screen effects, built from `.frag` files it declared as assets.
 *
 * ## What this file is really demonstrating
 *
 * That the whole of it is `commonMain`. The form the engine used to document read the `.frag` out
 * of the jar through `getResource` on a JVM `Class` and a JVM extension function on the `URL` it
 * came back with - three JVM-only things in one line, so a game that ships on more than one
 * platform had to write it once per platform to load a file that is byte-identical everywhere.
 * (`MobaShaderAssetTest` scans this file for exactly those names, so the line is described here
 * rather than quoted.)
 *
 * Here the GLSL is an asset: `shaders/scanlines.frag` is declared in
 * `assets/shaders/shaders.udea.kts`, the build reads and checks it and packs the text, and this
 * module compiles for every target `udea-render` has off this one source set.
 *
 * ## Why `moba` does not register one by default
 *
 * A screen effect is a look, and `moba` has one already: every shot, proof and agent screenshot
 * in this repository is the game without a post-process over it, and switching one on by default
 * would change all of them for a reason that has nothing to do with the game. So the game builds
 * it and whoever wants it registers it - `ShaderAssetProof` does, and that is where the pictures
 * come from.
 */
public object MobaScreenEffects {

    /** How much of a dimmed row's brightness the effect takes, at its default strength. */
    public const val DEFAULT_STRENGTH: Float = 0.25f

    /**
     * `shaders/scanlines`: every other row of the finished frame dimmed by [strength].
     *
     * @param assets the graph the shader is resolved in - `MobaAssets.registry` in the game, and
     *   a harness's own in a test. The caller's, because a process may hold more than one.
     * @param strength the starting value of the shader's `uStrength`. The returned [Scanlines]
     *   carries the handle, so a graphics setting writes it per frame without a name lookup.
     */
    public fun scanlines(assets: AssetRegistry, strength: Float = DEFAULT_STRENGTH): Scanlines {
        lateinit var handle: FloatUniform
        val shader = UdeaShader.fragment(GameAssets.shaders.scanlines, assets) {
            handle = float(STRENGTH_UNIFORM, strength)
        }
        return Scanlines(shader, handle)
    }

    /** The shader to register, and the one parameter it reads. */
    public class Scanlines internal constructor(
        /** Hand this to `RenderRegistry.screenPass`. */
        public val shader: UdeaShader,
        /** How much brightness a dimmed row loses, `0f` to `1f`. Written whenever. */
        public val strength: FloatUniform,
    )

    /** The name the `.frag` declares and this file binds. Both sides spell it once. */
    private const val STRENGTH_UNIFORM: String = "uStrength"
}
