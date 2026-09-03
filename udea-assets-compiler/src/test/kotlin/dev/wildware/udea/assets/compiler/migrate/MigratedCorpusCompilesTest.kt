package dev.wildware.udea.assets.compiler.migrate

import dev.wildware.udea.assets.compiler.AssetCompiler
import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.compiler.scan.UdeaDeclarationScanner
import dev.wildware.udea.assets.compiler.validate.AssetValidatorPipeline
import dev.wildware.udea.assets.compiler.validate.ValidationContext
import dev.wildware.udea.diagnostics.Severity
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        // The list and not a count. What this pins is that the walk found the game's real tree
        // rather than an empty scratch directory, and a *list* says which files it expects: adding
        // a script adds a line here, where a count would simply contradict itself. The number was
        // 19 when this was written and 22 when the shop's three scripts landed, which is exactly
        // the drift a count cannot survive and a list makes trivial.
        assertEquals(
            listOf(
                "ability/gameplay_effects.udea.kts",
                "ability/npc_melee.udea.kts",
                "ability/orc_elite_abilities.udea.kts",
                "ability/priest_abilities.udea.kts",
                "ability/soldier_abilities.udea.kts",
                "blueprint/projectiles.udea.kts",
                "champion/champion.udea.kts",
                "character/orc.udea.kts",
                "character/orc_elite.udea.kts",
                "character/priest.udea.kts",
                "character/skeleton.udea.kts",
                "character/soldier.udea.kts",
                "character/wizard.udea.kts",
                "config.udea.kts",
                "control/controls.udea.kts",
                "effects/effects.udea.kts",
                "item/components.udea.kts",
                "item/finished.udea.kts",
                "item/stats.udea.kts",
                "item/trinkets.udea.kts",
                "level/test_level.udea.kts",
                "sounds/sounds.udea.kts",
                "sprites/arrow/arrow.udea.kts",
            ),
            scripts.map { root.relativize(it).toString().replace('\\', '/') }.sorted(),
            "the game's asset root is not the tree this test was written against",
        )

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
        // Not an empty graph validated to a clean bill of health, and every declaration distinct.
        //
        // The second assertion is the one that pins the duplicate-id defect the migration
        // surfaced - in the source corpus a sprite sheet and the animation that plays it were
        // both named `orc_idle`, so both declared `character/orc_idle` and the old two-key loader
        // silently kept whichever it saw last. `AssetGraph` is keyed by id, so a collision shows
        // up as a graph smaller than the declaration list.
        //
        // There used to be an `assertEquals(127, compiled.graph.assets.size)` above it, and it is
        // gone rather than renumbered. It guarded nothing the two assertions below do not: the
        // collision it was written for is caught exactly by comparing the graph against the
        // declaration list, without a number that every content change has to be told about.
        assertTrue(compiled.declared.isNotEmpty(), "the corpus must actually declare something")
        assertEquals(
            compiled.declared.size,
            compiled.graph.assets.size,
            "two declarations share an id: " +
                compiled.declared.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys,
        )
    }
}
