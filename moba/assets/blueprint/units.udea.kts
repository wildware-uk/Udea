// The four units `level/test_level` puts on the field, ported from the old example game.
//
// Each id here is answered by a `UnitBlueprint` in `dev.wildware.moba.level.MobaBlueprints`, and
// the pairing is checked when the scene loads: an id the level names with no code blueprint
// behind it fails the swap by name rather than quietly spawning nothing.
//
// The component list is type names and no field values, exactly as `blueprint/grunt` was before
// it. That is what the packed graph can carry today: turning authored component *fields* into
// live Fleks components needs a name-to-`ComponentType` registry in the engine, and until that
// exists a blueprint with authored stats would be data no loader reads. A unit's stats live in
// `UnitKind`, and the art it wears is found by name in the `character/` roster.
//
// `blueprint/grunt` is gone with this file. It was the one thing this game could spawn, and
// nothing named it once the roster arrived.

val unitComponents = listOf(
    "dev.wildware.moba.Position",
    "dev.wildware.moba.level.GameUnit",
    "dev.wildware.moba.CharacterView",
)

blueprint(name = "soldier", components = unitComponents)

blueprint(name = "priest", components = unitComponents)

blueprint(name = "orc", components = unitComponents)

blueprint(name = "skeleton", components = unitComponents)
