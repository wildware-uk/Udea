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

    // --- merge: what `udeaWriteNetComponents` writes (issue #274) -----------------------------

    private fun rewrite(existing: String?, discovered: List<String>): UdeaNetComponents.Merge.Rewrite {
        val merged = UdeaNetComponents.merge(existing, discovered)
        assertIs<UdeaNetComponents.Merge.Rewrite>(merged, "expected a rewrite, got $merged")
        return merged
    }

    private fun mergeFailure(existing: String?, discovered: List<String>): String {
        val merged = UdeaNetComponents.merge(existing, discovered)
        assertIs<UdeaNetComponents.Merge.Failure>(merged, "expected a failure, got $merged")
        return merged.problem
    }

    @Test
    fun `a build with no registry yet gets one, with a header that says what it is`() {
        val written = rewrite(null, listOf("moba.Health", "gas.Shield"))

        assertEquals(listOf("gas.Shield", "moba.Health"), written.added)
        assertTrue(written.text.startsWith("# udea " + UdeaNetComponents.FILE_NAME), written.text)
        assertTrue(written.text.endsWith("\ngas.Shield\nmoba.Health\n"), written.text)
        // The whole point of writing it: what comes out has to be a legal id space going in.
        assertIs<UdeaNetComponents.Parse.Success>(UdeaNetComponents.parse(written.text))
    }

    @Test
    fun `a new name is inserted in sorted position, not appended`() {
        // Appending would be the easy thing and the wrong one: position IS the id, so a name
        // that sorts first has to take id 0 and move everything after it.
        val written = rewrite("# header\ngas.Shield\nmoba.Health\n", listOf("aaa.First"))

        assertEquals(listOf("aaa.First"), written.added)
        assertEquals("# header\naaa.First\ngas.Shield\nmoba.Health\n", written.text)
    }

    @Test
    fun `a name the build did not see this time is kept`() {
        // The engine's own lock says names may be listed before the component exists, and a
        // module that failed to compile reports nothing at all. Dropping a name here would
        // renumber the wire because of a build failure.
        val written = rewrite("gas.Shield\nmoba.Health\n", listOf("gas.Shield"))

        assertEquals(emptyList(), written.added)
        assertEquals("gas.Shield\nmoba.Health\n", written.text)
    }

    @Test
    fun `a trailing note travels with the name it is on`() {
        val written = rewrite("zzz.Last # written by hand\n", listOf("aaa.First"))

        assertEquals("aaa.First\nzzz.Last # written by hand\n", written.text)
    }

    @Test
    fun `an unsorted registry is sorted by the rewrite`() {
        // `parse` refuses this file; the write task is how a developer gets out of that state.
        assertIs<UdeaNetComponents.Parse.Failure>(UdeaNetComponents.parse("moba.Health\ngas.Shield\n"))

        val written = rewrite("moba.Health\ngas.Shield\n", emptyList())

        assertEquals("gas.Shield\nmoba.Health\n", written.text)
    }

    @Test
    fun `a comment between two names is refused rather than moved`() {
        val problem = mergeFailure("gas.Shield\n# why moba.Health is here\nmoba.Health\n", emptyList())

        assertTrue("line 2" in problem, problem)
        assertTrue("no position that survives" in problem, problem)
    }

    @Test
    fun `a repeated name in the reviewed file is refused, because a rewrite cannot pick one`() {
        val problem = mergeFailure("gas.Shield # one\ngas.Shield # the other\n", emptyList())

        assertTrue("gas.Shield" in problem, problem)
        assertTrue("two component type ids" in problem, problem)
    }

    @Test
    fun `a name the processor reported that is not a fully-qualified name is refused`() {
        val problem = mergeFailure(null, listOf("Shield"))

        assertTrue("fully-qualified" in problem, problem)
        assertTrue("processor's manifest" in problem, problem)
    }

    @Test
    fun `a file that is only comments keeps them and gains its first names`() {
        val written = rewrite("# nothing yet\n", listOf("gas.Shield"))

        assertEquals("# nothing yet\ngas.Shield\n", written.text)
        assertEquals(listOf("gas.Shield"), written.added)
    }

    @Test
    fun `a build that compiles no component is refused rather than given an empty registry`() {
        // Every module reporting an empty manifest is a legal build; writing the file for it is
        // not. An empty id space would be the per-module numbering this file exists to replace,
        // and the very next build would fail on a file this task had just produced.
        val problem = mergeFailure(null, emptyList())

        assertTrue("empty id space" in problem, problem)
    }

    @Test
    fun `the manifest name here is the one the processor writes`() {
        // Two constants in two builds that cannot see each other: `udeaWriteNetComponents` globs
        // for what `NetComponentsManifest` writes, and if they drift the task finds nothing and
        // says the build reported no components at all - true, and about the wrong thing.
        val source = File("..").canonicalFile
            .resolve("udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/protocol/NetComponentsManifest.kt")
        assertTrue(source.isFile, "no NetComponentsManifest.kt at ${source.absolutePath}")

        val declared = Regex("""const val FILE_NAME: String = "([^"]+)"""")
            .find(source.readText())
            ?.groupValues
            ?.get(1)

        assertEquals(UdeaNetComponents.MANIFEST_NAME, declared, source.absolutePath)
    }

    @Test
    fun `rewriting this repository's own registry changes nothing in it`() {
        // The strongest control this file has, and the one that says the discovery is complete:
        // the names the build reports are exactly the names a human reviewed. A rewrite that
        // moved a line would mean the two disagree about the id space.
        val file = File("..").canonicalFile.resolve(UdeaNetComponents.FILE_NAME)
        val text = file.readText()
        val names = (UdeaNetComponents.parse(text) as UdeaNetComponents.Parse.Success).components

        val written = rewrite(text, names)

        assertEquals(emptyList(), written.added)
        assertEquals(text.replace("\r\n", "\n"), written.text)
    }
}
