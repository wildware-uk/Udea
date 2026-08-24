package dev.wildware.udea.build.determinism

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The strictness that keeps the allowlist a reviewed artefact rather than a dumping ground
 * (spec 6's exit criterion for this phase).
 *
 * Each failure kind gets its own id, and that is asserted here rather than assumed: "the
 * allowlist is invalid" is a message that sends everyone to read the whole file, and
 * "ALLOW004: line 12 matched nothing, delete it" is a message somebody acts on.
 */
class AllowlistParserTest {

    @TempDir
    lateinit var tempDir: File

    private fun idsFor(text: String): List<String> = Allowlist.parse(text).problems.map { it.ruleId }

    @Test
    fun `an unknown rule id fails under ALLOW001`() {
        val problems = Allowlist.parse("DET099  java.lang.System#nanoTime  # nope\n").problems
        assertEquals(listOf(Allowlist.UNKNOWN_RULE), problems.map { it.ruleId })
        assertTrue(problems.single().message.contains("DET001"), "the message lists the known ids")
    }

    @Test
    fun `an entry with no reasoning fails under ALLOW002`() {
        assertEquals(listOf(Allowlist.NO_REASONING), idsFor("DET001  java.lang.System#nanoTime\n"))
    }

    @Test
    fun `an empty reasoning after the hash still fails under ALLOW002`() {
        assertEquals(listOf(Allowlist.NO_REASONING), idsFor("DET001  java.lang.System#nanoTime  #\n"))
    }

    @Test
    fun `a target that is not owner hash member fails under ALLOW003`() {
        assertEquals(listOf(Allowlist.MALFORMED_TARGET), idsFor("DET001  java.lang.System  # why\n"))
    }

    @Test
    fun `a three column entry fails under ALLOW003`() {
        assertEquals(
            listOf(Allowlist.MALFORMED_TARGET),
            idsFor("DET001  java.lang.System#nanoTime extra  # why\n"),
        )
    }

    @Test
    fun `an entry that matches nothing fails under ALLOW004`() {
        val compiled = FixtureCompiler.compile(
            tempDir,
            mapOf("sim/Clean.java" to "package sim;\npublic class Clean { }\n"),
        )
        val result = DeterminismScan.run(
            inputs = listOf(FixtureCompiler.scopeInput(compiled, packagePrefixes = listOf("sim"))),
            allowlist = Allowlist.parse("DET001  java.lang.System#nanoTime  # stale\n"),
            repoRoot = compiled.sourceDir,
        )
        assertEquals(listOf(Allowlist.UNUSED_ENTRY), result.problems.map { it.ruleId })
        assertTrue(result.failed, "a stale exception has to fail the build or it never leaves")
        assertTrue(result.problems.single().message.contains("line 1"))
    }

    @Test
    fun `a drifted version pin fails under ALLOW005 and names both versions`() {
        val allowlist = Allowlist.parse("@version fleks 2.14\n@version gdx 1.13.5\n")
        val problems = allowlist.versionProblems(mapOf("fleks" to "2.15", "gdx" to "1.13.5"))
        assertEquals(listOf(Allowlist.VERSION_DRIFT), problems.map { it.ruleId })
        val message = problems.single().message
        assertTrue(message.contains("2.14") && message.contains("2.15"), message)
        assertTrue(message.contains("determinism-audit.md"), message)
    }

    @Test
    fun `a resolved library with no pin at all fails under ALLOW005`() {
        val allowlist = Allowlist.parse("@version fleks 2.14\n")
        val problems = allowlist.versionProblems(mapOf("fleks" to "2.14", "gdx" to "1.13.5"))
        assertEquals(listOf(Allowlist.VERSION_DRIFT), problems.map { it.ruleId })
        assertTrue(problems.single().message.contains("gdx"))
    }

    @Test
    fun `matching pins produce no problems`() {
        val allowlist = Allowlist.parse("@version fleks 2.14\n@version gdx 1.13.5\n")
        assertEquals(
            emptyList(),
            allowlist.versionProblems(mapOf("fleks" to "2.14", "gdx" to "1.13.5")),
        )
    }

    @Test
    fun `an unknown directive fails under ALLOW006`() {
        assertEquals(listOf(Allowlist.UNKNOWN_DIRECTIVE), idsFor("@audited yesterday\n"))
    }

    @Test
    fun `comments and blank lines are skipped`() {
        val allowlist = Allowlist.parse(
            """
            # a comment
              # an indented comment

            @version fleks 2.14
            DET002  com.badlogic.gdx.math.MathUtils#*  # presentation-only helper
            """.trimIndent(),
        )
        assertEquals(emptyList(), allowlist.problems)
        assertEquals(1, allowlist.entries.size)
        assertEquals("*", allowlist.entries.single().member)
        assertEquals(listOf("fleks"), allowlist.versionPins.map { it.name })
    }

    @Test
    fun `the checked-in allowlist parses cleanly and pins every audited library`() {
        val file = repoRoot().resolve("determinism-allowlist.txt")
        assertTrue(file.isFile, "determinism-allowlist.txt is missing from the repository root")
        val allowlist = Allowlist.parse(file.readText())
        assertEquals(
            emptyList(),
            allowlist.problems.map { "${it.ruleId}: ${it.message}" },
            "the checked-in allowlist must satisfy its own parser",
        )
        assertEquals(
            UdeaVerifyDeterminismTask.PINNED_ALIASES.sorted(),
            allowlist.versionPins.map { it.name }.sorted(),
        )
    }

    @Test
    fun `every documented failure id is one the parser can actually produce`() {
        val documented = repoRoot().resolve("determinism-allowlist.txt").readText()
        Allowlist.IDS.forEach {
            assertTrue(documented.contains(it), "$it is a real failure id but the file's header does not explain it")
        }
    }

    /** `build-logic` runs with its own directory as the root, so the repository is one up. */
    private fun repoRoot(): File = File(System.getProperty("user.dir")).let {
        if (it.name == "build-logic") it.parentFile else it
    }
}
