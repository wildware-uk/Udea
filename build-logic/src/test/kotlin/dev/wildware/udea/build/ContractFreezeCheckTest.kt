package dev.wildware.udea.build

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `udeaVerifyContracts` attached to a real build, one deliberately broken freeze per rule id.
 *
 * [ContractFreezeTest] proves the rules. This proves the rules are actually wired to `check`,
 * that the failure reaches a developer with the sanctioned route out in it, and that the route
 * works — which is the half a unit test cannot speak to, and the half issue #174 is about: the
 * repository already had the *statement* that the contracts are frozen and nothing that acted
 * on it.
 *
 * The fixture's contracts are prose stand-ins, not copies of the real ones. What is being
 * tested is the gate, and a gate that only fires on the three documents that happen to be in
 * `docs/contracts/` today would stop working the day a fourth arrives.
 */
class ContractFreezeCheckTest {

    private val gate = "udea.contract-freeze"

    private val rootBuildScript = """
        plugins { id("$gate") }
    """.trimIndent()

    @Test
    fun `an edited contract fails check, naming the file and the route out`(@TempDir root: File) {
        val fixture = frozenFixture(root)

        contract(root, "replicator.md").appendText("one more byte\n")
        val result = fixture.buildAndFail("check", rootBuildScript = rootBuildScript)

        assertEquals(TaskOutcome.FAILED, result.task(":${ContractFreeze.VERIFY_TASK}")?.outcome, result.output)
        assertTrue(ContractFreeze.CONTRACT_CHANGED.value in result.output, result.output)
        assertTrue("docs/contracts/replicator.md" in result.output, result.output)
        assertTrue("Frozen means frozen" in result.output, result.output)
        assertTrue("Do not change it and carry on" in result.output, result.output)
        assertTrue(ContractFreeze.WRITE_TASK in result.output, result.output)
    }

    /**
     * The other half of the acceptance, and the half that decides whether the gate survives.
     *
     * A gate whose message does not tell the reader how to legitimately change the thing is a
     * gate somebody deletes the first time they have an agreed change to land.
     */
    @Test
    fun `the deliberate route makes the same tree green again`(@TempDir root: File) {
        val fixture = frozenFixture(root)
        contract(root, "replicator.md").appendText("an agreed change\n")
        fixture.buildAndFail("check", rootBuildScript = rootBuildScript)

        fixture.build(ContractFreeze.WRITE_TASK, rootBuildScript = rootBuildScript)
        val result = fixture.build("check", rootBuildScript = rootBuildScript)

        assertEquals(TaskOutcome.SUCCESS, result.task(":${ContractFreeze.VERIFY_TASK}")?.outcome, result.output)
        assertTrue("an agreed change" in contract(root, "replicator.md").readText())
    }

    @Test
    fun `a contract added without being frozen fails`(@TempDir root: File) {
        val fixture = frozenFixture(root)

        contract(root, "relevancy.md").writeText("# A fourth contract nobody agreed to freeze\n")
        val result = fixture.buildAndFail("check", rootBuildScript = rootBuildScript)

        assertTrue(ContractFreeze.CONTRACT_ADDED.value in result.output, result.output)
        assertTrue("docs/contracts/relevancy.md" in result.output, result.output)
    }

    @Test
    fun `a contract deleted from the directory fails`(@TempDir root: File) {
        val fixture = frozenFixture(root)

        assertTrue(contract(root, "asset-index.md").delete())
        val result = fixture.buildAndFail("check", rootBuildScript = rootBuildScript)

        assertTrue(ContractFreeze.CONTRACT_REMOVED.value in result.output, result.output)
        assertTrue("docs/contracts/asset-index.md" in result.output, result.output)
    }

