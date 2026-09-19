// The root of the eager set. `BundleContent.reachable` walks from `gameConfig` to decide which
// blobs load at launch and which stream, so a bundle without one streams everything and the
// first frame waits on a disk read it did not need to.
//
// There is no `defaultLevel`. It used to name `level/test_level`, and through it the six
// characters, which made the whole roster eager. The level this game plays is a saved
// `.udealevel` file now (`moba/game/levels/`, issue #192), not a packed asset, so there is nothing
// in the bundle for the slot to name. `MobaAssets` reads the whole bundle into memory from the
// classpath, so a streamed section is a read from that array rather than from the disk.
//
// `defaultCharacter` names a `character/` rather than the `blueprint/soldier` stand-in that is
// gone with `blueprint/units.udea.kts`. The slot is a `Ref<SpawnRecipe>`, which both kinds are.
gameConfig(
    defaultCharacter = reference("character/soldier"),
)
