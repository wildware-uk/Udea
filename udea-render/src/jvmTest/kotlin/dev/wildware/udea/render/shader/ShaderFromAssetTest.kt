package dev.wildware.udea.render.shader

import dev.wildware.udea.assets.AssetData
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.AssetRegistry
import dev.wildware.udea.assets.ResPath
import dev.wildware.udea.assets.Shader
import dev.wildware.udea.assets.UnknownAssetException
import dev.wildware.udea.assets.reference
import dev.wildware.udea.render.RenderRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `UdeaShader.fragment(GameAssets.shaders.scanlines, assets)`: the form a game writes.
 *
 * What this pins is that the overload is a *resolution* and nothing else - it takes the text and
 * the path out of the asset the reference names, and then makes exactly the same checks the
 * `path`/`source` overload makes. A second opinion about GLSL on this side of the API is the
 * failure this test exists to catch, because it would show up only as a shader the build accepted
 * and the runtime refused.
 *
 * No graphics context is involved: `fragment` is construction, and a driver sees the shader later,
 * when a pipeline installs it. `GlScreenShaderTest` is the half that needs a GL context.
 */
class ShaderFromAssetTest {

    private val body = """
        uniform float uStrength;

        vec4 udeaMain(vec2 uv) {
            return vec4(texture(uColor, uv).rgb * uStrength, 1.0);
        }
    """.trimIndent()

    private fun registryOf(vararg assets: AssetData): AssetRegistry =
        AssetRegistry(arrayOf(*assets), ByteArray(32))

    @Test
    fun `the shader is built from the asset's own path and text`() {
        val assets = registryOf(
            Shader(AssetId("shaders/scanlines"), ResPath("shaders/scanlines.frag"), body),
        )

        lateinit var strength: FloatUniform
        val shader = UdeaShader.fragment(reference<Shader>("shaders/scanlines"), assets) {
            strength = float("uStrength", 0.25f)
        }

        assertEquals("shaders/scanlines.frag", shader.path, "the path a compile failure names")
        assertEquals(body, shader.source, "the body handed to the driver is the asset's, verbatim")
        assertEquals(0.25f, strength.value)
    }

    /**
     * The text really is read out of the graph each time, rather than captured from anywhere else.
     *
     * A shader built from an asset whose source says `uAmount` has `uAmount` in it and does not
     * have `uStrength`. Both halves, because a `fragment` that returned a constant would pass the
     * first assertion of the test above and this one's first assertion too.
     */
    @Test
    fun `changing the asset's source changes the shader built from it`() {
        val other = body.replace("uStrength", "uAmount")
        val assets = registryOf(Shader(AssetId("shaders/scanlines"), ResPath("shaders/scanlines.frag"), other))

        val shader = UdeaShader.fragment(reference<Shader>("shaders/scanlines"), assets)

        assertTrue("uAmount" in shader.source, "the body was not the asset's: ${shader.source}")
        assertTrue("uStrength" !in shader.source, "a stale body was used: ${shader.source}")
    }

    @Test
    fun `a shader asset whose source states a version is refused here too`() {
        val assets = registryOf(
            Shader(AssetId("shaders/bad"), ResPath("shaders/bad.frag"), "#version 330\n$body"),
        )

        val refused = assertFailsWith<IllegalArgumentException> {
            UdeaShader.fragment(reference<Shader>("shaders/bad"), assets)
        }
        assertTrue("#version" in refused.message.orEmpty(), refused.message.orEmpty())
        assertTrue("shaders/bad.frag" in refused.message.orEmpty(), refused.message.orEmpty())
    }

    /**
     * The shape a build that could not read the `.frag` leaves behind.
     *
     * `UDEA0041` has already failed that build, so this is the belt to its braces - and what makes
     * it worth having is that the message names the author's file rather than saying "blank
     * source", so it is actionable if it is ever seen.
     */
    @Test
    fun `a shader asset with no source is refused, naming the file`() {
        val assets = registryOf(Shader(AssetId("shaders/empty"), ResPath("shaders/empty.frag"), ""))

        val refused = assertFailsWith<IllegalArgumentException> {
            UdeaShader.fragment(reference<Shader>("shaders/empty"), assets)
        }
        assertTrue("shaders/empty.frag" in refused.message.orEmpty(), refused.message.orEmpty())
    }

    @Test
    fun `a reference to a shader the graph does not hold fails at resolution, with a did-you-mean`() {
        val assets = registryOf(
            Shader(AssetId("shaders/scanlines"), ResPath("shaders/scanlines.frag"), body),
        )

        val missing = assertFailsWith<UnknownAssetException> {
            UdeaShader.fragment(reference<Shader>("shaders/scanlins"), assets)
        }
        assertEquals(AssetId("shaders/scanlines"), missing.suggestion)
    }

    /** The whole line a game writes: build it from the accessor, register it, keep the handle. */
    @Test
    fun `the shader a game registers is the one it was handed back`() {
        val assets = registryOf(
            Shader(AssetId("shaders/scanlines"), ResPath("shaders/scanlines.frag"), body),
        )
        val registry = RenderRegistry()

        val built = UdeaShader.fragment(reference<Shader>("shaders/scanlines"), assets)
        val registered = registry.screenPass(built)

        assertSame(built, registered)
        assertEquals("shaders/scanlines.frag", registered.path)
    }
}
