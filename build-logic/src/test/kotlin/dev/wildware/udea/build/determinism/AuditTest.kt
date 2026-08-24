package dev.wildware.udea.build.determinism

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `determinism-audit.md`, parsed and held to its own claims.
 *
 * Issue #151 is the one deliberately manual issue in the epic, and its value is the reasoning
 * column rather than the file - "a generated list with empty justifications would satisfy the
 * letter of section 7 and none of its intent". A document nobody checks decays into exactly
 * that, so the three properties that make it worth reading are asserted here: every row has a
 * verdict, every row has reasoning, and every `banned` verdict is backed by a rule that exists
 * or a replacement that is written down.
 */
class AuditTest {

    private val verdicts = setOf("deterministic", "deterministic-if-used-thus", "banned")

    /** Words that turn a row into a placeholder. Any of them fails the build. */
    private val weasel = listOf("assumed fine", "assumed ok", "probably", "tbd", "todo", "n/a")

    /**
     * The members issue #151 requires the audit to cover, at minimum. Matched as substrings of
     * the member column, so a row may name several members at once.
     */
    private val required = listOf(
        "recycling",
        "Family.getEntities",
        "Family.forEach",
        "Bag.get",
        "Vector2.nor",
        "Vector2.len",
        "Vector2.angleDeg",
        "MathUtils.sin",
        "MathUtils.random",
        "ObjectMap",
        "IntMap",
        "Pool.obtain",
    )

    private data class Row(val member: String, val verdict: String, val rule: String, val reasoning: String)

    @Test
    fun `every audited row carries a verdict, a rule-or-replacement and reasoning`() {
        val rows = rows()
        assertTrue(rows.size >= 25, "the audit has only ${rows.size} rows; that is not a used-surface audit")
        rows.forEach { row ->
            assertTrue(
                row.verdict in verdicts,
                "'${row.member}' has verdict '${row.verdict}', which is not one of $verdicts",
            )
            assertTrue(row.reasoning.length >= 40, "'${row.member}' has no real reasoning: '${row.reasoning}'")
            assertTrue(row.rule.isNotBlank(), "'${row.member}' has an empty rule-or-replacement cell")
            weasel.forEach { word ->
                assertTrue(
                    !row.reasoning.lowercase().contains(word),
                    "'${row.member}' says '$word'. A placeholder verdict is worse than none: it " +
                        "reads as reviewed.",
                )
            }
        }
    }

    @Test
    fun `every banned verdict names a real rule id or a documented replacement`() {
        rows().filter { it.verdict == "banned" }.forEach { row ->
            val named = DeterminismRules.IDS.filter { row.rule.contains(it) }
            val replacement = row.rule.startsWith("replacement:")
            assertTrue(
                named.isNotEmpty() || replacement,
                "'${row.member}' is banned but its rule column says '${row.rule}'. A ban with " +
                    "neither a rule that enforces it nor a replacement to reach for is advice, " +
                    "not a decision.",
            )
            if (replacement) {
                assertTrue(
                    row.rule.length > "replacement:".length + 10,
                    "'${row.member}' names a replacement without saying what it is",
                )
            }
        }
    }

    @Test
    fun `the audit covers every member issue 151 requires`() {
        val members = rows().joinToString(" ") { it.member }
        required.forEach { member ->
            assertTrue(members.contains(member), "the audit has no row covering '$member'")
        }
    }

    @Test
    fun `the audit is stamped with the versions the allowlist pins`() {
        val audit = auditFile().readText()
        val pins = Allowlist.parse(allowlistFile().readText()).versionPins
        assertTrue(pins.isNotEmpty(), "the allowlist pins no versions")
        pins.forEach { pin ->
            assertTrue(
                audit.contains(pin.version),
                "the audit does not say it was performed against ${pin.name} ${pin.version}, " +
                    "which is the version determinism-allowlist.txt pins",
            )
        }
    }

    @Test
    fun `the audit says plainly what the scanner cannot see`() {
        val audit = auditFile().readText()
        listOf(
            "structurally cannot see",
            "is **not** evidence that the simulation is",
            "replay-equality",
            "WorldHasher",
        ).forEach {
            assertTrue(audit.contains(it), "the audit does not contain '$it'")
        }
    }

    @Test
    fun `the audit names the predicted-package gap rather than hiding it`() {
        val audit = auditFile().readText()
        assertTrue(audit.contains("PREDICTED_PACKAGES"), audit.takeLast(400))
        assertTrue(
            audit.contains("no `@Predicted` annotation"),
            "DET005 keys on a package list because no annotation exists; that has to be written down",
        )
    }

    /**
     * Rows of both markdown tables that have four columns and a verdict-looking third cell.
     *
     * Deliberately not a strict parse of the whole document: the "blind spots" table in section
     * 1 has three columns and is prose, and asserting a shape on it would make editing the prose
     * a build failure for no gain.
     */
    private fun rows(): List<Row> = auditFile().readLines()
        .map { it.trim() }
        .filter { it.startsWith("|") && it.endsWith("|") }
        .map { it.trim('|').split("|").map(String::trim) }
        .filter { it.size == 4 && it[1] in verdicts }
        .map { Row(member = it[0], verdict = it[1], rule = it[2], reasoning = it[3]) }

    private fun auditFile(): File = repoRoot().resolve(UdeaVerifyDeterminismTask.AUDIT_FILE).also {
        assertTrue(it.isFile, "${UdeaVerifyDeterminismTask.AUDIT_FILE} is missing from the repository root")
    }

    private fun allowlistFile(): File = repoRoot().resolve(UdeaVerifyDeterminismTask.ALLOWLIST_FILE)

    private fun repoRoot(): File = File(System.getProperty("user.dir")).let {
        if (it.name == "build-logic") it.parentFile else it
    }

    @Test
    fun `the declared simulation scopes each say why they are simulation`() {
        DeterminismRules.SIMULATION_SCOPES.forEach { scope ->
            assertTrue(
                scope.why.length >= 60,
                "${scope.project} is declared simulation with the reason '${scope.why}'. " +
                    "Membership is never inferred from a module name (issue #150), so it has to " +
                    "be argued for.",
            )
        }
        assertEquals(
            DeterminismRules.SIMULATION_SCOPES.map { it.project }.distinct().size,
            DeterminismRules.SIMULATION_SCOPES.size,
            "a project is declared twice; two scopes over one module would scan it twice",
        )
    }

    @Test
    fun `no presentation module is declared simulation`() {
        val presentation = setOf(":udea-render", ":udea-audio", ":udea-agent-host", ":udea-agent")
        DeterminismRules.SIMULATION_SCOPES.forEach {
            assertTrue(
                it.project !in presentation,
                "${it.project} is presentation: spec 3.3 puts it outside world.update by " +
                    "construction and spec 5 gives it PresentationRandom, so a wall-clock read " +
                    "there is correct and a gate that failed on it is a gate people switch off",
            )
        }
        assertTrue(
            DeterminismRules.SIMULATION_SCOPES.none { it.project == ":udea-assets-compiler" },
            "udea-assets-compiler calls currentTimeMillis from a build-time script host, which " +
                "is legitimate; issue #150 names it as the reason scopes are declared and never " +
                "inferred",
        )
    }
}
