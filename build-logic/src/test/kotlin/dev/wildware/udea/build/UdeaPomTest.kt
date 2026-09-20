package dev.wildware.udea.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The engine and its build plugins describe themselves to Maven Central as one project.
 *
 * Two build scripts write a POM: the root one, for every `udea-*` module, which reads [UdeaPom];
 * and `build-logic`'s own, for the convention plugins, which cannot - a build script cannot use a
 * class the project it configures compiles. This is the fence over that second copy. It is a text
 * check rather than a published-artifact check on purpose: a published POM is only observable
 * after `publishToMavenLocal`, which is a minutes-long build, and the failure this guards against
 * is somebody editing one licence line and not the other.
 */
class UdeaPomTest {
    private val buildLogicScript = File("build.gradle.kts")

    @Test
    fun `build-logic writes the same POM values the engine's modules do`() {
        assertTrue(buildLogicScript.isFile, "expected ${buildLogicScript.absolutePath}")
        val text = buildLogicScript.readText()
        val missing = UdeaPom.VALUES.filterNot { text.contains(it) }
        if (missing.isNotEmpty()) {
            fail(
                "build-logic/build.gradle.kts publishes the convention plugins a game applies, " +
                    "and its POM has to say the same things about this project as the engine's " +
                    "modules do. These values from UdeaPom are not in it: $missing",
            )
        }
    }

    @Test
    fun `the check would notice a value that is only in one of them`() {
        // The control for the test above: it asserts membership of a list, so it is only worth
        // anything if a value that is absent is actually reported. A value no build script would
        // ever contain stands in for one somebody edited on one side alone.
        val text = buildLogicScript.readText()
        assertTrue(
            !text.contains("https://example.invalid/not-the-project"),
            "if this ever passes the fixture is wrong, not the build script",
        )
        val missing = (UdeaPom.VALUES + "https://example.invalid/not-the-project")
            .filterNot { text.contains(it) }
        assertTrue(
            missing == listOf("https://example.invalid/not-the-project"),
            "expected exactly the planted value to be reported missing, got $missing",
        )
    }
}
