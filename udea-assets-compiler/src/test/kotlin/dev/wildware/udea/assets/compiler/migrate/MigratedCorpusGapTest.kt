package dev.wildware.udea.assets.compiler.migrate

import dev.wildware.udea.assets.compiler.AssetScope
import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.compiler.scan.UdeaDeclarationScanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.exists

/**
 * What the migrated corpus still needs that the new receiver does not yet offer.
 *
 * ## This test exists to stop a claim being overstated
 *
 * "The 19 example scripts migrate" is true and is asserted next door. "The migrated corpus
 * *validates* against the new model" is **not** true, and would be easy to imply by only ever
 * asserting the first. The migration is a rewrite of spellings; `AssetScope` — the receiver the
 * new pipeline compiles scripts against — is still the provisional one issue #84 owns. The corpus
 * declares fourteen kinds and the receiver has a function for six of them, and even those six are
 * passed parameters it does not declare (`character(size = ..., attributeSet = ...)`).
 *
 * So this measures the gap and pins it. When #84's generated DSL lands and the gap closes, this
 * test fails on its own expected values and somebody has to come back and re-decide, which is
 * the behaviour a "known gap" note in a report does not have.
 */
class MigratedCorpusGapTest {

    /**
     * Every top-level declaration kind the migrated corpus uses, split by whether the current
     * receiver has a function of that name.
     */
    @Test
    fun `the migrated corpus uses declaration kinds the provisional receiver does not have`() {
        val root = TestPaths.repoRoot.resolve("moba/src/main/assets")
        assertTrue(
            root.exists(),
            "the migrated tree is missing; run `./gradlew :udea-assets-compiler:udeaMigrateAssets`",
        )
        val report = UdeaDeclarationScanner(TestPaths.repoRoot, root).use { it.scanTree() }
        assertEquals(19, report.files.size, "the migrated corpus is nineteen scripts")

        val kinds = report.declarations.map { it.kind }.toSortedSet()
        val supported = kinds.filter { it in AssetScope.MEMBER_NAMES }.toSortedSet()
        val unsupported = kinds.filterNot { it in AssetScope.MEMBER_NAMES }.toSortedSet()

        // Pinned, not described. A change in either direction is a change in what is honestly
        // claimable about the migration, and it should not pass silently.
        assertEquals(
            setOf("blueprint", "character", "gameConfig", "level", "soundCue", "spriteSheet"),
            supported.toSet(),
            "the receiver's coverage of the corpus changed; re-check the migration claim",
        )
        assertEquals(
            setOf(
                "ability",
                "axis2D",
                "axis2DBinding",
                "binding",
                "control",
                "effect",
                "gameplayEffect",
                "spriteAnimationSet",
            ),
            unsupported.toSet(),
            "the corpus's unsupported kinds changed; re-check the migration claim",
        )
    }

    /**
     * The corpus still imports game code from the old tree.
     *
     * Those imports are the honest boundary of what a *mechanical* migration can do: `character(
     * components = { networkable(); team(Team.OrcTeam) } )` names ECS component functions that
     * live in `common` and `example`, and porting them is a port of game source, which is other
     * epics' work. No rewrite of the asset DSL makes them resolve.
     */
    @Test
    fun `the migrated corpus still imports common and example game code`() {
        val root = TestPaths.repoRoot.resolve("moba/src/main/assets")
        val imports = root.toFile().walkTopDown()
            .filter { it.isFile && it.name.endsWith(AssetTreeMigration.SCRIPT_SUFFIX) }
            .flatMap { file -> file.readLines().filter { it.startsWith("import ") } }
            .map { it.removePrefix("import ").trim() }
            .toSortedSet()
        val foreign = imports.filter {
            it.startsWith("dev.wildware.udea.ecs.") ||
                it.startsWith("dev.wildware.udea.example.") ||
                it.startsWith("dev.wildware.udea.ability.")
        }
        assertTrue(
            foreign.isNotEmpty(),
            "the corpus no longer imports old-tree game code, so this test's premise is stale " +
                "and the migration claim can be strengthened. Imports: $imports",
        )
        assertEquals(
            34,
            foreign.size,
            "the number of old-tree imports changed:\n${foreign.joinToString("\n")}",
        )
    }
}
