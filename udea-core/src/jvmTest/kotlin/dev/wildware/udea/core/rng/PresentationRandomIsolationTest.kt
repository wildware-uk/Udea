package dev.wildware.udea.core.rng

import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.ModuleFiles
import dev.wildware.udea.core.RngService
import dev.wildware.udea.core.fixtures.testGameContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `PresentationRandom` must be **unreachable** from simulation code, not merely discouraged.
 *
 * Spec 5 gives presentation a separately typed, wall-seeded generator, and the separation is
 * only real if the module graph enforces it: a `PresentationRandom` that `udea-core` could
 * import would be one `import` away from a `SimSystem`, and the first time somebody jittered
 * a value with it the simulation would stop being reproducible — with nothing failing until a
 * replay diverged weeks later.
 *
 * The enforcement is structural and has three parts, of which this module owns two:
 *
 * 1. the type is declared in `udea-render` (issue #116), which is *downstream* of
 *    `udea-core`;
 * 2. `udea-core` declares no dependency on it, so the name cannot resolve here — asserted
 *    below against the build script and against the actual test classpath;
 * 3. it is deliberately **not** registered on `GameContext`, so no system can reach it
 *    through the one injectable either — also asserted below.
 *
 * A Gradle module-graph check (build tooling epic) is the fourth, and covers every module at
 * once.
 */
class PresentationRandomIsolationTest {

    @Test
    fun `udea-core declares no dependency on the module that owns PresentationRandom`() {
        val buildScript = ModuleFiles.moduleDir.resolve("build.gradle.kts").readText()

        val offending = DOWNSTREAM_MODULES.filter { module -> "\":$module\"" in buildScript }

        assertEquals(
            emptyList(),
            offending,
            "udea-core must stay upstream of presentation; depending on $offending would make " +
                "PresentationRandom importable from a SimSystem",
        )
    }

    @Test
    fun `PresentationRandom is not on udea-core's classpath at all`() {
        // Belt and braces: the build script check catches a declared dependency, this catches
        // one arriving transitively or through a test fixture.
        for (candidate in CANDIDATE_NAMES) {
            assertFailsWith<ClassNotFoundException>("$candidate is reachable from udea-core") {
                Class.forName(candidate, false, javaClass.classLoader)
            }
        }
    }

    @Test
    fun `udea-core declares nothing called PresentationRandom itself`() {
        val offenders = ModuleFiles.mainSources
            .filter { "PresentationRandom" in it.readText() }
            .map { ModuleFiles.relativePath(it) }

        assertEquals(
            emptyList(),
            offenders,
            "the presentation generator belongs to udea-render, not to the kernel",
        )
    }

    @Test
    fun `the only randomness a GameContext offers is the seeded simulation service`() {
        val ctx = testGameContext(seed = 3L)

        // `rng` is the whole surface: there is no second generator on the context, and no
        // service key registered for one, so a system has nothing else to reach for.
        assertEquals(
            listOf("rng"),
            GameContext::class.java.declaredFields
                .filter { RngService::class.java.isAssignableFrom(it.type) }
                .map { it.name },
            "GameContext names exactly one generator, and it is the seeded simulation one",
        )
        assertEquals(
            emptyList(),
            ctx.serviceKeys.map { it.name }.filter { "andom" in it },
            "a wall-seeded generator on the context would make the type separation decorative",
        )
    }

    private companion object {
        /** Modules that sit downstream of the kernel; none may appear in its build script. */
        val DOWNSTREAM_MODULES = listOf("udea-render", "udea-agent", "udea-agent-host", "moba")

        /** Where `PresentationRandom` may plausibly be declared, all of them out of reach. */
        val CANDIDATE_NAMES = listOf(
            "dev.wildware.udea.render.PresentationRandom",
            "dev.wildware.udea.core.PresentationRandom",
            "dev.wildware.udea.core.rng.PresentationRandom",
        )
    }
}