    /**
     * A rename changes no contract's content, so a gate comparing only the digests it
     * recognised would see nothing at all.
     */
    @Test
    fun `renaming a contract fails from both ends`(@TempDir root: File) {
        val fixture = frozenFixture(root)

        assertTrue(contract(root, "replicator.md").renameTo(contract(root, "replication.md")))
        val result = fixture.buildAndFail("check", rootBuildScript = rootBuildScript)

        assertTrue(ContractFreeze.CONTRACT_ADDED.value in result.output, result.output)
        assertTrue("docs/contracts/replication.md" in result.output, result.output)
        assertTrue(ContractFreeze.CONTRACT_REMOVED.value in result.output, result.output)
        assertTrue("docs/contracts/replicator.md" in result.output, result.output)
    }

    /**
     * Deleting the lock is editing a contract with an extra step, and has to be as loud.
     *
     * The failure mode this rules out is the quiet one: a gate that treats "no baseline" as
     * "nothing to compare" and passes, so removing one file switches the freeze off for good
     * while the build stays green.
     */
    @Test
    fun `deleting the lock fails rather than freezing nothing`(@TempDir root: File) {
        val fixture = frozenFixture(root)

        assertTrue(File(root, ContractFreeze.LOCK_PATH).delete())
        val result = fixture.buildAndFail("check", rootBuildScript = rootBuildScript)

        assertEquals(TaskOutcome.FAILED, result.task(":${ContractFreeze.VERIFY_TASK}")?.outcome, result.output)
        assertTrue(ContractFreeze.CONTRACT_ADDED.value in result.output, result.output)
        assertTrue("docs/contracts/replicator.md" in result.output, result.output)
    }

    /**
     * The same case through a real build, because this one is about Gradle's input validation
     * rather than about the rule: a missing directory has to reach the task as three vanished
     * contracts, not as a build that cannot configure.
     */
    @Test
    fun `deleting the whole frozen directory fails, rather than failing to configure`(@TempDir root: File) {
        val fixture = frozenFixture(root)

        assertTrue(File(root, ContractFreeze.DIRECTORY).deleteRecursively())
        val result = fixture.buildAndFail("check", rootBuildScript = rootBuildScript)

        assertEquals(TaskOutcome.FAILED, result.task(":${ContractFreeze.VERIFY_TASK}")?.outcome, result.output)
        assertTrue(ContractFreeze.CONTRACT_REMOVED.value in result.output, result.output)
        assertTrue("docs/contracts/replicator.md" in result.output, result.output)
        assertTrue("docs/contracts/agent-tools.md" in result.output, result.output)
        assertTrue("docs/contracts/asset-index.md" in result.output, result.output)
    }

    @Test
    fun `an untouched tree passes, and check is what runs the gate`(@TempDir root: File) {
        val result = frozenFixture(root).build("check", rootBuildScript = rootBuildScript)

        // `check` and not only a task somebody remembers: a fresh clone has to catch an edit
        // without being told the task name.
        assertEquals(TaskOutcome.SUCCESS, result.task(":${ContractFreeze.VERIFY_TASK}")?.outcome, result.output)
        assertTrue(File(root, "build/reports/udea/contract-freeze.txt").isFile, result.output)
    }

    /**
     * A fixture with three contracts, frozen by [ContractFreeze.WRITE_TASK] itself.
     *
     * Written by the real task rather than by the test, so every case below starts from a lock
     * this build produced. A hand-rolled baseline would let the test agree with itself about a
     * format the task does not write.
     */
    private fun frozenFixture(root: File): GradleFixture {
        listOf("replicator.md", "agent-tools.md", "asset-index.md").forEach {
            contract(root, it).writeText("# $it\n\nA stand-in for a frozen agreement.\n")
        }
        val fixture = GradleFixture(root)
        fixture.build(ContractFreeze.WRITE_TASK, rootBuildScript = rootBuildScript)
        assertTrue(File(root, ContractFreeze.LOCK_PATH).isFile, "the write task produced no lock")
        return fixture
    }

    private fun contract(root: File, name: String): File =
        File(root, "${ContractFreeze.DIRECTORY}/$name").also { it.parentFile.mkdirs() }
}
