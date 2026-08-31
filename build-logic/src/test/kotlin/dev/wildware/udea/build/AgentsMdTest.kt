package dev.wildware.udea.build

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `AGENTS.md` describes the tree it is a brief for.
 *
 * Some of these read the **real** `AGENTS.md` and `settings.gradle.kts`, so the file committed
 * alongside this test is the thing being asserted, not a fixture that resembles it. The rest
 * edit a copy of it, which is what issue #138 asks for: proof the gate can fail.
 *
 * Either way the input is the committed file, so what a checkout did to its line endings is
 * part of the input. `settingsWithout` and `asCrlf` are where that is dealt with; issue #176
 * is why.
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
        val edited = settingsWithout("udea-gas")

        val finding = AgentsMd.findings(agentsMd, edited).single()

        assertEquals(AgentsMd.MODULE_TABLE_DRIFT, finding.rule)
        assertTrue(finding.message.contains("udea-gas"), finding.message)
        assertTrue(finding.message.contains("reads as current"), finding.message)
    }

    /**
     * Issue #176: the same drift, on a checkout that translated the line endings.
     *
     * This repository has no root `.gitattributes` and Git for Windows checks out with
     * `core.autocrlf=true`, so on that platform `settings.gradle.kts` and `AGENTS.md` arrive
     * CRLF. `AgentsMd` itself copes - its regexes are anchored on `^`, and `\s` matches a
     * carriage return - but the mutation above was built from a literal `\n`. It removed
     * nothing, `findings` came back empty and `single()` threw `NoSuchElementException`.
     *
     * That is the thing worth fixing rather than the red itself. `udeaVerifyAgentsMd` is what
     * makes `CLAUDE.md`'s "a stale `AGENTS.md` is a correctness bug" a checkable claim, and on
     * Windows the same red arrived whether the document was stale or perfect. A gate that
     * cannot tell its own subject apart is not a gate.
     *
     * No Windows checkout is needed to hold that: the translation is what a checkout does to
     * the bytes, and doing it here reaches the same code with the same input.
     */
    @Test
    fun `a deleted module is still caught when the checkout translated the line endings`() {
        val edited = settingsWithout("udea-gas", settings.asCrlf())

        val finding = AgentsMd.findings(agentsMd.asCrlf(), edited).single()

        assertEquals(AgentsMd.MODULE_TABLE_DRIFT, finding.rule)
        assertTrue(finding.message.contains("udea-gas"), finding.message)
        assertTrue(finding.message.contains("reads as current"), finding.message)
    }

    /**
     * The control for the test above: an *untranslated* settings script still reaches the same
     * finding, so the CRLF case is not passing because the mutation stopped mattering.
     */
    @Test
    fun `the module table reads the same whatever the checkout did to the line endings`() {
        assertEquals(AgentsMd.declaredModules(settings), AgentsMd.declaredModules(settings.asCrlf()))
        assertEquals(AgentsMd.documentedModules(agentsMd), AgentsMd.documentedModules(agentsMd.asCrlf()))
        assertEquals(emptyList(), AgentsMd.findings(agentsMd.asCrlf(), settings.asCrlf()))
    }

    /**
     * [settings] with one module's `include(...)` line removed, whatever line ending it carries.
     *
     * The `assertTrue` is part of the fence rather than defensive noise. A removal that removed
     * nothing hands the caller an unedited script, `findings` returns nothing, and the caller
     * fails on an empty list with no hint of why - which is precisely the failure issue #176
     * spent a CI leg on. Failing here names the cause instead.
     */
    private fun settingsWithout(module: String, from: String = settings): String {
        val line = Regex("""(?m)^[ \t]*include\("${Regex.escape(module)}"\)[ \t]*\r?\n""")
        val edited = line.replace(from, "")
        assertTrue(edited != from, "the settings script has no `include(\"$module\")` line to remove")
        return edited
    }

    /** The bytes a `core.autocrlf=true` checkout would have written for LF-committed text. */
    private fun String.asCrlf(): String = replace("\r\n", "\n").replace("\n", "\r\n")

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
