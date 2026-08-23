package dev.wildware.udea.assets.compiler.scan

import dev.wildware.udea.assets.compiler.AssetCompilerRules
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The boundary of what pass 1 will claim to know (issue #85).
 *
 * A syntactic pass that guesses is worse than one that admits ignorance: a guessed id is a
 * wrong id, and every downstream pass believes it. So there is exactly one sanctioned dynamic
 * form — `forEach` over a constant collection of string literals — and everything else that
 * computes a name is reported with a span and contributes no id.
 */
class NonLiteralIdTest {

    private fun scan(@TempDir root: Path, source: String): FileScan {
        val assets = root.resolve("assets/character")
        assets.createDirectories()
        val file = assets.resolve("orc.udea.kts")
        file.writeText(source)
        return UdeaDeclarationScanner(root, root.resolve("assets")).use { it.scanFile(file) }
    }

    @Test
    fun `a computed name is reported with a file and a line`(@TempDir root: Path) {
        val scan = scan(
            root,
            """
            val prefix = "orc"

            spriteSheet(name = prefix.uppercase(), spritePath = "/a.png")
            """.trimIndent(),
        )

        assertEquals(emptyList(), scan.declarations.map { it.id }, "a computed name yields no id")
        val diagnostic = scan.diagnostics.single()
        assertEquals(AssetCompilerRules.NON_LITERAL_ID.id, diagnostic.ruleId)
        val span = assertNotNull(diagnostic.span)
        assertEquals("assets/character/orc.udea.kts", span.path)
        assertEquals(3, span.startLine)
        assertEquals(20, span.startColumn, "the span points at the name expression, not the call")
        assertTrue("spriteSheet" in diagnostic.message)
    }

    @Test
    fun `the sanctioned constant-list forEach yields every id it generates`(@TempDir root: Path) {
        val scan = scan(
            root,
            """
            listOf("idle", "walk", "attack").forEach { pose ->
                spriteSheet(name = "orc_${'$'}pose", spritePath = "/orc/${'$'}pose.png")
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf("character/orc_idle", "character/orc_walk", "character/orc_attack"),
            scan.declarations.map { it.id },
        )
        assertEquals(emptyList(), scan.diagnostics, "the sanctioned form must not fire udea non-literal-id")
    }

    @Test
    fun `the loop variable may be the name outright, and may be an implicit it`(@TempDir root: Path) {
        val scan = scan(
            root,
            """
            listOf("a", "b").forEach { spriteSheet(name = it, spritePath = "/x.png") }
            """.trimIndent(),
        )
        assertEquals(listOf("character/a", "character/b"), scan.declarations.map { it.id })
        assertEquals(emptyList(), scan.diagnostics)
    }

    @Test
    fun `a constant list held in a file-level val is still constant`(@TempDir root: Path) {
        val scan = scan(
            root,
            """
            val poses = listOf("idle", "walk")

            poses.forEach { spriteSheet(name = it, spritePath = "/x.png") }
            """.trimIndent(),
        )
        assertEquals(listOf("character/idle", "character/walk"), scan.declarations.map { it.id })
    }

    /**
     * The honest failure of the sanctioned form: a receiver pass 1 cannot fold.
     *
     * The lambda is still descended into, so the *diagnostic* points at the name expression
     * inside the loop rather than at the loop — which is where the author has to edit.
     */
    @Test
    fun `a forEach over a non-constant receiver reports the name, not the loop`(@TempDir root: Path) {
        val scan = scan(
            root,
            """
            poseNames().forEach { spriteSheet(name = it, spritePath = "/x.png") }
            """.trimIndent(),
        )
        assertEquals(emptyList(), scan.declarations)
        val span = assertNotNull(scan.diagnostics.single().span)
        assertEquals(1, span.startLine)
        assertEquals(42, span.startColumn)
    }

    /**
     * A declaration nested inside a `forEach` whose name *is* a literal is unaffected by the
     * loop being unfoldable: the id is known regardless of how many times it is declared.
     */
    @Test
    fun `a literal name inside an unfoldable loop is still an id`(@TempDir root: Path) {
        val scan = scan(
            root,
            """
            poseNames().forEach { spriteSheet(name = "orc_idle", spritePath = "/x.png") }
            """.trimIndent(),
        )
        assertEquals(listOf("character/orc_idle"), scan.declarations.map { it.id })
        assertEquals(emptyList(), scan.diagnostics)
    }

    @Test
    fun `a name interpolating something unbound is not an id`(@TempDir root: Path) {
        val scan = scan(
            root,
            """
            spriteSheet(name = "orc_${'$'}{System.currentTimeMillis()}", spritePath = "/x.png")
            """.trimIndent(),
        )
        assertEquals(emptyList(), scan.declarations)
        assertEquals(AssetCompilerRules.NON_LITERAL_ID.id, scan.diagnostics.single().ruleId)
    }
}
