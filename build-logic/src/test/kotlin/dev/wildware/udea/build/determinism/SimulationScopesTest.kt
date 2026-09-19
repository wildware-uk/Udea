package dev.wildware.udea.build.determinism

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Which simulation scopes a build scans, and what a scope has to say for itself (issue #265).
 *
 * Two builds run `udeaVerifyDeterminism` and they contain different things: this repository,
 * which has the engine in it, and the build of a game that resolves the engine from a Maven
 * repository. The engine's scopes are right for the first and nonsense in the second - the
 * module directories they name are not under that build's root at all - so which ones apply is a
 * decision, and this is where it is made.
 */
class SimulationScopesTest {

    private val engine: Set<String> = DeterminismRules.SIMULATION_SCOPES.map { it.project }.toSet()

    @Test
    fun `a build that contains the engine scans every engine scope`() {
        val scopes = DeterminismRules.engineScopesIn(engine + setOf(":moba:game", ":moba:desktop"))

        assertEquals(DeterminismRules.SIMULATION_SCOPES, scopes)
    }

    @Test
    fun `a game's own build scans none of them`() {
        // The projects a game in its own repository has. The engine is in that build too, as an
        // included build - which scans its own modules in its own `check`, and whose modules are
        // not projects of this one.
        val scopes = DeterminismRules.engineScopesIn(setOf(":", ":game", ":desktop"))

        assertEquals(emptyList(), scopes)
    }

    @Test
    fun `an engine build missing a declared module fails rather than scanning one fewer`() {
        // The failure mode the filter would otherwise introduce: a module renamed, a table not
        // updated, and a gate that quietly covers less than it says. It is the same defect as a
        // rule scoped to a project that no longer exists, which `ModuleGraphRulesTest` refuses.
        val incomplete = engine - ":udea-gas"

        val failure = assertFailsWith<IllegalStateException> {
            DeterminismRules.engineScopesIn(incomplete)
        }

        assertTrue(":udea-gas" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `a scope has to argue for itself, wherever it is declared`() {
        // `udeaGates { simulation(...) }` builds a `SimScope` too, so a game outside this
        // repository is held to the same thing the engine's own table is: issue #150's rule that
        // membership is declared and argued for, never inferred from a module name.
        val failure = assertFailsWith<IllegalArgumentException> {
            SimScope(project = ":game", sourceSet = "main", packagePrefixes = emptyList(), why = "it is")
        }

        assertTrue("argued for" in failure.message.orEmpty(), failure.message.orEmpty())

        val pathless = assertFailsWith<IllegalArgumentException> {
            SimScope(
                project = "game",
                sourceSet = "main",
                packagePrefixes = emptyList(),
                why = "A long enough reason that the length rule is satisfied and the path is " +
                    "the only thing wrong with this scope.",
            )
        }
        assertTrue("Gradle path" in pathless.message.orEmpty(), pathless.message.orEmpty())
    }
}
