package dev.wildware.udea.assets.compiler.migrate

import dev.wildware.udea.assets.compiler.AssetCompiler
import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.compiler.scan.UdeaDeclarationScanner
import dev.wildware.udea.assets.compiler.validate.AssetValidatorPipeline
import dev.wildware.udea.assets.compiler.validate.ValidationContext
import dev.wildware.udea.diagnostics.Severity
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The migrated corpus compiles and validates against the new pipeline, end to end.
 *
 * ## The corpus is the game's one asset root now
 *
 * It used to be `moba/src/main/assets`, a tree nothing packed and nothing shipped, sitting beside
 * `moba/assets` - the smaller hand-reduced root the build actually pointed at. Two roots, and the
 * reason was never that the corpus failed to compile: it was that `character`, `gameplayEffect`
 * and `effect` were `AssetKind.Unpublishable`, so packing the corpus dropped all twenty-seven of
 * `level/test_level`'s entity references and produced a bundle with a level that spawned nothing.
 * With those three kinds published the two roots are one, `moba/src/main/assets` is deleted, and
 * this test runs over the tree `:moba:udeaPackBundle` packs and `MobaGame` boots.
 *
 * That is what makes it a live check rather than a museum piece: it now fails when somebody
 * breaks the running game's assets, not only when somebody breaks a corpus nobody loads.
 *
 * This is the acceptance criterion of issue #93 and the thing a "the migration is done" claim
 * has to mean. It is deliberately not a spelling check: it runs pass 1 (PSI scan), pass 2
 * (compile and evaluate every script against [dev.wildware.udea.assets.compiler.AssetScope])
 * and pass 3 (every validator) over the real tree, and fails on the first error diagnostic any
 * of them produce - a script that does not compile, a `reference` nothing declares, a PNG that
 * is not there, a grid that does not divide the art.
 */
class MigratedCorpusCompilesTest {

    private val root = TestPaths.repoRoot.resolve("moba/assets")

    @Test
    fun `every migrated script compiles and validates with zero errors`() {
        val scripts = AssetCompiler.scriptsUnder(root)
        assertEquals(19, scripts.size, "the game's asset root is nineteen scripts")

        val scan = UdeaDeclarationScanner(TestPaths.repoRoot, root).use { it.scanTree() }
        val compiler = AssetCompiler(
            repoRoot = TestPaths.repoRoot,
            assetRoot = root,
            scriptClasspath = TestPaths.compilerClasspath,
            cacheDirectory = TestPaths.scratch("migrated-corpus-cache"),
        )
        val compiled = compiler.compile(scripts, scan.referenceSpanIndex())
        val compileErrors = compiled.diagnostics.filter { it.severity == Severity.Error }
        assertEquals(emptyList(), compileErrors, "the migrated corpus must compile")

        val report = AssetValidatorPipeline().validate(
            ValidationContext(
                declared = compiled.declared,
                repoRoot = TestPaths.repoRoot,
                assetRoot = root,
                declarations = scan.declarations,
                sources = scripts,
            ),
        )
        assertEquals(
            emptyList(),
            report.diagnostics.filter { it.severity == Severity.Error },
            "the migrated corpus must validate",
        )
        // Not an empty graph validated to a clean bill of health: 127 assets, and every one of
        // them distinct. The second assertion is the one that pins the duplicate-id defect the
        // migration surfaced - in the source corpus a sprite sheet and the animation that plays
        // it were both named `orc_idle`, so both declared `character/orc_idle` and the old
        // two-key loader silently kept whichever it saw last. `AssetGraph` is keyed by id, so a
        // collision shows up here as a graph smaller than the declaration list.
        assertEquals(127, compiled.graph.assets.size, "the corpus declares 127 assets")
        assertEquals(
            compiled.declared.size,
            compiled.graph.assets.size,
            "two declarations share an id: " +
                compiled.declared.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys,
        )
    }
}
