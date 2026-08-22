package dev.wildware.udea.render.headless

import dev.wildware.udea.render.support.RepoLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `udeaVerifyHeadless`: the Phase 0 exit gate that keeps `udea-core` free of GL.
 *
 * This is the class the `udeaVerifyHeadless` Gradle task runs, and the only one the ordinary
 * `test` task skips -- so that the gate is a task you can name, and running it does not mean
 * running the whole module's suite.
 *
 * Spec 4 says `udea-core` is the headless kernel with no GL on the compile classpath, and
 * spec 3.5 makes `RenderMode.Headless` mean "no GL context at all". Neither survives six
 * phases as a convention: the old tree lost this exact property when `SpriteRenderer.kt`
 * imported `com.badlogic.gdx.graphics.Texture` into a component the world tick touched, and
 * nothing failed until somebody tried to run a server without a display.
 *
 * See [HeadlessScan] for what is scanned and for the relationship to `UDEA-MG-002`, whose
 * configuration-level half this extends rather than restates.
 */
class UdeaVerifyHeadlessTest {

    @Test
    fun `no headless module references a GL type`() {
        val report = HeadlessScan.run()

        assertEquals(
            emptyList(),
            report.diagnostics.map { it.toString() },
            "GL reached a headless module; move the code to udea-render (spec 4)",
        )
        assertEquals(0, report.suppressedCount, "violations were capped or collapsed away")
    }

    @Test
    fun `every designated module was actually scanned`() {
        // The failure mode a gate like this dies of: a module renamed, a build directory
        // missing, and a green tick that means "nothing was read". `HeadlessScan.run` fails
        // on an empty module; this fails on a module that is not a module any more.
        for (module in HeadlessScan.HEADLESS_MODULES) {
            val classes = RepoLayout.classFiles(module)
            assertTrue(classes.isNotEmpty(), "$module contributed no compiled classes")
        }
    }

    @Test
    fun `the designated list is the one the issue names`() {
        // A module quietly dropped from the list is indistinguishable from a module that
        // passes, so the list itself is asserted rather than assumed.
        assertEquals(
            listOf("udea-agent", "udea-annotations", "udea-assets", "udea-core", "udea-gas", "udea-net"),
            HeadlessScan.HEADLESS_MODULES.sorted(),
        )
    }
}
