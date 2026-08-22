package dev.wildware.udea.compiler.fir

import dev.wildware.udea.compiler.UdeaCompilerPlugin
import dev.wildware.udea.compiler.testing.CheckerRun
import dev.wildware.udea.compiler.testing.TestSource
import dev.wildware.udea.compiler.testing.UdeaCheckerTest
import dev.wildware.udea.compiler.testing.UdeaCompileTesting
import dev.wildware.udea.compiler.testing.source
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The KDoc harvester, driven through a real compilation (issue #42, Trello #12).
 *
 * The claim being tested is the one spec 3.2 makes for K2 over KSP2: that documentation
 * written on a source declaration can be read at all. Everything downstream of the index -
 * `udea-codegen` calling `addKdoc`, and the Gradle step that orders the harvest before KSP -
 * lives in other modules; what is provable here is that the index is produced, complete,
 * repo-relative and byte-stable.
 */
class KDocHarvestExtensionTest : UdeaCheckerTest() {

    private val documented = source(
        "Ability.kt",
        """
        package udea.fixtures

        /**
         * One activatable ability.
         *
         * See [Cooldown] for how the timer is stored.
         *
         * @param name the ability's display name.
         * @param cooldown ticks before it may be activated again.
         * @return nothing; this is a class.
         * @sample udea.fixtures.dropped
         */
        class Ability(val name: String, val cooldown: Int) {

            /** How long until the ability is ready. */
            var remaining: Int = 0

            var undocumented: Int = 0
        }

        class Cooldown
        """,
    )

    private fun harvest(vararg sources: TestSource): Pair<CheckerRun, String> {
        val workDir = UdeaCompileTesting.newWorkDir()
        val index = File(workDir, "build/udea/kdoc-index.json")
        val run = UdeaCompileTesting.compile(
            sources = sources.toList(),
            pluginOptions = mapOf(
                UdeaCompilerPlugin.OPTION_KDOC_INDEX to index.absolutePath,
                UdeaCompilerPlugin.OPTION_REPO_ROOT to workDir.absolutePath,
            ),
            workDir = workDir,
        )
        assertEquals(emptyList(), run.otherMessages, "the fixture must compile:\n" + run.describe())
        return run to (if (index.isFile) index.readText() else "")
    }

    @Test
    fun `a documented declaration lands in the index with its class doc and every param`() {
        val (_, index) = harvest(documented)

        assertTrue("\"fqn\": \"udea.fixtures.Ability\"" in index, index)
        assertTrue("One activatable ability." in index, index)
        assertTrue(
            """{"name": "name", "text": "the ability's display name."}""" in index,
            "the @param text must be carried verbatim:\n$index",
        )
        assertTrue(
            """{"name": "cooldown", "text": "ticks before it may be activated again."}""" in index,
            index,
        )
        assertTrue("""{"tag": "return", "text": "nothing; this is a class."}""" in index, index)
    }

    @Test
    fun `a link is fully qualified so it still resolves from a generated file`() {
        val (_, index) = harvest(documented)

        assertTrue(
            "[udea.fixtures.Cooldown]" in index,
            "`[Cooldown]` must be qualified against the source file's own declarations:\n$index",
        )
    }

    @Test
    fun `a member's own KDoc is harvested under its own fully qualified name`() {
        val (_, index) = harvest(documented)

        assertTrue("\"fqn\": \"udea.fixtures.Ability.remaining\"" in index, index)
        assertTrue("How long until the ability is ready." in index, index)
    }

    @Test
    fun `a declaration with no KDoc produces no entry and no warning`() {
        val (run, index) = harvest(documented)

        assertFalse("undocumented" in index, "an undocumented member must not be indexed:\n$index")
        assertEquals(emptyList(), run.diagnostics, run.describe())
        assertEquals(emptyList(), run.otherMessages, run.describe())
    }

    @Test
    fun `spans are repo-relative, never absolute`() {
        val (_, index) = harvest(documented)

        assertTrue("\"span\": \"${UdeaCompileTesting.SOURCE_DIR}/Ability.kt:" in index, index)
    }

    @Test
    fun `two clean runs produce a byte-identical index`() {
        // Issue #42's determinism acceptance. The compiler is free to visit files in any
        // order, so the index is written sorted rather than in visitation order.
        val extra = source(
            "Zzz.kt",
            """
            package udea.fixtures

            /** Declared last, sorted first by nothing but its name. */
            class Aaa

            /** Declared first in the alphabet's tail. */
            class Zzz
            """,
        )

        val (_, first) = harvest(documented, extra)
        val (_, second) = harvest(documented, extra)

        assertEquals(first, second)
        assertTrue(first.indexOf("udea.fixtures.Aaa") < first.indexOf("udea.fixtures.Zzz"), first)
    }

    @Test
    fun `without the kdocIndex option nothing is harvested and nothing is written`() {
        // The harvester is opt-in, so an ordinary build - and the plugin-disabled build - pays
        // nothing for it and cannot depend on it.
        val workDir = UdeaCompileTesting.newWorkDir()
        val index = File(workDir, "build/udea/kdoc-index.json")

        UdeaCompileTesting.compile(listOf(documented), workDir = workDir)

        assertFalse(index.exists(), "no kdocIndex option must mean no output at all")
    }
}
