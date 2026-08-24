package dev.wildware.udea.assets.compiler.gen

import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.compiler.scan.UdeaDeclarationScanner
import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `GameAssets` emission from the pass-1 scan.
 *
 * The scan, not the evaluated graph: the whole reason accessors can exist at all is that pass 1
 * is syntactic and runs before anything is compiled (spec 3.6). Generating from the graph would
 * put script evaluation on the path to `compileKotlin` and reintroduce the cycle.
 */
class AccessorGeneratorTest {

    private fun scan(assets: Path) = UdeaDeclarationScanner(TestPaths.repoRoot, assets).use { it.scanTree() }

    private fun generate(assets: Path) = AccessorGenerator.generate(scan(assets).declarations)

    private val packAssets: Path = TestPaths.repoRoot.resolve(
        "udea-assets-compiler/src/test/resources/packassets",
    )

    private fun fileNamed(files: List<GeneratedFile>, name: String): GeneratedFile =
        assertNotNull(files.singleOrNull { it.path.endsWith("/$name.kt") }, "no $name.kt in ${files.map { it.path }}")

    /**
     * The acceptance criterion, as literally as a test can state it without a compiler:
     * `GameAssets.blueprint.player` exists and its declared type is `Ref<Blueprint>`.
     *
     * The compile-tested half is [`the generated source compiles`], which is what actually
     * proves the type resolves; this asserts the *text*, so a failure says which member is
     * wrong rather than dumping a compiler error log.
     */
    @Test
    fun `a blueprint member is declared as a Ref of Blueprint`() {
        val files = generate(packAssets)

        val text = fileNamed(files, "BlueprintAssets").text
        assertTrue(
            "public val player: Ref<Blueprint> = reference(\"blueprint/player\")" in text,
            "BlueprintAssets.kt does not declare `player`:\n$text",
        )
        assertTrue("public val minion: Ref<Blueprint>" in text)
    }

    @Test
    fun `snake case ids become camel case members`() {
        val files = generate(packAssets)

        val text = fileNamed(files, "CharacterAssets").text

        assertTrue("public val orcIdle: Ref<SpriteSheet>" in text, text)
        assertTrue("public val orcIdleAnim: Ref<SpriteAnimation>" in text, text)
        assertTrue("public val orcAttackCue: Ref<SoundCue>" in text, text)
    }

    /** A kind with no runtime type gets no accessor, and does not break the file it is in. */
    @Test
    fun `a declaration with no runtime type is absent rather than guessed`() {
        val files = generate(packAssets)

        val text = fileNamed(files, "CharacterAssets").text

        // `asset("particle", "orc_dust")` -> id `character/orc_dust` -> member `orcDust`, which
        // must not exist: a game declares its own kinds and this module has no type for one.
        assertTrue(
            Regex("""val orcDust\s*:""").find(text) == null,
            "`character/orc_dust` has no AssetData type, so it must not be given an accessor:\n$text",
        )
        assertTrue("orcIdle" in text, "its siblings are still generated")
        // And the kind that *did* acquire a type does get one, which is the other half of the
        // claim: absent because there is no type, not absent because the generator skipped it.
        assertTrue(Regex("""val orc\s*:""").containsMatchIn(text), "`character/orc` is a Character")
    }

    @Test
    fun `an id at the asset root lands in the root group`() {
        val files = generate(packAssets)

        assertTrue("public val config: Ref<GameConfig>" in fileNamed(files, "RootAssets").text)
        assertTrue("public val root: RootAssets" in fileNamed(files, "GameAssets").text)
    }

    @Test
    fun `the aggregate names every group exactly once`() {
        val files = generate(packAssets)

        val aggregate = fileNamed(files, "GameAssets").text
        listOf("blueprint", "character", "level", "root").forEach { group ->
            assertEquals(
                1,
                Regex("""public val $group:""").findAll(aggregate).count(),
                "'$group' should appear once in GameAssets:\n$aggregate",
            )
        }
    }

    /**
     * Adding an asset to one group rewrites only that group's file (issue #90).
     *
     * This is the criterion that makes the 3s edit loop possible, and it is the one a plausible
     * implementation gets wrong by regenerating everything into one file. Checked by scanning a
     * copy of the tree with one extra declaration and comparing every *other* file byte for
     * byte.
     */
    @Test
    fun `adding an asset to one group leaves the other generated files byte-identical`(
    ) {
        val before = generate(packAssets)

        val extended = TestPaths.scratch("accessors-extended")
        packAssets.copyTreeTo(extended)
        extended.resolve("blueprint/extra.udea.kts")
            .writeText("blueprint(name = \"tower\", components = listOf(\"turret\"))\n")

        val after = generate(extended)

        val byPath = after.associateBy { it.path }
        val changed = before.filter { byPath[it.path]?.text != it.text }.map { it.path }
        assertEquals(
            listOf("dev/wildware/udea/generated/BlueprintAssets.kt"),
            changed,
            "only the group that gained a member may change",
        )
        assertTrue("public val tower: Ref<Blueprint>" in fileNamed(after, "BlueprintAssets").text)
    }

    /** Generation is a pure function of ids and kinds, so it reproduces byte for byte. */
    @Test
    fun `generating twice produces identical text`() {
        assertEquals(generate(packAssets), generate(packAssets))
    }

    @OptIn(ExperimentalPathApi::class)
    private fun Path.copyTreeTo(target: Path) {
        target.createDirectories()
        copyToRecursively(target, followLinks = false, overwrite = true)
    }
}
