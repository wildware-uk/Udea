package dev.wildware.udea.build

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The rule that keeps a clone of this repository buildable: every sprite `:moba` names is either
 * committed or staged by the build.
 *
 * ## The defect
 *
 * `moba/assets/sprites/` is gitignored - it is paid-pack art this public repository has no right
 * to sublicense - so a clone carries none of it and `:moba:udeaValidateAssets` refused the
 * manifest with a `UDEA0032` per sheet. The fix is for the build to stage the sheets out of
 * `example/src/main/resources/assets/sprites/`, where this repository already holds them.
 *
 * ## What each test can catch
 *
 * The first four read the **real** asset scripts rather than a fixture, because the failure being
 * prevented is a mismatch between what the game names and what the build stages, and a fixture
 * agrees with whatever it was written next to. Adding a seventh character to
 * `moba/assets/character/` therefore fails [the plan stages every sprite the game names that a
 * clone does not carry] naming the sheets nobody wired up, instead of failing a CI run three
 * pushes later with a page of `UDEA0032`.
 *
 * The last two drive the task itself over a synthetic tree, which is where copying and the
 * missing-source failure are observable.
 */
class CharacterArtStagingTest {

    private val repoRoot = File("..").canonicalFile

    /**
     * The sprites a clone really does carry, asset-root-relative.
     *
     * `.gitignore` excludes everything under `moba/assets/sprites/` and then excepts these two:
     * `champion_idle.png` predates the rule, and `arrow.png` is the free demo pack's 260-byte
     * file - it is not from the paid pack and is committed at both paths. Nothing stages
     * them and nothing needs to. Held here as a list rather than as a count, and checked against
     * the tree by [the committed sprites this test exempts are really in the tree] so that a wrong
     * entry fails instead of quietly excusing a sheet the build should have staged.
     */
    private val committed = setOf(
        "sprites/champion_idle.png",
        "sprites/arrow/arrow.png",
    )

    /** `spritePath = "sprites/orc/Orc-Idle.png"`, in any `.udea.kts` under the asset root. */
    private val spritePath = Regex("spritePath\\s*=\\s*\"([^\"]+)\"")

    /** Every sprite the game's own asset scripts name, asset-root-relative. */
    private fun spritesTheGameNames(): Set<String> =
        File(repoRoot, "moba/assets").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".udea.kts") }
            .flatMap { script -> spritePath.findAll(script.readText()).map { it.groupValues[1] } }
            .filter { it.startsWith("sprites/") }
            .toSortedSet()

    @Test
    fun `the plan stages every sprite the game names that a clone does not carry`() {
        val named = spritesTheGameNames()
        assertTrue(named.isNotEmpty(), "no .udea.kts under moba/assets names a sprite at all")

        val staged = CharacterArtStaging.PLAN.keys.map { "sprites/$it" }.toSortedSet()

        assertEquals(
            (named - committed).toSortedSet(),
            staged,
            "the build stages a different set of sheets from the one moba/assets/**/*.udea.kts " +
                "names. Every sprite the game names has to be either committed or staged; one " +
                "that is neither is a UDEA0032 on every clean clone.",
        )
    }

    @Test
    fun `the committed sprites this test exempts are really in the tree`() {
        for (sprite in committed) {
            val file = File(repoRoot, "moba/assets/$sprite")
            assertTrue(file.isFile, "$file is exempted from staging but is not in the tree")
        }
    }

    @Test
    fun `every sheet the plan copies is committed in this repository`() {
        val source = File(repoRoot, CharacterArtStaging.SOURCE_TREE)
        val absent = CharacterArtStaging.PLAN.values.sorted()
            .filterNot { File(source, it).isFile }
        assertEquals(
            emptyList(),
            absent,
            "the plan copies from ${CharacterArtStaging.SOURCE_TREE}, and these are not there. " +
                "A plan naming a file no clone has stages nothing and fails the build on a " +
                "machine that has never had the art.",
        )
    }

    @Test
    fun `the plan never writes over a committed sprite`() {
        val clobbered = CharacterArtStaging.PLAN.keys.map { "sprites/$it" }.filter { it in committed }
        assertEquals(
            emptyList(),
            clobbered,
            "staging would overwrite art that is in git. A build that edits committed files " +
                "leaves a dirty working tree after a clean checkout.",
        )
    }

    @Test
    fun `staging copies every planned sheet into the destination tree`() {
        val root = createTempDirectory()
        val source = File(root, "source").also { it.mkdirs() }
        val destination = File(root, "destination").also { it.mkdirs() }
        write(source, "wizard/Wizard/Wizard-Idle.png", "idle")
        write(source, "orc/Orc-Walk.png", "walk")

        stagingTask(
            plan = mapOf(
                "wizard/Wizard-Idle.png" to "wizard/Wizard/Wizard-Idle.png",
                "orc/Orc-Walk.png" to "orc/Orc-Walk.png",
            ),
            source = source,
            destination = destination,
        ).stage()

        assertEquals("idle", File(destination, "wizard/Wizard-Idle.png").readText())
        assertEquals("walk", File(destination, "orc/Orc-Walk.png").readText())
    }

    @Test
    fun `staging fails and names the sheet when the committed art is not there`() {
        val root = createTempDirectory()
        val source = File(root, "source").also { it.mkdirs() }
        val destination = File(root, "destination").also { it.mkdirs() }
        write(source, "orc/Orc-Walk.png", "walk")

        val failure = assertFailsWith<GradleException> {
            stagingTask(
                plan = mapOf(
                    "orc/Orc-Walk.png" to "orc/Orc-Walk.png",
                    "wizard/Wizard-Idle.png" to "wizard/Wizard/Wizard-Idle.png",
                ),
                source = source,
                destination = destination,
            ).stage()
        }

        assertContains(failure.message.orEmpty(), "wizard/Wizard/Wizard-Idle.png")
    }

    /** A configured task, off a throwaway project, with nothing else applied to it. */
    private fun stagingTask(
        plan: Map<String, String>,
        source: File,
        destination: File,
    ): UdeaStageCharacterArtTask {
        val project = ProjectBuilder.builder().withProjectDir(createTempDirectory()).build()
        val task = project.tasks.register("stage", UdeaStageCharacterArtTask::class.java).get()
        task.plan.set(plan)
        task.sourceTree.set(source)
        task.destinationTree.set(destination)
        task.stagedSheets.setFrom(plan.keys.map { File(destination, it) })
        return task
    }

    private fun createTempDirectory(): File =
        java.nio.file.Files.createTempDirectory("udea-art-staging").toFile().also { it.deleteOnExit() }

    private fun write(root: File, path: String, text: String) {
        File(root, path).apply { parentFile.mkdirs() }.writeText(text)
    }
}
