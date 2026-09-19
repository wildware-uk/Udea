package dev.wildware.udea.render.headless

import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.render.bytecode.BannedOwner
import dev.wildware.udea.render.bytecode.GL_BANNED_OWNERS
import dev.wildware.udea.render.support.RepoLayout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * That the scan behind `udeaVerifyHeadless` actually detects what it claims to.
 *
 * The gate itself ([UdeaVerifyHeadlessTest]) asserts an *absence*, and an absence is exactly
 * what a broken scanner also reports. So the detection half is proven here against fixtures
 * whose compiled form is known: [GlNamingFixture] must be caught, [ValueNamingFixture]
 * must not, and an empty scan must fail rather than pass.
 */
class HeadlessScanTest {

    @Test
    fun `a class naming a GL type is reported with its class, its member and the banned owner`() {
        val violations = HeadlessScan.violations(MODULE, fixtureClasses("GlNamingFixture"))

        val diagnostic = violations.firstOrNull { "maxTextureSize" in it.message }
        assertNotNull(diagnostic, "the scan found no reference to GL11 at all: $violations")
        val message = diagnostic.message
        assertTrue(GlNamingFixture::class.java.name in message, message)
        assertTrue("org/lwjgl/opengl/GL11" in message, message)
        assertEquals(Severity.Error, diagnostic.severity)
        assertEquals(HeadlessScan.RULE_ID, diagnostic.ruleId)
    }

    @Test
    fun `the message says it extends UDEA-MG-002 rather than restating it`() {
        val diagnostic = HeadlessScan.violations(MODULE, fixtureClasses("GlNamingFixture")).first()

        // Ownership (issue #117): the configuration-level rule belongs to
        // udeaVerifyModuleGraph. This gate is its bytecode extension and says so, so that a
        // reader who follows the id lands on one rule and not two rival ones.
        assertTrue("UDEA-MG-002" in diagnostic.message, diagnostic.message)
        assertTrue("udeaVerifyModuleGraph" in diagnostic.message, diagnostic.message)
        assertEquals("UDEA-MG-002-BYTECODE", diagnostic.ruleId)
    }

    @Test
    fun `the violation carries a repo-relative source span pointing at the offending line`() {
        // The instruction-level reference, which is the one that carries a line number; a
        // reference that only appears in a signature has no instruction to point at.
        val diagnostic = HeadlessScan.violations(MODULE, fixtureClasses("GlNamingFixture"))
            .first { (it.span?.startLine ?: 0) > 0 }

        val span = diagnostic.span
        assertNotNull(span, "a violation with no location is a grep task, not a diagnostic")
        assertEquals("udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/headless/GlFixtures.kt", span.path)
        assertTrue(span.startLine > 0, "line ${span.startLine}")

        // The span must actually point at the offending source, not merely at the file.
        val line = RepoLayout.repoRoot.resolve(span.path).readLines()[span.startLine - 1]
        assertTrue("maxTextureSize" in line, "span pointed at: $line")
    }

    @Test
    fun `a class naming only engine value types is not reported`() {
        val violations = HeadlessScan.violations(MODULE, fixtureClasses("ValueNamingFixture"))

        assertEquals(emptyList(), violations.map { it.message })
    }

    @Test
    fun `the same class passes the gate once it lives in udea-render`() {
        // Both fixtures are compiled *in udea-render*, and udea-render is not a module the
        // gate scans. That is the entire remedy for a violation: move the code here.
        assertTrue("udea-render" !in HeadlessScan.HEADLESS_MODULES, "${HeadlessScan.HEADLESS_MODULES}")

        val report = HeadlessScan.run()

        assertEquals(
            emptyList(),
            report.diagnostics.filter { GlNamingFixture::class.java.name in it.message },
        )
    }

