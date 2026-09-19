package dev.wildware.udea.assets.compiler

import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import dev.wildware.udea.diagnostics.UdeaRules
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The loop ban as the compiler sees it (issue #192): by what a call **resolves to**, not by how
 * it is spelled.
 *
 * Pass 1 (`LoopInAssetTest`) matches names, and review round 1 showed the cost: `kotlin.repeat`
 * and `import kotlin.repeat as times` both walked past it. Here each script goes through
 * [AssetCompiler], the real pass 2, whose K2 compile carries `udea-compiler-plugin`'s loop
 * checker. That checker refuses every loop node and every lambda handed to a callee that may run
 * it more than once - anything that is neither a Udea asset-DSL function nor declares
 * `callsInPlace(EXACTLY_ONCE | AT_MOST_ONCE)` for that parameter. So the spelling of the call is
 * irrelevant: every script below except the negatives spells a repeat a different way.
 *
 * Nothing here runs pass 1, so a red case cannot be the name matcher's.
 */
class AssetLoopResolutionTest {

    private fun compile(name: String, source: String): List<UdeaDiagnostic> {
        val root = TestPaths.scratch("loop-resolution-$name")
        val assets = root.resolve("assets")
        assets.createDirectories()
        val file = assets.resolve("arena.udea.kts")
        file.writeText(source)
        return AssetCompiler(
            repoRoot = root,
            assetRoot = assets,
            scriptClasspath = TestPaths.compilerClasspath,
            cacheDirectory = root.resolve("cache"),
        ).compile(listOf(file)).diagnostics
    }

    private fun loops(diagnostics: List<UdeaDiagnostic>) =
        diagnostics.filter { it.ruleId == UdeaRules.LOOP_IN_ASSET.id }

    /** Line and column of every UDEA0015, in file order. */
    private fun loopSites(diagnostics: List<UdeaDiagnostic>): List<Pair<Int, Int>> =
        loops(diagnostics).map { assertNotNull(it.span).let { span -> span.startLine to span.startColumn } }

    @Test
    fun `a bare repeat is refused at the call`() {
        val diagnostics = compile("bare", "blueprint(name = \"b\")\n  repeat(2) { }\n")
        assertEquals(listOf(2 to 3), loopSites(diagnostics), diagnostics.toString())
        val loop = loops(diagnostics).single()
        assertEquals(Severity.Error, loop.severity)
        assertEquals("assets/arena.udea.kts", assertNotNull(loop.span).path)
        assertTrue("repeat" in loop.message, loop.message)
        assertTrue(!loop.message.startsWith("UDEA0015"), "the id is the rule, not message text: ${loop.message}")
    }

    @Test
    fun `a repeat qualified with its package is refused`() {
        val diagnostics = compile("qualified", "blueprint(name = \"b\")\nkotlin.repeat(2) { }\n")
        assertEquals(listOf(2 to 1), loopSites(diagnostics), diagnostics.toString())
    }

    @Test
    fun `a repeat imported under another name is refused at the call`() {
        val diagnostics = compile(
            "aliased",
            "import kotlin.repeat as times\n\nblueprint(name = \"b\")\ntimes(2) { }\n",
        )
        assertEquals(listOf(4 to 1), loopSites(diagnostics), diagnostics.toString())
    }

    @Test
    fun `forEach over a range is refused`() {
        val diagnostics = compile("foreach", "(1..3).forEach { i -> blueprint(name = \"b\$i\") }\n")
        assertEquals(listOf(1 to 1), loopSites(diagnostics), diagnostics.toString())
        assertTrue("forEach" in loops(diagnostics).single().message, diagnostics.toString())
    }

    @Test
    fun `a helper of the script's own that runs its lambda twice is refused at the call`() {
        val diagnostics = compile(
            "helper",
            """
            fun twice(body: () -> Unit) {
                body()
                body()
            }

            twice { blueprint(name = "b") }
            """.trimIndent(),
        )
        // No loop keyword anywhere: only the resolved callee says this repeats.
        assertEquals(listOf(6 to 1), loopSites(diagnostics), diagnostics.toString())
    }

    @Test
    fun `a helper that loops inside is refused at the loop and at the call`() {
        val diagnostics = compile(
            "helper-loop",
            """
            fun times(n: Int, body: (Int) -> Unit) {
                var i = 0
                while (i < n) {
                    body(i)
                    i++
                }
            }

            times(3) { i -> blueprint(name = "b${'$'}i") }
            """.trimIndent(),
        )
        assertEquals(listOf(3 to 5, 9 to 1), loopSites(diagnostics), diagnostics.toString())
    }

    @Test
    fun `for, while and do-while are refused, each at its own keyword`() {
        val diagnostics = compile(
            "keywords",
            """
            for (i in 0 until 2) { }
            var n = 0
            while (n < 2) { n++ }
            do { n-- } while (n > 0)
            """.trimIndent(),
        )
        assertEquals(listOf(1 to 1, 3 to 1, 4 to 1), loopSites(diagnostics), diagnostics.toString())
        assertEquals(
            listOf("for", "while", "do-while"),
            loops(diagnostics).map { it.message.substringAfter('`').substringBefore('`') },
        )
    }

    @Test
    fun `a function that calls itself is refused at the recursive call`() {
        val diagnostics = compile(
            "recursion",
            """
            fun spawn(n: Int) {
                if (n == 0) return
                blueprint(name = "b${'$'}n")
                spawn(n - 1)
            }

            spawn(3)
            """.trimIndent(),
        )
        assertEquals(listOf(4 to 5), loopSites(diagnostics), diagnostics.toString())
    }

    /**
     * The negatives. The scope functions each declare `callsInPlace(block, EXACTLY_ONCE)`, and
     * the DSL builders run each lambda they take once, which `@AssetDsl` records. A checker that
     * refused every lambda would fail here, and so would one that forgot to scope itself.
     */
    @Test
    fun `scope functions and the asset DSL's own builders are not loops`() {
        val diagnostics = compile(
            "negatives",
            """
            val base = "orc".let { it + "_base" }
            val tags = mutableListOf<String>().apply { add("melee") }.also { it.add("orc") }
            val size = run { 1f }
            with(tags) { add("elite") }
            val ok = "x".takeIf { it.isNotEmpty() }

            character(
                name = base,
                size = size,
                tags = tags,
                abilitySpecs = { },
                components = { component("Team", "team" to "orc") },
            )
            blueprint(name = "unit") { component("Position") }
            level(name = "arena", entities = {
                entity(name = "e", components = { component("Position") })
            })
            """.trimIndent(),
        )
        assertEquals(emptyList(), loops(diagnostics), diagnostics.toString())
        assertEquals(emptyList(), diagnostics.filter { it.severity == Severity.Error }, diagnostics.toString())
    }
}
