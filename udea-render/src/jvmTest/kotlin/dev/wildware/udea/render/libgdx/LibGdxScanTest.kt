package dev.wildware.udea.render.libgdx

import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.render.bytecode.GL_BANNED_OWNERS
import dev.wildware.udea.render.bytecode.LIBGDX_BANNED_OWNERS
import dev.wildware.udea.render.support.RepoLayout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * That the scan behind `udeaVerifyNoLibGdx` detects what it claims to (issue #189).
 *
 * The gate ([UdeaVerifyNoLibGdxTest]) asserts an absence, which a broken scanner also reports, so
 * detection is proven here against fixtures whose compiled form is known. Both positive fixtures
 * name LibGDX classes that were compiled from source in this repository rather than resolved from
 * an artifact: the case `UDEA-MG-009` cannot see and this gate exists for.
 */
class LibGdxScanTest {

    @Test
    fun `a class naming scene2d is reported with the scene2d reason under the bytecode id of UDEA-MG-009`() {
        val violations = LibGdxScan.violations(MODULE, fixtureClasses("Scene2dNamingFixture"))

        val diagnostic = violations.firstOrNull { "actOnce" in it.message }
        assertNotNull(diagnostic, "the scan found no reference to scene2d at all: $violations")
        val message = diagnostic.message
        assertTrue(Scene2dNamingFixture::class.java.name in message, message)
        assertTrue("com/badlogic/gdx/scenes/scene2d/Stage" in message, message)
        assertTrue("ComposeGL" in message, "the scene2d entry's own reason is missing: $message")
        assertEquals(Severity.Error, diagnostic.severity)
        assertEquals("UDEA-MG-009-BYTECODE", diagnostic.ruleId)
    }

    @Test
    fun `the message names the dependency-level rule it extends`() {
        val message = LibGdxScan.violations(MODULE, fixtureClasses("Scene2dNamingFixture")).first().message

        assertTrue("UDEA-MG-009" in message, message)
        assertTrue("udeaVerifyModuleGraph" in message, message)
    }

    @Test
    fun `LibGDX outside scene2d is reported with the general reason, not the scene2d one`() {
        val violations = LibGdxScan.violations(MODULE, fixtureClasses("GdxNamingFixture"))

        val diagnostic = violations.firstOrNull { "clampToUnit" in it.message }
        assertNotNull(diagnostic, "the scan found no reference to gdx math at all: $violations")
        assertTrue("com/badlogic/gdx/math/MathUtils" in diagnostic.message, diagnostic.message)
        assertTrue("ComposeGL" !in diagnostic.message, "matched the scene2d entry: ${diagnostic.message}")
        assertTrue("#213" in diagnostic.message, diagnostic.message)
    }

    @Test
    fun `a class naming only engine value types is not reported`() {
        val clean = RepoLayout.classFiles(MODULE, "test").filter { it.name.startsWith("ValueNamingFixture") }
        check(clean.isNotEmpty()) { "no compiled ValueNamingFixture" }

        assertEquals(emptyList(), LibGdxScan.violations(MODULE, clean).map { it.message })
    }

    @Test
    fun `a module that contributed no classes fails the gate rather than passing vacuously`() {
        val failure = assertFailsWith<IllegalStateException> {
            LibGdxScan.run(modules = listOf("moba/game"), classFilesOf = { emptyList() })
        }

        assertTrue("broken" in failure.message.orEmpty(), failure.message.orEmpty())
        assertTrue("udeaVerifyNoLibGdx" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `every banned owner in the table is matched by the scan`() {
        val samples = mapOf(
            "com/badlogic/gdx/scenes/scene2d/" to "com/badlogic/gdx/scenes/scene2d/ui/Table",
            "com/badlogic/" to "com/badlogic/gdx/graphics/Texture",
            "box2dLight/" to "box2dLight/RayHandler",
        )

        assertEquals(samples.keys.sorted(), LIBGDX_BANNED_OWNERS.map { it.pattern }.sorted())
        for ((pattern, sample) in samples) {
            val entry = LIBGDX_BANNED_OWNERS.first { it.pattern == pattern }
            assertTrue(entry.matches(sample), "$pattern did not match $sample")
        }
    }

    @Test
    fun `no owner is banned by both the LibGDX table and the headless GL table`() {
        // The dependency level splits the same way: UDEA-MG-002 does not name LibGDX, because
        // UDEA-MG-009 bans it everywhere. One reference, one diagnostic.
        val shared = GL_BANNED_OWNERS.map { it.pattern }.filter { gl ->
            LIBGDX_BANNED_OWNERS.any { it.matches(gl) || gl.startsWith(it.pattern) || it.pattern.startsWith(gl) }
        }

        assertEquals(emptyList(), shared)
    }

    /** The compiled forms of one fixture class, including any nested or synthetic classes. */
    private fun fixtureClasses(simpleName: String): List<File> =
        RepoLayout.classFiles(MODULE, "test").filter { it.name.startsWith(simpleName) }
            .also { check(it.isNotEmpty()) { "no compiled class found for $simpleName" } }

    private companion object {
        /** The fixtures are compiled in `udea-render`; the scan is told so. */
        const val MODULE = "udea-render"
    }
}