    /**
     * The FBX converter's allowance (issue #244), against the asset compiler's real bytecode: it
     * does name Assimp, which is why the allowance exists, and the allowance clears exactly that.
     */
    @Test
    fun `the asset compiler names Assimp, and only there is Assimp excused`() {
        val converter = RepoLayout.classFiles("udea-assets-compiler")
            .filter { "/model/FbxConverter" in it.invariantSeparatorsPath }
        assertTrue(converter.isNotEmpty(), "the FBX converter's classes were not found; the test would scan nothing")

        val unexcused = HeadlessScan.violations("udea-assets-compiler", converter)
        assertTrue(unexcused.any { "org/lwjgl/assimp/" in it.message }, "the converter names no Assimp type: $unexcused")

        assertEquals(listOf("org/lwjgl/assimp/"), HeadlessScan.EXCUSED["udea-assets-compiler"])
        assertEquals(
            emptyList(),
            HeadlessScan.violations("udea-assets-compiler", converter, excused = HeadlessScan.EXCUSED.getValue("udea-assets-compiler"))
                .map { it.message },
        )
    }

    @Test
    fun `the Assimp allowance does not excuse GL, and no other module has it`() {
        val excused = HeadlessScan.EXCUSED.getValue("udea-assets-compiler")
        val violations = HeadlessScan.violations(MODULE, fixtureClasses("GlNamingFixture"), excused = excused)
        assertTrue(violations.any { "org/lwjgl/opengl/GL11" in it.message }, "$violations")

        assertEquals(listOf("udea-assets-compiler", "udea-gradle"), HeadlessScan.EXCUSED.keys.sorted())
    }

    @Test
    fun `a module that contributed no classes fails the gate rather than passing vacuously`() {
        val failure = assertFailsWith<IllegalStateException> {
            HeadlessScan.run(modules = listOf("udea-core"), classFilesOf = { emptyList() })
        }

        assertTrue("broken" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `every banned owner in the table is matched by the scan`() {
        // Guards against an entry that can never fire -- a typo in a pattern, or a package
        // that moved. Each entry is checked against a name it must match.
        // LibGDX is `LIBGDX_BANNED_OWNERS`, read over every module by `LibGdxScan` (issue #189).
        val samples = mapOf(
            "org/lwjgl/" to "org/lwjgl/glfw/GLFW",
        )

        assertEquals(samples.keys.sorted(), GL_BANNED_OWNERS.map { it.pattern }.sorted())
        for ((pattern, sample) in samples) {
            val entry = GL_BANNED_OWNERS.first { it.pattern == pattern }
            assertTrue(entry.matches(sample), "$pattern did not match $sample")
        }
    }

    @Test
    fun `a class entry matches nested classes but not siblings that merely share a prefix`() {
        // The table itself holds only package entries since issue #213, so the single-class
        // semantics are held on an entry built here: the rule is `BannedOwner`'s, not the table's.
        val gl = BannedOwner("org/lwjgl/opengl/GL", "a single class")

        assertTrue(gl.matches("org/lwjgl/opengl/GL"))
        assertTrue(gl.matches("org/lwjgl/opengl/GL\$Companion"))
        // A sibling sharing the prefix is a different class, and a naive startsWith would ban it.
        assertTrue(!gl.matches("org/lwjgl/opengl/GL11"))
        assertTrue(!gl.matches("org/lwjgl/opengl/GLCapabilities"))
    }

    @Test
    fun `a banned owner spelled as a source name is rejected when the table is built`() {
        // Internal names are `/`-separated. A dotted entry would silently match nothing.
        assertFailsWith<IllegalArgumentException> {
            BannedOwner("com.badlogic.gdx.graphics.Texture", "dotted, and therefore inert")
        }
    }

    /** The compiled forms of one fixture class, including any nested or synthetic classes. */
    private fun fixtureClasses(simpleName: String): List<File> =
        RepoLayout.classFiles(MODULE, "test").filter { it.name.startsWith(simpleName) }
            .also { check(it.isNotEmpty()) { "no compiled class found for $simpleName" } }

    private companion object {
        /**
         * The fixtures live in `udea-render`; the scan is *told* it is looking at that module
         * so the message and the span come out the way they would for a real violation.
         */
        const val MODULE = "udea-render"
    }
}
