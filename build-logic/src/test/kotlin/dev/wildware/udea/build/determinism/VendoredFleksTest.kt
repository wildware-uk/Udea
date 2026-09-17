package dev.wildware.udea.build.determinism

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The audited version of vendored Fleks names its source as well as its release (issue #215).
 *
 * `determinism-audit.md` is a set of claims about particular Fleks source. From Maven, only a
 * release bump could change that source, so pinning the release was enough. Vendored, anybody can
 * edit the files, so the version the pin compares against carries a digest of them, and each way
 * the source can change has to move it.
 */
class VendoredFleksTest {

    @TempDir
    lateinit var tempDir: File

    private fun tree(name: String, files: Map<String, String>): File {
        val root = tempDir.resolve(name)
        files.forEach { (path, text) -> root.resolve(path).apply { parentFile.mkdirs(); writeText(text) } }
        return root
    }

    private val upstream = mapOf(
        "kotlin/com/github/quillraven/fleks/world.kt" to "class World\n",
        "kotlin/com/github/quillraven/fleks/collection/bag.kt" to "class Bag\n",
    )

    @Test
    fun `the audited version is the release plus a SHA-256 of the source`() {
        val version = VendoredFleks.auditedVersion("2.14", tree("a", upstream))
        assertTrue(Regex("""2\.14\+sha256:[0-9a-f]{64}""").matches(version), version)
    }

    @Test
    fun `the same source in two places has the same digest`() {
        assertEquals(VendoredFleks.digest(tree("a", upstream)), VendoredFleks.digest(tree("b", upstream)))
    }

    @Test
    fun `an edit to one file moves the digest`() {
        val edited = upstream + ("kotlin/com/github/quillraven/fleks/world.kt" to "class World { }\n")
        assertNotEquals(VendoredFleks.digest(tree("a", upstream)), VendoredFleks.digest(tree("b", edited)))
    }

    @Test
    fun `a renamed file moves the digest`() {
        val renamed = mapOf(
            "kotlin/com/github/quillraven/fleks/world.kt" to "class World\n",
            "kotlin/com/github/quillraven/fleks/collection/bags.kt" to "class Bag\n",
        )
        assertNotEquals(VendoredFleks.digest(tree("a", upstream)), VendoredFleks.digest(tree("b", renamed)))
    }

    @Test
    fun `an added file moves the digest`() {
        val added = upstream + ("kotlin/com/github/quillraven/fleks/extra.kt" to "")
        assertNotEquals(VendoredFleks.digest(tree("a", upstream)), VendoredFleks.digest(tree("b", added)))
    }

    @Test
    fun `line endings do not move the digest`() {
        // A Windows checkout with core.autocrlf=true: a pin that failed there on a perfect tree is
        // a pin somebody switches off (issue #176).
        val crlf = upstream.mapValues { (_, text) -> text.replace("\n", "\r\n") }
        assertEquals(VendoredFleks.digest(tree("a", upstream)), VendoredFleks.digest(tree("b", crlf)))
    }

    @Test
    fun `a directory with no source is refused rather than digested`() {
        val empty = tempDir.resolve("empty").apply { mkdirs() }
        assertFailsWith<IllegalStateException> { VendoredFleks.digest(empty) }
        assertFailsWith<IllegalStateException> { VendoredFleks.digest(tempDir.resolve("absent")) }
    }
}
