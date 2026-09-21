package dev.wildware.udea.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The convention hands a module's KSP run the project's component id space (issue #274).
 *
 * [UdeaNetComponentsTest] proves the *rule* - what a registry file means, and what a rewrite of
 * one should say. This proves the rule is attached to a build, which is the whole of the defect:
 * `UdeaNetComponents` had held the parse and the sorting since Phase 0 and **nothing published
 * ever called it**. Each module inside this repository wrote the same eight lines into its own
 * build script, so a game on the published conventions had no way to pass the list at all, and
 * re-implemented the sorting - the one part that decides what a component's id is.
 *
 * ## The stand-in `ksp`
 *
 * `build-logic` cannot put the KSP Gradle plugin on its classpath (`UdeaModuleRegistry` says
 * why), so the wiring reaches the extension by name and calls `arg(String, String)` on it. The
 * fixtures here register an object of their own under that name, which makes the whole path -
 * find the file, parse it, sort it, hand it over - observable in seconds, with no KSP, no
 * network and no Kotlin compilation. `scripts/outside-game-proof.sh` is the other half: it runs
 * the real processor, over a real game, from outside this repository.
 */
class NetComponentsWiringTest {

    /** What the stand-in prints when the convention calls it. */
    private val marker = "FAKE-KSP ${UdeaNetComponents.KSP_OPTION}="

    /**
     * A module on `dev.wildware.udea.kotlin-library` whose `ksp` extension records what it is told.
     *
     * `extensions.add` rather than `extensions.create`: `add` takes an instance, so nothing has
     * to be constructible by Gradle's `ObjectFactory` from inside a build script.
     */
    private val recordingKsp =
        """
        plugins { id("dev.wildware.udea.kotlin-library") }

        open class FakeKsp {
            fun arg(key: String, value: String) {
                println("FAKE-KSP " + key + "=" + value)
            }
        }
        extensions.add("ksp", FakeKsp())
        """.trimIndent()

    private fun fixture(root: File, project: String = recordingKsp): GradleFixture =
        GradleFixture(root).withVersionCatalog().withCompilerPluginProject().project("game", project)

    private fun registry(root: File, text: String, name: String = UdeaNetComponents.FILE_NAME) {
        val file = File(root, name)
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    /** The one line the module was handed, or `null` if it was handed none. */
    private fun handedOver(output: String): String? =
        output.lineSequence().firstOrNull { it.startsWith(marker) }?.removePrefix(marker)

    @Test
    fun `a module whose build script says nothing is handed the root registry, sorted`(
        @TempDir root: File,
    ) {
        // Unsorted on disk is not the case under test - `parse` refuses that - so the file is
        // written sorted and what is asserted is that the *option* is the comma-separated list
        // the processor splits on. The sorting itself is UdeaNetComponentsTest's.
        registry(root, "# the id space\ngas.Shield\nmoba.Health\n")

        val result = fixture(root).build("help")

        assertEquals("gas.Shield,moba.Health", handedOver(result.output), result.output)
    }

    @Test
    fun `no registry file means no option, and the build is not failed for it`(@TempDir root: File) {
        // The known negative, run beside the positive above: a build with no `@Replicated`
        // component anywhere needs no id space, and refusing here would fail every game that has
        // not got one yet. The processor is the only thing that can tell "no components" from "a
        // module that emits a wire protocol and was told nothing", and it does.
        val result = fixture(root).build("help")

        assertEquals(null, handedOver(result.output), result.output)
        assertFalse(marker in result.output, result.output)
    }

    @Test
    fun `a module that does not run KSP is left alone`(@TempDir root: File) {
        registry(root, "gas.Shield\n")

        val result = fixture(
            root,
            project = """
                plugins { id("dev.wildware.udea.kotlin-library") }
            """.trimIndent(),
        ).build("help")

        assertFalse(marker in result.output, result.output)
    }

    @Test
    fun `the root build script can put the registry somewhere else`(@TempDir root: File) {
        // The second half of the issue: a game that keeps the file elsewhere states it once, in
        // the root script, rather than teaching every module where to look.
        registry(root, "gas.Shield\n", name = UdeaNetComponents.FILE_NAME)
        registry(root, "elsewhere.Component\n", name = "wire/components.lock")

        val result = fixture(root).build(
            "help",
            rootBuildScript = """
                plugins { id("dev.wildware.udea.game-gates") }

                udeaNetComponents {
                    registry.set(layout.projectDirectory.file("wire/components.lock"))
                }
            """.trimIndent(),
        )

        assertEquals("elsewhere.Component", handedOver(result.output), result.output)
    }

    @Test
    fun `a registry that is not an id space fails the build, naming the file`(@TempDir root: File) {
        registry(root, "moba.Health\ngas.Shield\n")

        val result = fixture(root).buildAndFail("help")

        assertTrue("must be sorted" in result.output, result.output)
        assertTrue(UdeaNetComponents.FILE_NAME in result.output, result.output)
    }

    @Test
    fun `an extension called ksp that cannot take an option fails rather than dropping it`(
        @TempDir root: File,
    ) {
        // If KSP's DSL ever moves, the option would otherwise vanish in silence and every module
        // would go back to numbering from its own symbols starting at 0 - with a green build, an
        // internally consistent lock per module, and a protoHash reporting agreement.
        registry(root, "gas.Shield\n")

        val result = fixture(
            root,
            project = """
                plugins { id("dev.wildware.udea.kotlin-library") }

                open class NotKsp
                extensions.add("ksp", NotKsp())
            """.trimIndent(),
        ).buildAndFail("help")

        assertTrue("has no arg(String, String)" in result.output, result.output)
        assertTrue(":game" in result.output, result.output)
    }
}
