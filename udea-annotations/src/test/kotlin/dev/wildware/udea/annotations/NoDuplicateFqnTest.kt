package dev.wildware.udea.annotations

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression guard for the defect this module exists to remove.
 *
 * `dev.wildware.udea.network.UdeaNetworked` is declared twice in the old tree - in
 * `common/src/main/kotlin/dev/wildware/udea/network/packets.kt` and in
 * `gradle-plugin/src/main/kotlin/dev/wildware/udea/network/annotations.kt` - and
 * `common/build.gradle.kts` puts both on one compile classpath, so which one the
 * reflective scan binds against depends on classpath ordering. `udea-annotations` is the
 * single home for the rewrite's annotations; this test fails if any FQN it declares also
 * appears in either old tree, which is what would let the duplicate come back.
 *
 * The old trees are deleted at the Phase 6 exit (spec 6); when they are gone this test
 * still asserts that this module declares the vocabulary, and simply has nothing to
 * collide with.
 */
class NoDuplicateFqnTest {

    @Test
    fun `no FQN declared here is also declared in the old common or gradle-plugin trees`() {
        val ours = typeFqnsIn(File("src/main/kotlin"))
        assertTrue(
            ours.isNotEmpty(),
            "scanned no declarations in udea-annotations - the scanner is broken, not the tree",
        )

        for (oldTree in listOf(File("../common/src"), File("../gradle-plugin/src"))) {
            if (!oldTree.isDirectory) continue
            val theirs = typeFqnsIn(oldTree)
            assertTrue(
                theirs.isNotEmpty(),
                "scanned no declarations in ${oldTree.path} - the scanner is broken, not the tree",
            )
            assertEquals(
                emptySet(),
                ours intersect theirs,
                "udea-annotations must be the single home for these FQNs, but ${oldTree.path} also declares them",
            )
        }
    }

    @Test
    fun `this module does not reuse the FQN of either old UdeaNetworked declaration`() {
        val ours = typeFqnsIn(File("src/main/kotlin"))
        assertTrue(
            ours.none { it == "dev.wildware.udea.network.UdeaNetworked" },
            "the duplicated FQN must not be carried forward; the replacement is @Replicated/@Net",
        )
        assertTrue(
            ours.all { it.startsWith("dev.wildware.udea.annotations.") },
            "every declaration here belongs under the module's own package root, found: $ours",
        )
    }

    private companion object {
        private val PACKAGE = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)

        private val TOP_LEVEL_TYPE = Regex(
            """^(?:(?:public|internal|private|protected|open|abstract|sealed|final|data|value|inline|annotation|enum|expect|actual)\s+)*(?:class|interface|object)\s+(\w+)""",
            RegexOption.MULTILINE,
        )

        /** Fully qualified names of every top-level class, interface, object or enum under [root]. */
        fun typeFqnsIn(root: File): Set<String> {
            check(root.isDirectory) { "expected a source directory at ${root.absolutePath}" }
            return root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    val text = file.readText()
                    val pkg = PACKAGE.find(text)?.groupValues?.get(1)
                    TOP_LEVEL_TYPE.findAll(text).map { match ->
                        val simple = match.groupValues[1]
                        if (pkg.isNullOrEmpty()) simple else "$pkg.$simple"
                    }
                }
                .toSet()
        }
    }
}
