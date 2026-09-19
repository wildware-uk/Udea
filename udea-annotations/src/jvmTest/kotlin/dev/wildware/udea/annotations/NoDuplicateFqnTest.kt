package dev.wildware.udea.annotations

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression guard for the defect this module exists to remove.
 *
 * `dev.wildware.udea.network.UdeaNetworked` was declared twice in the old tree - once in
 * `common` and once in `gradle-plugin` - and both landed on one compile classpath, so which one
 * a reflective scan bound against depended on classpath ordering. `udea-annotations` is the
 * single home for the annotations; this test fails if it carries that FQN forward.
 *
 * Until issue #213 it also scanned the two old trees for any FQN declared here. Both trees are
 * deleted, so there is nothing left for a declaration here to collide with.
 */
class NoDuplicateFqnTest {

    @Test
    fun `this module does not reuse the FQN of either old UdeaNetworked declaration`() {
        val ours = typeFqnsIn(File("src/commonMain/kotlin"))
        assertTrue(
            ours.isNotEmpty(),
            "scanned no declarations in udea-annotations - the scanner is broken, not the tree",
        )
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
