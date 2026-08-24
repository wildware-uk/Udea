package dev.wildware.udea.assets.compiler

import java.nio.file.Path

/**
 * The five-script fixture corpus (issues #86 and #87).
 *
 * Real files on disk under `src/test/resources/assets`, not strings built in a test: pass 2
 * compiles *files*, spans are repo-relative paths to files, and the transpiler rewrites files.
 * A corpus assembled in memory would exercise none of that.
 *
 * They are in the **new implicit-receiver form** — no `bundle { }`, no return value — which is
 * what distinguishes them from the nineteen legacy scripts `ExampleScanTest` reads. Between
 * them they cover: a script at the asset root, references in a `List` and in a `Map`, a file
 * constant, the sanctioned constant-list `forEach`, and a local helper function driving a
 * `repeat(n)` loop.
 */
internal object Fixtures {

    /** The fixture asset root. */
    val assetRoot: Path = TestPaths.repoRoot.resolve("udea-assets-compiler/src/test/resources/assets")

    /** Every fixture script, sorted, as [AssetCompiler.scriptsUnder] would return them. */
    fun scripts(): List<Path> = AssetCompiler.scriptsUnder(assetRoot)

    /**
     * Every id the corpus declares.
     *
     * Written out rather than derived so that a change in either front end that silently
     * drops or renames an asset is a failing test and not a quietly smaller graph.
     */
    val EXPECTED_IDS: Set<String> = setOf(
        "config",
        "character/orc",
        "character/orc_idle",
        "character/orc_idle_sheet",
        "character/orc_walk",
        "character/orc_walk_sheet",
        "character/orc_attack_cue",
        "character/orc_death_cue",
        "character/goblin",
        "character/goblin_dust",
        "character/goblin_idle",
        "character/goblin_idle_sheet",
        "character/goblin_spawn",
        "sounds/melee_hit",
        "sounds/melee_swoosh",
        "level/test_level",
        "level/spawner_0",
        "level/spawner_1",
    )
}
