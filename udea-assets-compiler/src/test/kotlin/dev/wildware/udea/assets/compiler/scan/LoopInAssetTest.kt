package dev.wildware.udea.assets.compiler.scan

import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.UdeaRules
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Loops are banned in assets (issue #192).
 *
 * The editor's Save writes an exact value back into the `.udea.kts` it came from, and a value made
 * inside a loop has no single place in the file to write it to. So pass 1 refuses `repeat`, `for`,
 * `while` and `do`-`while` wherever they appear in a script, and points at the loop.
 */
class LoopInAssetTest {

    private fun scan(root: Path, source: String): FileScan {
        val assets = root.resolve("assets/level")
        assets.createDirectories()
        val file = assets.resolve("arena.udea.kts")
        file.writeText(source)
        return UdeaDeclarationScanner(root, root.resolve("assets")).use { it.scanFile(file) }
    }

    private fun loops(scan: FileScan) = scan.diagnostics.filter { it.ruleId == UdeaRules.LOOP_IN_ASSET.id }

    @Test
    fun `a repeat is an error that points at the repeat`(@TempDir root: Path) {
        val scan = scan(
            root,
            """
            gameConfig()

              repeat(2) { }
            """.trimIndent(),
        )

        val diagnostic = loops(scan).single()
        assertEquals("UDEA0015", diagnostic.ruleId)
        assertEquals(Severity.Error, diagnostic.severity)
        val span = assertNotNull(diagnostic.span)
        assertEquals("assets/level/arena.udea.kts", span.path)
        assertEquals(3, span.startLine)
        assertEquals(3, span.startColumn, "the span starts at the loop, not at the line")
        assertTrue("repeat" in diagnostic.message, diagnostic.message)
    }

    @Test
    fun `for, while and do-while are errors too, each at its own line`(@TempDir root: Path) {
        val scan = scan(
            root,
            """
            val names = listOf("a", "b")
            for (name in names) { }
            var n = 0
            while (n < 2) { n++ }
            do { n-- } while (n > 0)
            """.trimIndent(),
        )

        assertEquals(listOf(2, 4, 5), loops(scan).map { assertNotNull(it.span).startLine })
        assertEquals(
            listOf("for", "while", "do-while"),
            loops(scan).map { it.message.substringAfter('`').substringBefore('`') },
        )
    }

    @Test
    fun `a loop inside a declaration's lambda is found`(@TempDir root: Path) {
        val scan = scan(
            root,
            """
            level(
                name = "arena",
                entities = {
                    entity(name = "player", blueprint = reference("character/orc_elite"))
                    repeat(4) {
                        entity(name = "orc_${'$'}it", blueprint = reference("character/orc"))
                    }
                },
            )
            """.trimIndent(),
        )

        assertEquals(listOf(5), loops(scan).map { assertNotNull(it.span).startLine })
    }

    @Test
    fun `a repeat qualified with its package is still a repeat`(@TempDir root: Path) {
        val scan = scan(
            root,
            """
            gameConfig()
            kotlin.repeat(2) { }
            """.trimIndent(),
        )

        val diagnostic = loops(scan).single()
        val span = assertNotNull(diagnostic.span)
        assertEquals(2, span.startLine)
        assertEquals(1, span.startColumn)
        assertTrue("repeat" in diagnostic.message, diagnostic.message)
    }

    @Test
    fun `an import of repeat is refused, and so is every call through its alias`(@TempDir root: Path) {
        val scan = scan(
            root,
            """
            import kotlin.repeat as times

            gameConfig()
            times(2) { }
            """.trimIndent(),
        )

        assertEquals(listOf(1 to 1, 4 to 1), loops(scan).map { assertNotNull(it.span).let { s -> s.startLine to s.startColumn } })
        assertTrue(loops(scan).all { "repeat" in it.message }, loops(scan).toString())
    }

    /** The control: the words appear, the loops do not. A ban that fired on prose would fail here. */
    @Test
    fun `words that only look like loops are left alone`(@TempDir root: Path) {
        val scan = scan(
            root,
            """
            import kotlin.math.max
            // repeat(2) { } in a comment is not a loop, and neither is for (x in y) or while (true)
            val label = "repeat(3) { for (i in 0..1) { } }"
            val dashes = "-".repeat(3)
            val wider = max(1, 2)
            listOf("idle", "walk").forEach { pose ->
                spriteSheet(name = "orc_${'$'}pose", spritePath = "/orc/${'$'}pose.png")
            }
            """.trimIndent(),
        )

        assertEquals(emptyList(), loops(scan), "no loop in this file: ${scan.diagnostics}")
    }
}
