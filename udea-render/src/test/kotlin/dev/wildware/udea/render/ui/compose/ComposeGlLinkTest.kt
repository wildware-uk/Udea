package dev.wildware.udea.render.ui.compose

import com.badlogic.gdx.graphics.g2d.Batch
import dev.wildware.composegl.gdx.GdxBackend
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.backend.UiBackend
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #186's acceptance criterion: a test that composes a real ComposeGL `@Composable` from
 * `udea-render`'s main compilation and asserts on the result.
 *
 * ## Why this is the evidence for a version bump
 *
 * "The dependency resolves" is not the claim #186 makes. The claim is that the two halves *link*,
 * and there are three ways for that to be false while the build is green, so the test asserts
 * against all three:
 *
 * - the ComposeGL jars carry `@Metadata(mv = [2, 4, 0])`, so on the 2.2.10 compiler this file
 *   does not compile — the frontend rejects `composegl-ui` outright;
 * - without `org.jetbrains.kotlin.plugin.compose` on `udea-render`, [ComposeGlProbe] still
 *   compiles, and `remember` throws at the first composition. [greeting] is what catches that;
 * - with the plugin but no working runtime, a click would not recompose. [click] is what catches
 *   that: it reads the count back out of Compose's slot table after the state change.
 *
 * ## No GL, deliberately
 *
 * `HeadlessBackend` is ComposeGL's own no-context backend, so these run in the plain `test` task
 * rather than in `udeaGlTest`. That is the honest scope for this ticket: what #186 has to prove is
 * that the *toolchain* links, and requiring a driver to prove it would make the proof weaker
 * rather than stronger — on a box with no `DISPLAY`, `udeaGlTest` skips.
 */
class ComposeGlLinkTest {

    /** 320x240 is arbitrary and only has to be big enough for the three nodes to be placed. */
    private val size = Size(320f, 240f)

    @Test
    fun `composing the probe places a ComposeGL text node with the text it was given`() {
        uiTest(size, HeadlessBackend()) { ComposeGlProbe() }.use { ui ->
            ui.assertExists(ComposeGlProbeTags.GREETING)
            assertEquals(
                "HELLO FROM UDEA",
                ui.text(ComposeGlProbeTags.GREETING),
                "the composed tree should carry the literal the @Composable passed to Text",
            )
        }
    }

    @Test
    fun `clicking the probe's button recomposes the count through Compose's own state`() {
        uiTest(size, HeadlessBackend()) { ComposeGlProbe() }.use { ui ->
            assertEquals(
                "Clicked 0 times",
                ui.text(ComposeGlProbeTags.COUNT),
                "before any input the remembered state should be its initial value",
            )

            assertTrue(
                ui.click(ComposeGlProbeTags.BUTTON),
                "the tagged Button should be hit-testable in the placed tree",
            )
            ui.settle()

            assertEquals(
                "Clicked 1 times",
                ui.text(ComposeGlProbeTags.COUNT),
                "a click must reach onClick and the state change must recompose the text",
            )
        }
    }

    /**
     * The second dependency, `composegl-gdx`, resolved *and* linked.
     *
     * The two tests above exercise `composegl-ui` only, so on their own they would leave the GL
     * backend as a coordinate in a build file — and "a resolved dependency is not a compiling
     * one" is the whole reason #186 is a ticket. This loads `GdxBackend`, which forces the JVM to
     * resolve its supertypes and its constructor signature across three jars at once:
     * `composegl-gdx`, `composegl-ui` (for `UiBackend`) and libGDX (for `Batch` and
     * `Disposable`).
     *
     * `Batch` is read off the constructor rather than named in a variable on purpose. That is the
     * assertion that the gdx `composegl-gdx` was compiled against is the same gdx class
     * `udea-render` has: `composegl-gdx:0.5.0` requires `gdx:1.14.2`, and before this branch
     * moved the catalog to match, Gradle upgraded gdx core to 1.14.2 while leaving
     * `gdx-backend-lwjgl3` and the desktop natives at 1.13.5.
     *
     * It is not a render. Drawing a ComposeGL tree through `GdxCanvas` needs a live `Batch`, a
     * `GdxFonts` with a registered typeface, and therefore the gdx-freetype **natives** —
     * `composegl-gdx`'s POM brings `gdx-freetype` but no `gdx-freetype-platform` — plus somewhere
     * in the pipeline for the canvas to live. All of that is #187, and `BRIEF.md` says so rather
     * than a half-wired frame pretending otherwise.
     */
    @Test
    fun `the gdx backend links against this module's own libGDX and ComposeGL`() {
        assertTrue(
            UiBackend::class.java.isAssignableFrom(GdxBackend::class.java),
            "composegl-gdx's GdxBackend should satisfy composegl-ui's UiBackend; if it does not, " +
                "the two artefacts on this classpath are not a matched pair",
        )

        // Matched on the simple name, then required to be *this* module's class. That is the
        // sharp version: a `composegl-gdx` compiled against a different libGDX would still have
        // a parameter called `Batch`, and it would not be this one. Stated as a property rather
        // than "there are two of them", so adding a constructor overload upstream does not
        // falsify a sentence about a number.
        val namedBatch = GdxBackend::class.java.constructors
            .flatMap { it.parameterTypes.asList() }
            .filter { it.simpleName == "Batch" }
        assertTrue(
            namedBatch.isNotEmpty(),
            "GdxBackend takes no Batch at all, so composegl-gdx is not the libGDX backend this " +
                "test thinks it is: ${GdxBackend::class.java.constructors.toList()}",
        )
        assertEquals(
            emptyList(),
            namedBatch.filterNot { it == Batch::class.java },
            "every Batch GdxBackend takes must be the com.badlogic.gdx.graphics.g2d.Batch this " +
                "module compiles against. A different one means composegl-gdx and udea-render " +
                "resolved different libGDX versions.",
        )
    }
}
