package dev.wildware.udea.build

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `AGENTS.md` describes the tree it is a brief for.
 *
 * Three of these run against the **real** `AGENTS.md` and `settings.gradle.kts`, so the file
 * committed alongside this test is the thing being asserted, not a fixture that resembles it.
 * The rest edit a copy, which is what issue #138 asks for: proof the gate can fail.
 */
class AgentsMdTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "AGENTS.md").isFile }

    private val agentsMd = File(repoRoot, "AGENTS.md").readText()
    private val settings = File(repoRoot, "settings.gradle.kts").readText()

    @Test
    fun `the committed AGENTS_md matches the committed settings script`() {
        assertEquals(emptyList(), AgentsMd.findings(agentsMd, settings))
    }

    @Test
    fun `every module in settings gradle kts has a row`() {
        val declared = AgentsMd.declaredModules(settings)

        assertTrue(declared.contains("udea-core"), declared.toString())
        assertTrue(declared.contains("moba"), declared.toString())
        assertEquals(declared.sorted(), AgentsMd.documentedModules(agentsMd).sorted())
    }

    @Test
    fun `the deleted D6 modules are not documented as if they still existed`() {
        val documented = AgentsMd.documentedModules(agentsMd)

        listOf("level-editor", "idea-plugin", "compose-ui").forEach {
            assertTrue(it !in documented, "AGENTS.md still lists the deleted module '$it'")
        }
    }

    @Test
    fun `a module added to settings without a row fails`() {
        val edited = settings + "\ninclude(\"udea-physics\")\n"

        val finding = AgentsMd.findings(agentsMd, edited).single()

        assertEquals(AgentsMd.MODULE_TABLE_DRIFT, finding.rule)
        assertEquals("AGENTS.md", finding.path)
        assertTrue(finding.message.contains("udea-physics"), finding.message)
    }

    @Test
    fun `a row for a module that has been deleted fails`() {
        val edited = settings.replace("include(\"udea-gas\")\n", "")

        val finding = AgentsMd.findings(agentsMd, edited).single()

        assertEquals(AgentsMd.MODULE_TABLE_DRIFT, finding.rule)
        assertTrue(finding.message.contains("udea-gas"), finding.message)
        assertTrue(finding.message.contains("reads as current"), finding.message)
    }

    @Test
    fun `dropping a spec section 5 contract fails, naming it`() {
        val edited = agentsMd.replace("Authority vocabulary", "Ownership words")

        val finding = AgentsMd.findings(edited, settings).single()

        assertEquals(AgentsMd.MISSING_CONTRACT, finding.rule)
        assertTrue(finding.message.contains("Authority vocabulary"), finding.message)
    }

    @Test
    fun `all nine section 5 contracts are checked for, not eight`() {
        assertEquals(9, AgentsMd.CONTRACTS.size)
        assertTrue(AgentsMd.CONTRACTS.containsAll(listOf("Serialization", "Randomness", "Time")))
    }

    @Test
    fun `tables outside the module section are not mistaken for modules`() {
        val documented = AgentsMd.documentedModules(agentsMd)

        // AGENTS.md's render-mode and bridge-endpoint tables also open with a backticked cell.
        listOf("Headless", "Offscreen", "Windowed", "/health", "/tools").forEach {
            assertTrue(it !in documented, "'$it' was read out of the wrong table")
        }
    }

    @Test
    fun `an AGENTS_md with no module section is a hard failure`() {
        assertFailsWith<IllegalArgumentException> {
            AgentsMd.documentedModules(agentsMd.replace(AgentsMd.MODULE_SECTION, "## Bits and pieces"))
        }
    }
}
