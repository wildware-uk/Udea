// What `:moba` actually draws, declared once and packed at build time.
//
// One root. There were two - this one and `moba/src/main/assets`, the mechanically migrated
// 19-script corpus of issue #93 - and the reason narrowed twice before it closed. It was never
// that the corpus failed to compile; it was that `character`, `gameplayEffect` and `effect` were
// `AssetKind.Unpublishable`, so packing the corpus dropped all 27 of `level/test_level`'s entity
// references and produced a bundle with a level that spawned nothing.
//
// Those three kinds have runtime types now, the corpus is merged into this root and the other one
// is deleted. `moba/build.gradle.kts`'s `udea { }` block names this directory and nothing else.

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
