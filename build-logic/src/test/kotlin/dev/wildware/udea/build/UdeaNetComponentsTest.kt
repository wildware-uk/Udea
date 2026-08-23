package dev.wildware.udea.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The registry that makes a `ComponentTypeId` mean one component across the whole build.
 *
 * Every rejection below is a rejection of an *ambiguous* id space rather than of an untidy
 * file, and the last test is the one that matters most: the file in the repository is read by
 * `udea-codegen/build.gradle.kts` at configuration time, so a rule that only ever sees
 * synthetic strings is a rule nobody has watched run against the real artefact.
 */
class UdeaNetComponentsTest {

    private fun failure(text: String): String {
        val parsed = UdeaNetComponents.parse(text)
        assertIs<UdeaNetComponents.Parse.Failure>(parsed, "expected a failure, got $parsed")
        return parsed.problem
    }

    @Test
    fun `comments and blank lines are not components`() {
        val parsed = UdeaNetComponents.parse(
            "# a header\ngas.Shield\n\nmoba.Health # trailing note\n",
        )
        assertEquals(
            UdeaNetComponents.Parse.Success(listOf("gas.Shield", "moba.Health")),
            parsed,
        )
    }

    @Test
    fun `an empty registry is refused rather than read as an empty id space`() {
        // The failure mode the whole file exists to remove: an empty list is indistinguishable
        // from "no project id space", which is per-module numbering applied silently.
        val problem = failure("# nothing but a comment\n")
        assertTrue("empty id space" in problem, problem)
    }

    @Test
    fun `an out-of-order registry is refused, because position is the id`() {
        val problem = failure("moba.Health\ngas.Shield\n")
        assertTrue("sorted" in problem, problem)
        assertTrue("gas.Shield" in problem && "moba.Health" in problem, problem)
    }

    @Test
    fun `a repeated name is refused, because one component cannot hold two ids`() {
        val problem = failure("gas.Shield\ngas.Shield\n")
        assertTrue("gas.Shield" in problem, problem)
        assertTrue("two component type ids" in problem, problem)
    }

    @Test
    fun `something that is not a fully-qualified name is refused`() {
        val problem = failure("Shield\n")
        assertTrue("fully-qualified" in problem, problem)
    }

    @Test
    fun `the option value is what the processor splits on`() {
        assertEquals(
            "gas.Shield,moba.Health",
            UdeaNetComponents.optionValue(listOf("gas.Shield", "moba.Health")),
        )
    }

    @Test
    fun `the registry in this repository is a legal id space`() {
        val file = File("..").canonicalFile.resolve(UdeaNetComponents.FILE_NAME)
        assertTrue(file.isFile, "no " + UdeaNetComponents.FILE_NAME + " at " + file.absolutePath)

        val parsed = UdeaNetComponents.parse(file.readText())
        assertIs<UdeaNetComponents.Parse.Success>(parsed, "the checked-in registry: $parsed")
        assertTrue(
            parsed.components.size >= 3,
            "the Phase 0 exit needs a Replicator for at least three components, so a registry " +
                "of fewer than three is a registry that is not carrying the build: $parsed",
        )
    }
}
