package dev.wildware.udea.assets

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The regression for the two-keys-for-one-file bug: a script wrote
 * `spritePath = "/sprites/orc_elite/orc_elite_idle.png"` while the loader registered the same file
 * under the stripped key `sprites/...`, so the lookup missed and the texture loaded twice.
 */
class ResPathTest {

    @Test
    fun `a leading slash is normalised away`() {
        assertEquals("sprites/a.png", ResPath("/sprites/a.png").value)
    }

    @Test
    fun `the two spellings of one file are the same value`() {
        assertEquals(ResPath("sprites/a.png"), ResPath("/sprites/a.png"))
        assertEquals(ResPath("sprites/a.png").hashCode(), ResPath("/sprites/a.png").hashCode())
    }

    @Test
    fun `a windows separator and a doubled separator normalise too`() {
        assertEquals("sprites/orc/a.png", ResPath("\\sprites\\orc\\a.png").value)
        assertEquals("sprites/orc/a.png", ResPath("sprites//orc///a.png").value)
    }

    @Test
    fun `a path that escapes the asset root is rejected`() {
        assertFailsWith<IllegalArgumentException> { ResPath("../../etc/passwd") }
        assertFailsWith<IllegalArgumentException> { ResPath("sprites/../../secrets.png") }
    }

    @Test
    fun `a path must name a file`() {
        assertFailsWith<IllegalArgumentException> { ResPath("") }
        assertFailsWith<IllegalArgumentException> { ResPath("   ") }
        assertFailsWith<IllegalArgumentException> { ResPath("///") }
    }

    @Test
    fun `the extension is lowercased and empty when there is none`() {
        assertEquals("png", ResPath("sprites/A.PNG").extension)
        assertEquals("", ResPath("sprites/atlas").extension)
    }

    @Test
    fun `no asset in the model can hold a path with a leading slash`() {
        // Constructed the way a script writes them - every one of these was a raw String field
        // in the old tree, and every one of them is where the doubled key came from.
        val sheet = SpriteSheet(AssetId("a/b"), ResPath("/sprites/a.png"), columns = 1, rows = 1)
        val cue = SoundCue(AssetId("a/c"), listOf(ResPath("/sounds/a.wav")))
        val config = GameConfig(AssetId("a/d"), backgroundTexture = ResPath("/bg.png"))

        assertEquals("sprites/a.png", sheet.texture.value)
        assertEquals("sounds/a.wav", cue.sounds.single().value)
        assertEquals("bg.png", config.backgroundTexture?.value)
    }

    /**
     * The structural half of the assertion above: normalisation only helps for a field that is a
     * [ResPath] in the first place, and `spritePath: String` is precisely the declaration that
     * caused the bug. A new asset kind that reaches for a `String` fails here rather than shipping.
     */
    @Test
    fun `no main source declares a path-shaped property as a String`() {
        val offenders = mainSources().flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> PATH_SHAPED_STRING.containsMatchIn(line) }
                .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
        }

        assertEquals(
            emptyList(),
            offenders,
            "a path-shaped property must be a ResPath, which normalises; found: $offenders",
        )
    }

    @Test
    fun `the source scan has sources to scan`() {
        assertTrue(mainSources().size >= 10, "found only ${mainSources().size} main sources")
    }

    private fun mainSources(): List<File> =
        File(moduleRoot(), "src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()

    private companion object {
        /** `val spritePath: String`, `var texturePath: String?`, `path: String = ...`. */
        val PATH_SHAPED_STRING = Regex("""\b\w*([Pp]ath|[Tt]exture|[Ff]ile)\s*:\s*String\b""")

        fun moduleRoot(): File {
            var candidate: File? = File("").absoluteFile
            while (candidate != null) {
                if (candidate.name == "udea-assets" && File(candidate, "build.gradle.kts").isFile) {
                    return candidate
                }
                val child = File(candidate, "udea-assets")
                if (File(child, "build.gradle.kts").isFile) return child
                candidate = candidate.parentFile
            }
            error("could not locate the udea-assets module from ${File("").absolutePath}")
        }
    }
}
