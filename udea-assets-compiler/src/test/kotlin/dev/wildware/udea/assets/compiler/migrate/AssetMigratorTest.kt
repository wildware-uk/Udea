package dev.wildware.udea.assets.compiler.migrate

import dev.wildware.udea.assets.compiler.TestPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The five rewrite rules, one at a time, against text rather than a tree.
 *
 * Each test states the rewrite *and* the thing the rule must not touch, because every one of
 * these rules has a near-miss that a text-based migrator would corrupt: `kotlin.lazy`, an asset
 * id that looks like a path, a `columns` on a sheet that is not there.
 */
class AssetMigratorTest {

    private val probe = SheetProbe { path ->
        when (path) {
            "sprites/orc/idle.png" -> 600 to 100
            "sprites/orc/walk.png" -> 800 to 100
            else -> null
        }
    }

    private val migrator = AssetMigrator(Path.of("."), probe)

    private fun migrate(text: String): MigrationResult =
        migrator.migrateText("test/fixture.udea.kts", "fixture.udea.kts", text)

    @Test
    fun `bundle is unwrapped and its body dedented`() {
        val result = migrate(
            """
            bundle {
                spriteSheet(
                    name = "idle",
                    spritePath = "sprites/orc/idle.png",
                    columns = 6,
                )
            }
            """.trimIndent(),
        )
        assertFalse(result.migrated.contains("bundle {"), result.migrated)
        assertTrue(result.migrated.startsWith("spriteSheet("), result.migrated)
        // Dedented by exactly one level: the declaration's own arguments keep their indent
        // relative to it.
        assertTrue(result.migrated.contains("\n    name = \"idle\","), result.migrated)
        assertEquals(1, result.edits.count { it.rule == MigrationRule.UnwrapBundle })
    }

    @Test
    fun `a named lazy argument loses its callee and keeps its lambda`() {
        val result = migrate(
            """
            character(
                name = "orc",
                components = lazy {
                    networkable()
                },
            )
            """.trimIndent(),
        )
        assertTrue(result.migrated.contains("components = {"), result.migrated)
        assertFalse(result.migrated.contains("lazy {"), result.migrated)
        assertTrue(result.migrated.contains("networkable()"), result.migrated)
    }

    @Test
    fun `a qualified kotlin lazy is left alone`() {
        val text = """
            val late = kotlin.lazy { 3 }
        """.trimIndent()
        val result = migrate(text)
        assertEquals(text, result.migrated.trimEnd('\n'))
        assertTrue(result.edits.isEmpty(), result.edits.toString())
    }

    @Test
    fun `an unnamed lazy is refused rather than rewritten`() {
        val result = migrate(
            """
            character(
                lazy {
                    networkable()
                },
            )
            """.trimIndent(),
        )
        assertTrue(result.migrated.contains("lazy {"), "it must not have been rewritten")
        assertEquals(1, result.undecided.size, result.undecided.toString())
        assertTrue(result.migrated.contains(AssetMigrator.TODO_MARKER), result.migrated)
    }

    @Test
    fun `a leading slash is stripped from a resource path but not from an asset id`() {
        val result = migrate(
            """
            soundCue(
                name = "hit",
                sounds = {
                    add("/sounds/orc/hurt.ogg")
                },
            )
            spriteAnimation(name = "a", sheet = reference("character/orc_idle"))
            """.trimIndent(),
        )
        assertTrue(result.migrated.contains("\"sounds/orc/hurt.ogg\""), result.migrated)
        assertTrue(result.migrated.contains("\"character/orc_idle\""), result.migrated)
        assertEquals(1, result.edits.count { it.rule == MigrationRule.StripLeadingSlash })
    }

    @Test
    fun `a columns that disagrees with the sheet is corrected from the art`() {
        val result = migrate(
            """
            spriteSheet(
                name = "walk",
                spritePath = "/sprites/orc/walk.png",
                rows = 1,
                columns = 6,
            )
            """.trimIndent(),
        )
        // 800x100 in one row is eight 100x100 frames, whatever the author typed.
        assertTrue(result.migrated.contains("columns = 8"), result.migrated)
        assertEquals(1, result.edits.count { it.rule == MigrationRule.InferColumns })
    }

    @Test
    fun `a columns that agrees with the sheet is not rewritten`() {
        val result = migrate(
            """
            spriteSheet(
                name = "idle",
                spritePath = "/sprites/orc/idle.png",
                columns = 6,
            )
            """.trimIndent(),
        )
        assertEquals(0, result.edits.count { it.rule == MigrationRule.InferColumns }, result.edits.toString())
    }

    @Test
    fun `a sheet that is not under the asset root is reported and left as written`() {
        val result = migrate(
            """
            spriteSheet(
                name = "ghost",
                spritePath = "/sprites/wizard/Priest-Idle.png",
                columns = 6,
            )
            """.trimIndent(),
        )
        assertTrue(result.migrated.contains("columns = 6"), result.migrated)
        assertEquals(1, result.undecided.size, result.undecided.toString())
        assertTrue(
            result.undecided.single().message.contains("sprites/wizard/Priest-Idle.png"),
            result.undecided.single().message,
        )
    }

    @Test
    fun `random is seeded and the seed differs between two files`() {
        val text = """
            import kotlin.random.Random

            level(
                entities = {
                    val x = Random.nextFloat()
                },
            )
        """.trimIndent()
        val a = migrator.migrateText("level/a.udea.kts", "a.udea.kts", text)
        assertTrue(a.migrated.contains("private val ${AssetMigrator.SEEDED} = Random("), a.migrated)
        assertTrue(a.migrated.contains("${AssetMigrator.SEEDED}.nextFloat()"), a.migrated)
        assertFalse(a.migrated.contains("= Random.nextFloat()"), a.migrated)

        val b = migrator.migrateText("level/b.udea.kts", "b.udea.kts", text)
        val seedOf = { s: String -> Regex("""= Random\((\d+)\)""").find(s)!!.groupValues[1] }
        assertTrue(seedOf(a.migrated) != seedOf(b.migrated), "two levels must not lay out identically")
    }

    @Test
    fun `a file with nothing to migrate is returned byte-identical`() {
        val text = """
            gameConfig(defaultCharacter = reference("blueprint/player"))
        """.trimIndent()
        val result = migrate(text)
        assertFalse(result.changed)
        assertEquals(text, result.migrated)
    }

    /**
     * The corpus-wide claim: the example tree is a real input and the migrator survives it.
     *
     * Not a smoke test. It asserts the three greps the acceptance criteria name, on the real
     * nineteen files, and it asserts the six broken wizard paths are *found* rather than
     * silently passed over.
     */
    @Test
    fun `the example corpus migrates and leaves no old spelling behind`() {
        val source = TestPaths.exampleAssets
        val report = AssetTreeMigration(source, source).run(write = false)
        assertEquals(19, report.results.size, "the corpus is nineteen scripts")
        for (result in report.results) {
            assertFalse(result.migrated.contains("bundle {"), "${result.path} still wraps a bundle")
            assertFalse(
                Regex("""(^|[^.\w])lazy\s*\{""").containsMatchIn(result.migrated),
                "${result.path} still calls the shadowing `lazy`",
            )
            assertFalse(
                Regex(""""/[^"]*\.[a-z]{3}"""").containsMatchIn(result.migrated),
                "${result.path} still holds a leading-slash resource path",
            )
        }
        val wizard = report.results.single { it.path.endsWith("wizard.udea.kts") }
        assertEquals(6, wizard.undecided.size, wizard.undecided.joinToString("\n"))
    }
}
