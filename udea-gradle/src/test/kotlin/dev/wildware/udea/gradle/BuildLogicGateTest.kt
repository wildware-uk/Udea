package dev.wildware.udea.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `build-logic`'s own tests are inside `./gradlew build`, and this is what keeps them there.
 *
 * ## What was wrong
 *
 * `build-logic` is an **included build**. The outer build compiles its classes to configure
 * itself and then reaches none of its tasks, so the repository could be green while
 * `:build-logic:test` was red - and it was. Issue #265 deleted `ModuleGraphRules.governs` and
 * `ModuleGraphRules.GAME_PROJECTS` and left `ModuleGraphRulesTest` calling both, so the suite did
 * not compile. Its reviewer ran `./gradlew build` with no exclusions, got a genuine green, and
 * merged it: the tests that change broke are exactly the tests that gate did not run.
 *
 * Be precise about which half was dark. The `udeaVerify*` **tasks** are on the outer `check` and
 * ran correctly throughout; what nothing ran was the unit tests **of** those rules.
 *
 * ## Why this test lives in `:udea-gradle` and not in `build-logic`
 *
 * A fence inside `build-logic` would be reached only by the wiring it exists to check, so
 * deleting the wiring would delete the alarm with it. `:udea-gradle` is a project of the outer
 * build, so this runs whenever the outer `check` does, wiring or no wiring.
 *
 * ## What it does not say
 *
 * It reads build scripts as text; it does not resolve a task graph. Text is the weaker half of
 * the evidence and it is deliberate that it is not the whole of it - the executed proof is
 * `BRIEF.md`'s transcript, where a deliberate `Unresolved reference` in `build-logic`'s test
 * sources fails `./gradlew build`. This test is what notices the wiring being *removed* between
 * such runs.
 */
class BuildLogicGateTest {

    private val rootBuildScript = RepositoryUnderTest.file("build.gradle.kts")
    private val buildLogicScript = RepositoryUnderTest.file("build-logic/build.gradle.kts")

    @Test
    fun `the outer check depends on build-logic's aggregate verification task`() {
        assertTrue(rootBuildScript.isFile, "no root build script at $rootBuildScript")
        val block = BuildLogicGate.outerTaskBlock(rootBuildScript.readText())
        assertNotNull(
            block,
            "build.gradle.kts no longer configures `tasks.named(\"${BuildLogicGate.OUTER_TASK}\")`, " +
                "so nothing in the outer build reaches the included build `build-logic`. Its unit " +
                "tests are then run by no habit anybody has, which is the defect issue #265's " +
                "merge exposed.",
        )
        assertTrue(
            BuildLogicGate.wiresOuterTask(rootBuildScript.readText()),
            "the outer `${BuildLogicGate.OUTER_TASK}` does not carry\n    ${BuildLogicGate.WIRING}\n" +
                "so `./gradlew build` reaches none of `build-logic`'s tasks and its test suite can " +
                "be red while this repository is green. The block was:\n$block",
        )
    }

    @Test
    fun `build-logic declares that task over every project of its own build`() {
        assertTrue(buildLogicScript.isFile, "no build script at $buildLogicScript")
        val code = BuildLogicGate.stripComments(buildLogicScript.readText())
        assertTrue(
            "val ${BuildLogicGate.AGGREGATE_TASK} by tasks.registering" in code,
            "build-logic/build.gradle.kts does not register `${BuildLogicGate.AGGREGATE_TASK}`, " +
                "which is the task the outer `${BuildLogicGate.OUTER_TASK}` depends on. The outer " +
                "build then fails to configure rather than failing to notice, but the suite is " +
                "unreached either way.",
        )
        val body = code.substringAfter("val ${BuildLogicGate.AGGREGATE_TASK} by tasks.registering")
            .substringBefore("\n}")
        assertTrue(
            "allprojects" in body,
            "`${BuildLogicGate.AGGREGATE_TASK}` no longer derives its members from `allprojects`, " +
                "so a project added to the `build-logic` build gets no gate and is dark in exactly " +
                "the way the whole build was. The body was:\n$body",
        )
    }

    /**
     * The control, in both directions.
     *
     * A fence that passes on prose is no fence, and one that fails on code is worse than none.
     * The scanner is handed the same wiring three ways: as code, as a `//` comment, and inside
     * KDoc - because the real root build script carries the sentence in a comment right beside
     * the statement, and a raw-line `contains` would be satisfied by the sentence alone.
     */
    @Test
    fun `wiring only mentioned in a comment does not count`() {
        val wired = """
            tasks.named("check") {
                ${BuildLogicGate.WIRING}
            }
        """.trimIndent()
        assertTrue(BuildLogicGate.wiresOuterTask(wired), "the scanner does not see real code")

        val commented = """
            tasks.named("check") {
                // ${BuildLogicGate.WIRING}
            }
        """.trimIndent()
        assertFalse(BuildLogicGate.wiresOuterTask(commented), "a `//` comment satisfied the scanner")

        val documented = """
            /** Once upon a time this said ${BuildLogicGate.WIRING} and then it did not. */
            tasks.named("check") {
                dependsOn("somethingElse")
            }
        """.trimIndent()
        assertFalse(BuildLogicGate.wiresOuterTask(documented), "KDoc satisfied the scanner")
    }

    /**
     * The other half of the same control: the scanner must not be defeated by an unrelated
     * `dependsOn` somewhere else in a 480-line script.
     */
    @Test
    fun `wiring attached to some other task does not count`() {
        val elsewhere = """
            val udeaSomethingElse by tasks.registering {
                ${BuildLogicGate.WIRING}
            }
            tasks.named("check") {
                dependsOn(udeaSomethingElse)
            }
        """.trimIndent()
        assertFalse(
            BuildLogicGate.wiresOuterTask(elsewhere),
            "a dependency hung on a task nothing runs answered for the one that is run",
        )
    }

    /**
     * The stripper keeps string literals, because these scripts are full of `https://` URLs and a
     * `//` cut would swallow the rest of the line that quotes one.
     */
    @Test
    fun `stripping comments keeps the url inside a string`() {
        assertEquals(
            """url.set("https://github.com/wildware-uk/Udea")""",
            BuildLogicGate.stripComments(
                """url.set("https://github.com/wildware-uk/Udea") // the project page""",
            ).trim(),
        )
    }
}
