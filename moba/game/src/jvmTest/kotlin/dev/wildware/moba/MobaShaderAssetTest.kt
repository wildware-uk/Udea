package dev.wildware.moba

import dev.wildware.udea.assets.Shader
import dev.wildware.udea.generated.GameAssets
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The game's own screen effect, from the `.frag` on disk to the `UdeaShader` a renderer is handed.
 *
 * ## What each half proves
 *
 * - **The pack carries the text.** The bundle on this test's classpath is the one
 *   `udeaPackBundle` wrote from `moba/game/assets/`, and the GLSL it holds for
 *   `shaders/scanlines` is compared against the text of `shaders/scanlines.frag`, line endings
 *   made LF as the pack makes them - so this fails
 *   if the build stops reading the file, starts reading a different one, or packs a path where
 *   the text should be.
 * - **The game names it as an accessor.** `MobaScreenEffects.scanlines` is `commonMain` and
 *   builds the shader from `GameAssets.shaders.scanlines`; what comes back has to name the
 *   author's file, because that is what a compile failure prints.
 * - **And the source it is built from has nothing platform-shaped in it.** The JVM compiler
 *   cannot catch that, because `moba:game` builds for `jvm` and `android` and both of those are
 *   JVM-family - `java.nio.file.Path` compiles on either. So it is read off the file, with a
 *   control that shows the reader does find the line it is looking for.
 *
 * The GPU half is `:moba:desktop:runShaderAssetProof`, which draws the effect and measures it.
 */
class MobaShaderAssetTest {

    @Test
    fun `the packed bundle holds the GLSL of the real frag file, byte for byte`() {
        val shader = MobaAssets.registry[GameAssets.shaders.scanlines]

        assertEquals(SHADER_FILE, shader.file.value)
        // The file's text with its line endings made LF, because the pack makes them LF: a shader's
        // line endings must not move the asset graph hash (`ShaderSources`, windows-crlf-shaders).
        // A Windows checkout has CRLF files, and comparing its raw bytes failed there on every run
        // that executed this test rather than restoring it from the build cache. Only the endings
        // are forgiven: every other byte is still compared.
        assertEquals(
            shaderFile().readText().replace("\r\n", "\n").replace('\r', '\n'),
            shader.source,
            "the packed GLSL is not the text of $SHADER_FILE",
        )
    }

    @Test
    fun `the game builds its screen effect from the accessor, naming the author's file`() {
        val scanlines = MobaScreenEffects.scanlines(MobaAssets.registry)

        assertEquals(SHADER_FILE, scanlines.shader.path, "a compile failure would name the wrong file")
        assertEquals(MobaScreenEffects.DEFAULT_STRENGTH, scanlines.strength.value)
    }

    @Test
    fun `the strength the game asks for is the strength the uniform starts at`() {
        val dim = MobaScreenEffects.scanlines(MobaAssets.registry, strength = 0.75f)

        assertEquals(0.75f, dim.strength.value)
    }

    /**
     * The source that names the shader has no JVM-only construct in it.
     *
     * The control is the second half and is not decoration: a scanner that found nothing because
     * it was looking in the wrong place, or with a pattern that matches nothing, reads exactly
     * like a pass. So the same patterns are run over the line the old documentation shipped, and
     * that has to be found.
     */
    @Test
    fun `the common source that builds the shader names nothing JVM-only`() {
        val source = File(moduleRoot(), "src/commonMain/kotlin/dev/wildware/moba/MobaScreenEffects.kt")
        assertTrue(source.isFile, "not found: $source")

        val offenders = JVM_ONLY.filter { it.containsMatchIn(source.readText()) }
        assertEquals(
            emptyList(),
            offenders.map { it.pattern },
            "commonMain may not name a JVM type; this is the whole point of the shader being an asset",
        )

        // The control: the line `docs/new-game.md` used to ship, which every pattern above is for.
        val wasShipped = """val source = checkNotNull(javaClass.getResource("/shaders/scanlines.frag")).readText()"""
        assertEquals(
            listOf("""\bjavaClass\b"""),
            JVM_ONLY.filter { it.containsMatchIn(wasShipped) }.map { it.pattern },
            "the scan does not find the defect it exists to find, so its silence means nothing",
        )
    }

    /**
     * `moba/game`, from wherever the test JVM was started - Gradle starts it in the project
     * directory and an IDE may start it at the repository root. The same walk `MobaAudioTest`
     * does, for the same reason.
     */
    private fun moduleRoot(): File {
        var candidate: File? = File(".").canonicalFile
        while (candidate != null) {
            if (File(candidate, "assets/shaders").isDirectory) return candidate
            val nested = File(candidate, "moba/game")
            if (File(nested, "assets/shaders").isDirectory) return nested
            candidate = candidate.parentFile
        }
        error("no moba/game/assets/shaders directory above ${File(".").canonicalFile}")
    }

    private fun shaderFile(): File = File(moduleRoot(), "assets/$SHADER_FILE")

    private companion object {

        /** The declaration in `assets/shaders/shaders.udea.kts`, and the file it names. */
        const val SHADER_FILE: String = "shaders/scanlines.frag"

        /**
         * What may not appear in `commonMain`, as the patterns rather than as a count.
         *
         * `javaClass` is the property the old example used; the two import prefixes are how a
         * JVM type gets named at all. Word-bounded so that a comment mentioning `java` in prose
         * is not a finding - the fence has to fail on code and stay quiet on English, and the
         * control below asserts it fails on the code.
         */
        val JVM_ONLY: List<Regex> = listOf(
            Regex("""\bjavaClass\b"""),
            Regex("""^import java\.""", RegexOption.MULTILINE),
            Regex("""^import javax\.""", RegexOption.MULTILINE),
        )
    }

    /** `Shader` is named so this file fails to compile if the accessor's type changes. */
    @Suppress("unused")
    private val typed: dev.wildware.udea.assets.Ref<Shader> = GameAssets.shaders.scanlines
}
