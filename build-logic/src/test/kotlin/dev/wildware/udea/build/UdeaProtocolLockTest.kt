package dev.wildware.udea.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `net-protocol.lock` drift rule, and the seam that keeps its instructions true.
 *
 * `udeaCheckProtocolLock` is a `doLast` block, which nothing here can execute, so the decision
 * it makes lives in [UdeaProtocolLock.violation] where it has tests that can fail — including
 * the branch that matters most, which is that a check with nothing to compare must fail rather
 * than pass vacuously.
 *
 * The last two tests cross a module boundary on purpose. `udea-codegen` writes the task names
 * into the header of every lock it generates, and it cannot see `build-logic`, so the two ends
 * are literals in two files. That is exactly the arrangement that produced the original
 * defect - a header telling readers to run `udeaWriteProtocolLock` while no task by that name
 * had ever been registered - so the file that generates the instruction is read here and held
 * against the constants naming the tasks actually registered.
 */
class UdeaProtocolLockTest {

    private val repoRoot = File("..").canonicalFile

    private val lock = listOf(
        "# a header",
        "lockFormat 1",
        "protoHash 0x9ec2",
        "component 0 moba.Health",
    ).joinToString("\n")

    @Test
    fun `a reviewed lock equal to the generated protocol is not a violation`() {
        assertNull(UdeaProtocolLock.violation(":udea-codegen", generated = lock, reviewed = lock))
    }

    @Test
    fun `line endings are not a wire change`() {
        assertNull(
            UdeaProtocolLock.violation(
                ":udea-codegen",
                generated = lock,
                reviewed = lock.replace("\n", "\r\n"),
            ),
        )
    }

    @Test
    fun `a check with nothing generated fails rather than passing forever`() {
        // The silent failure a drift check has: point it at the wrong generated-resources path
        // and it compares nothing to nothing on every build for the rest of the project.
        val problem = UdeaProtocolLock.violation(":udea-codegen", generated = null, reviewed = lock)
        assertTrue(problem != null && "passes forever" in problem, "$problem")
    }

    @Test
    fun `a module with no reviewed lock is told to write one`() {
        val problem = UdeaProtocolLock.violation(":moba", generated = lock, reviewed = null)
        assertTrue(problem != null && UdeaProtocolLock.WRITE_TASK in problem, "$problem")
        assertTrue(problem.contains(":moba"), problem)
    }

    @Test
    fun `drift is reported as the first differing line, both sides shown`() {
        val drifted = lock.replace("component 0 moba.Health", "component 0 moba.Vitals")
        val problem =
            UdeaProtocolLock.violation(":udea-codegen", generated = drifted, reviewed = lock)

        assertTrue(problem != null && "first difference at line 4" in problem, "$problem")
        assertTrue(problem.contains("checked in: component 0 moba.Health"), problem)
        assertTrue(problem.contains("generated:  component 0 moba.Vitals"), problem)
        assertTrue(problem.contains(UdeaProtocolLock.WRITE_TASK), problem)
    }

    @Test
    fun `the generated lock header names the tasks this build actually registers`() {
        val emitter = repoRoot.resolve(
            "udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/protocol/ProtocolLock.kt",
        )
        assertTrue(emitter.isFile, "no emitter at " + emitter.absolutePath)
        val text = emitter.readText()

        assertTrue(
            "const val WRITE_TASK: String = " + quoted(UdeaProtocolLock.WRITE_TASK) in text,
            "the generated header must name the task " + UdeaProtocolLock.WRITE_TASK,
        )
        assertTrue(
            "const val CHECK_TASK: String = " + quoted(UdeaProtocolLock.CHECK_TASK) in text,
            "the generated header must name the task " + UdeaProtocolLock.CHECK_TASK,
        )
    }

    @Test
    fun `the reviewed lock in this repository is the one the check compares`() {
        // A registered task nobody has watched run against the real files is not enforcement.
        val reviewed = repoRoot.resolve("udea-codegen/" + UdeaProtocolLock.FILE_NAME)
        assertTrue(reviewed.isFile, "no reviewed lock at " + reviewed.absolutePath)
        val text = reviewed.readText()

        assertTrue(UdeaProtocolLock.CHECK_TASK in text, "the header must name the check task")
        assertEquals(
            null,
            UdeaProtocolLock.violation(":udea-codegen", generated = text, reviewed = text),
            "the rule must accept the file it guards",
        )
    }

    private fun quoted(value: String): String = "\"" + value + "\""
}
