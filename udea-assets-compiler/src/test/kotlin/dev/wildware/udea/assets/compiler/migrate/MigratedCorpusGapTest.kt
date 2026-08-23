package dev.wildware.udea.assets.compiler.migrate

import dev.wildware.udea.assets.compiler.AssetScope
import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.compiler.scan.UdeaDeclarationScanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.exists

/**
 * The two gaps the first cut of this migration left, pinned shut.
 *
 * ## What this used to say
 *
 * It measured a gap and asserted its size: the corpus used fourteen declaration kinds and the
 * receiver had six of them, and the scripts still carried thirty-four imports of `common` and
 * `example` game code. That was the honest statement at the time — "the corpus migrates" was
 * true, "the corpus validates" was not — and the test existed so that closing the gap would
 * fail on its own expected values and force somebody to come back and re-decide.
 *
 * ## What it says now
 *
 * Both halves are closed, so the assertions are inverted rather than deleted: every kind the
 * corpus declares is a member of [AssetScope], and no script imports game code from the old
 * tree. Deleting the test instead would leave nothing failing when either regresses, and a
 * regression here is invisible — a script that reaches back into `common` still *compiles*, in
 * a build where `common` happens to be on the script classpath, and only stops compiling for
 * the game that does not have it.
 *
 * `MigratedCorpusCompilesTest` is the other half: this one is about the vocabulary, that one
 * runs the real pipeline over the real files.
 */
class MigratedCorpusGapTest {

    private val root = TestPaths.repoRoot.resolve("moba/src/main/assets")

    /** Every declaration kind the corpus uses is a declaration function on the receiver. */
    @Test
    fun `every declaration kind the migrated corpus uses is a member of the receiver`() {
        assertTrue(
            root.exists(),
            "the migrated tree is missing; run `./gradlew :udea-assets-compiler:udeaMigrateAssets`",
        )
        val report = UdeaDeclarationScanner(TestPaths.repoRoot, root).use { it.scanTree() }
        assertEquals(19, report.files.size, "the migrated corpus is nineteen scripts")

        val kinds = report.declarations.map { it.kind }.toSortedSet()
        assertEquals(
            emptySet<String>(),
            kinds.filterNot { it in AssetScope.MEMBER_NAMES }.toSortedSet(),
            "the corpus declares a kind the receiver has no function for",
        )

        // Pinned, not merely derived: the point of the migration was that the receiver grew the
        // eight kinds it was missing, and a corpus that quietly stopped using one would make the
        // assertion above pass for the wrong reason.
        assertEquals(
            setOf(
                "ability",
                "axis2D",
                "axis2DBinding",
                "binding",
                "blueprint",
                "character",
                "control",
                "effect",
                "gameConfig",
                "gameplayEffect",
                "level",
                "soundCue",
                "spriteAnimation",
                "spriteAnimationSet",
                "spriteSheet",
            ),
            kinds.toSet(),
            "the set of kinds the corpus declares changed; re-check the migration claim",
        )
    }

    /**
     * No script reaches back into the old tree.
     *
     * `character(components = { networkable(); team(Team.OrcTeam) })` named ECS component
     * *functions* in `common` and `example`, which made every asset script a compile dependency
     * on the game — the thing that made an asset edit cost a Gradle build, and the reason `:moba`
     * (which depends on neither module) could not compile its own assets. A component is data
     * now: `component("dev.wildware.udea.ecs.component.base.Networkable")`, a type name in a
     * `ComponentSpec`, resolved by whoever instantiates it rather than by the script compiler.
     */
    @Test
    fun `the migrated corpus imports nothing at all`() {
        val imports = root.toFile().walkTopDown()
            .filter { it.isFile && it.name.endsWith(AssetTreeMigration.SCRIPT_SUFFIX) }
            .flatMap { file -> file.readLines().filter { it.startsWith("import ") } }
            .map { it.removePrefix("import ").trim() }
            .toSortedSet()
        assertEquals(
            emptySet<String>(),
            imports,
            "an asset script imports something; the corpus compiles against `AssetScope` alone",
        )
    }
}
