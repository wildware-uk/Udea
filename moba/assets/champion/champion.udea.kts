// What `:moba` actually draws, declared once and packed at build time.
//
// This root is deliberately small and deliberately separate from `moba/src/main/assets`, the
// mechanically migrated 19-script corpus of issue #93. That corpus *does* compile and validate
// now - `MigratedCorpusCompilesTest` drives all 19 with zero errors - so the reason for the
// split is no longer compilation. It is packing: `character`, `gameplayEffect` and `effect` are
// still `AssetKind.Unpublishable`, so `level/test_level` packs its 27 entities without their
// blueprints, and a game cannot spawn from a level like that.
//
// This root packs completely, so it is what the pipeline runs over and what the game loads. See
// `moba/build.gradle.kts`'s `udea { }` block for the same statement in full.

/**
 * World units per pixel.
 *
 * `0.53125` because the placeholder frames are 64px square and `MobaScene.WORLD_HEIGHT` is 80
 * world units: 64 x 0.53125 is 34, which is the size the hand-written renderer drew a champion
 * at before the pipeline existed, so the picture is comparable across the change.
 *
 * It is also the number the Phase 2 hot-reload proof moves. `ChampionRenderSystem` reads it out
 * of the live `AssetRegistry` on every frame, so an `assets.patch` that changes this line changes
 * the size of every champion in the next capture - through the simulation's own asset graph, not
 * through anything the agent surface can write directly.
 */
val championScale = 0.53125F

spriteSheet(
    name = "idle_sheet",
    spritePath = "sprites/champion_idle.png",
    rows = 1,
    columns = 6,
    scale = championScale,
)

spriteAnimation(name = "idle", sheet = reference("champion/idle_sheet"))
