package dev.wildware.udea.build

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `dev.wildware.udea.compose-ui` gives a game the Compose compiler, so a screen it writes is a real
 * composable (issue #275).
 *
 * `UiScreen.content` and `CapturedUi`'s content are `@Composable`. Before this convention none of
 * the published ones applied the Compose compiler, so a game in its own repository compiled its
 * screen as a plain function: that compiles, and it fails at run time where it meets the toolkit,
 * because a composable the Compose compiler built takes a `Composer` a plain caller never passes
 * (`NoSuchMethodError`, which the control below asserts). So the
 * property is observed where it lives - by composing the game's screen - not by reading which
 * plugins the project applied, which is a description of the build rather than the build.
 *
 * The fixture is a game shaped like `templates/new-game`: `dev.wildware.udea.kotlin-library`
 * plus this convention, and nothing else about Compose. Its screen stands in for `UiScreen` with
 * an interface of the same shape, because `udea-render` is a project of the outer build and is
 * not published at the point this suite runs; `composegl-ui` is what a `UiScreen` is written in,
 * and it comes from the repository it comes from for every game. The real `UiScreen`, over a
 * published engine, is the outside-game transcript in the branch's brief.
 *
 * It needs the network, like the other fixtures here that resolve a real dependency: ComposeGL
 * publishes snapshots only.
 */
class ComposeUiConventionTest {

    private fun game(root: File, plugins: String): GradleFixture =
        GradleFixture(root).withVersionCatalog().withCompilerPluginProject().project(
            "game",
            """
            plugins {
                $plugins
            }

            repositories {
                mavenCentral()
                // The Compose runtime ComposeGL's runtime resolves to is `androidx.compose.runtime`,
                // published only to Google's repository.
                google()
                // ComposeGL publishes snapshots only, as `templates/new-game/settings.gradle.kts` says.
                maven("https://central.sonatype.com/repository/maven-snapshots/")
            }

            dependencies {
                implementation(libs.composegl.ui)
            }
            """.trimIndent(),
        ).also {
            source(root, "game/src/main/kotlin/game/Greeting.kt", GREETING)
            source(root, "game/src/test/kotlin/game/GreetingTest.kt", GREETING_TEST)
        }

    private fun source(root: File, path: String, text: String) {
        File(root, path).apply { parentFile.mkdirs() }.writeText(text.trimIndent() + "\n")
    }

    @Test
    fun `a game on the convention writes a screen that the toolkit composes`(@TempDir root: File) {
        val result = game(
            root,
            """
            id("dev.wildware.udea.kotlin-library")
            id("dev.wildware.udea.compose-ui")
            """.trimIndent(),
        ).build(":game:test")

        assertTrue(":game:test" in result.output, result.output)
        val report = File(root, "game/build/test-results/test/TEST-game.GreetingTest.xml")
        assertTrue(report.isFile, "the screen's test did not run:\n${result.output}")
        assertTrue("tests=\"1\"" in report.readText() && "failures=\"0\"" in report.readText(), report.readText())
    }

    @Test
    fun `without the convention the same game compiles and its screen cannot be composed`(@TempDir root: File) {
        // The control, and the reason the test above composes rather than compiles: a game with no
        // Compose compiler builds green, so "it compiled" would pass on exactly the defect.
        val result = game(root, """id("dev.wildware.udea.kotlin-library")""").buildAndFail(":game:test")

        assertTrue(":game:compileKotlin" in result.output, result.output)
        val report = File(root, "game/build/test-results/test/TEST-game.GreetingTest.xml")
        assertTrue(report.isFile, "the screen's test did not run, so this failure says nothing:\n${result.output}")
        assertTrue("NoSuchMethodError" in report.readText(), report.readText())
    }

    @Test
    fun `the convention can be applied before the Kotlin one`(@TempDir root: File) {
        // A game lists its plugins in whatever order it likes; a convention that only worked when
        // it came second would be one a reader has to know a rule about.
        game(
            root,
            """
            id("dev.wildware.udea.compose-ui")
            id("dev.wildware.udea.kotlin-library")
            """.trimIndent(),
        ).build(":game:test")
    }

    private companion object {
        /** A screen written the way `MobaHudScreen` is: a ComposeGL widget inside `content()`. */
        const val GREETING = """
            package game

            import androidx.compose.runtime.Composable
            import dev.wildware.composegl.ui.modifier.Modifier
            import dev.wildware.composegl.ui.modifier.testTag
            import dev.wildware.composegl.ui.widget.Text

            /** The shape of `dev.wildware.udea.render.ui.UiScreen`. */
            public interface Screen {
                @Composable
                public fun content()
            }

            public class Greeting : Screen {
                @Composable
                override fun content() {
                    Text("hello from outside", Modifier.testTag("greeting"))
                }
            }
        """

        const val GREETING_TEST = """
            package game

            import dev.wildware.composegl.ui.testing.uiTest
            import kotlin.test.Test
            import kotlin.test.assertEquals

            class GreetingTest {
                @Test
                fun `the toolkit composes the game's screen`() {
                    val screen: Screen = Greeting()
                    uiTest { screen.content() }.use { ui ->
                        assertEquals("hello from outside", ui.text("greeting"))
                    }
                }
            }
        """
    }
}
