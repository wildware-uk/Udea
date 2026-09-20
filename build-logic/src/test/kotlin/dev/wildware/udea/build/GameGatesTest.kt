package dev.wildware.udea.build

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `dev.wildware.udea.game-gates` over a build that is not this repository (issue #265).
 *
 * The fixture is a root project with two subprojects and no engine in it, which is the shape of
 * a game's own build. What is being tested is that applying one plugin is enough: the per-project
 * module-graph gate lands on each project, the determinism gate lands on the root and scans what
 * `udeaGates { simulation(...) }` declared, and a declaration that names nothing is refused
 * rather than scanned.
 *
 * `scripts/outside-game-proof.sh` is the other half of this and runs the real thing - the whole
 * of `templates/new-game`, copied outside the repository, built against this checkout. These
 * cases are here because they run in seconds and cover the branches that proof cannot reach
 * without a deliberately broken template.
 */
class GameGatesTest {

    private val gates = "dev.wildware.udea.game-gates"

    /** A game project: real configurations, so the module-graph gate has something to inspect. */
    private fun gameProject(name: String, fixture: GradleFixture) = fixture.project(
        name,
        """
        plugins { `java-library` }
        """.trimIndent(),
    )

    @Test
    fun `one plugin puts the module-graph gate on every project of the build`(@TempDir root: File) {
        val fixture = GradleFixture(root)
        gameProject("game", fixture)
        gameProject("desktop", fixture)

        val result = fixture.build(
            "udeaVerifyModuleGraph",
            rootBuildScript = """
                plugins { id("$gates") }
            """.trimIndent(),
        )

        assertTrue(":game:udeaVerifyModuleGraph" in result.output, result.output)
        assertTrue(":desktop:udeaVerifyModuleGraph" in result.output, result.output)
    }

    @Test
    fun `the aggregate is on the root's check, so an ordinary build cannot pass while a rule is broken`(
        @TempDir root: File,
    ) {
        val fixture = GradleFixture(root)
        fixture.publish("org.jetbrains.kotlin:kotlin-scripting-jvm:2.4.20")
        fixture.project(
            "game",
            """
            plugins { `java-library` }
            ${fixture.repositoryBlock()}
            dependencies { implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:2.4.20") }
            """.trimIndent(),
        )

        val result = fixture.buildAndFail(
            ":game:check",
            rootBuildScript = """
                plugins { id("$gates") }
            """.trimIndent(),
        )

        // UDEA-MG-005 scoped to `ProjectScope.GAME`: `:game` is not an engine module, so the ban
        // on a scripting host in a shipped game applies to it although nothing names its path.
        assertTrue("UDEA-MG-005" in result.output, result.output)
        assertTrue("kotlin-scripting-jvm" in result.output, result.output)
    }

    @Test
    fun `a declared simulation scope that compiled nothing fails rather than passing on an empty scan`(
        @TempDir root: File,
    ) {
        val fixture = GradleFixture(root)
        fixture.project(
            "game",
            """
            plugins { `java-library` }
            // What `dev.wildware.udea.kotlin-library` registers on a real game module: the task that builds
            // the bytecode the scan reads. The gate depends on it by path, so that it can never
            // read whatever stale `build/classes` was lying around.
            tasks.register("${ModuleGraphRules.MAIN_BYTECODE_TASK}")
            """.trimIndent(),
        )

        val result = fixture.buildAndFail(
            "udeaVerifyDeterminism",
            rootBuildScript = """
                plugins { id("$gates") }

                udeaGates {
                    simulation(
                        project = ":game",
                        packagePrefixes = listOf("com.example.game.sim"),
                        why = "The game's own rules, declared so the determinism scan reads them; " +
                            "this fixture compiles nothing, which is the case under test.",
                    )
                }
            """.trimIndent(),
        )

        // The gate is registered, it ran, and it refused to report on a scan of nothing - which
        // is the failure mode every gate in this repository is written against.
        assertTrue("contributed no compiled classes" in result.output, result.output)
        assertTrue(":game" in result.output, result.output)
    }

    @Test
    fun `a simulation scope over a project this build does not have is refused`(@TempDir root: File) {
        val fixture = GradleFixture(root)
        gameProject("game", fixture)

        val result = fixture.buildAndFail(
            "help",
            rootBuildScript = """
                plugins { id("$gates") }

                udeaGates {
                    simulation(
                        project = ":nothing-here",
                        why = "A scope over a project that does not exist, which is a scan of " +
                            "nothing dressed up as a declaration.",
                    )
                }
            """.trimIndent(),
        )

        assertTrue("does not include" in result.output, result.output)
        assertTrue(":nothing-here" in result.output, result.output)
    }
}
