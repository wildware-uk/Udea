package dev.wildware.udea.assets.pack

import dev.wildware.udea.assets.ResPath
import dev.wildware.udea.assets.Shader
import dev.wildware.udea.assets.reference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A shader's GLSL comes back out of a `.udeapak` in **common code**, on every target this module
 * has.
 *
 * ## Why this test is in `commonTest` and why that is the whole point
 *
 * The defect being fixed is that the documented way to hand GLSL to `UdeaShader.fragment` was
 * `javaClass.getResource("/shaders/scanlines.frag").readText()` - a JVM `Class`, a JVM `URL` and
 * a JVM extension function - which `commonMain` does not have on any target and which therefore
 * had to be written once per platform. This file compiles and runs on `jvm`, `android`, `wasmJs`
 * and both iOS targets, because that is what `udea-assets` builds for, and it reads a shader's
 * source with nothing platform-shaped anywhere in it. A `getResource` line could not be written
 * in this file at all.
 *
 * ## Why the bytes are laid out by hand
 *
 * [BundleFixtures]' own reason: `udea-assets` may not depend on the compiler that writes a real
 * `.udeapak` (`UDEA-MG-006`), so a format bug shared by the writer and the reader would be
 * invisible to a round trip that used both.
 * `udea-assets-compiler`'s `ShaderAssetTest` is the other half - real scripts, real files, real
 * writer, real reader - and `ShaderAssetProof` is the half with a GPU in it.
 *
 * ## No commas in the test names below, and that is not a style choice
 *
 * Kotlin/Native rejects a backticked declaration name containing a comma outright -
 * `Name contains illegal characters: ","` - where the JVM back end accepts it, so a name that
 * reads fine everywhere else in this repository fails `compileTestKotlinIosArm64` and nothing
 * else. Most of the repository's test names are in JVM-only source sets and use commas freely;
 * a name written in this file, or in any `commonTest` of a module with an iOS target, may not.
 */
class ShaderSourceReadTest {

    private val kind = requireNotNull(Shader::class.qualifiedName)

    private val glsl = """
        uniform float uStrength;

        vec4 udeaMain(vec2 uv) {
            return vec4(texture(uColor, uv).rgb * uStrength, 1.0);
        }
    """.trimIndent()

    private fun bundleOf(fields: Map<String, String>): ByteArray = BundleFixtures.bundle(
        listOf(
            Triple(
                SectionKind.GRAPH,
                BundleFormat.GRAPH_SECTION,
                BundleFixtures.graphSection(listOf(Triple("shaders/scanlines", kind, fields))),
            ),
            Triple(SectionKind.ATLAS_INDEX, BundleFormat.ATLAS_SECTION, BundleFixtures.emptyAtlasSection()),
        ),
    )

    @Test
    fun `a packed shader hands back its GLSL and the file it was authored in`() {
        val packed = bundleOf(mapOf("file" to "shaders/scanlines.frag", "source" to glsl))

        BundleReader.open(packed).use { bundle ->
            val shader = bundle.registry[reference<Shader>("shaders/scanlines")]

            assertEquals(ResPath("shaders/scanlines.frag"), shader.file)
            // Byte for byte, newlines included: what a driver is handed is what the author wrote.
            assertEquals(glsl, shader.source)
            assertTrue("udeaMain" in shader.source)
        }
    }

    /**
     * The degenerate record a failed build leaves: `UDEA0041` has already stopped that build, so
     * this decodes rather than throwing from inside the reader.
     */
    @Test
    fun `a shader packed with no source decodes rather than throwing from inside the reader`() {
        val packed = bundleOf(mapOf("file" to "shaders/scanlines.frag", "source" to ""))

        BundleReader.open(packed).use { bundle ->
            assertEquals("", bundle.registry[reference<Shader>("shaders/scanlines")].source)
        }
    }

    /**
     * A `Shader` made in code, rather than by the build, still may not name a file that is not a
     * `.frag` - the same argument `Model` makes for its own extension.
     */
    @Test
    fun `a shader whose file is not a frag is refused at construction`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            Shader(dev.wildware.udea.assets.AssetId("shaders/x"), ResPath("shaders/x.glsl"), glsl)
        }
        assertTrue(Shader.EXTENSION in refused.message.orEmpty(), refused.message.orEmpty())
    }
}
