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

// The two the roster packed art for and no level could ever spawn.
//
// `character/orc_elite_animation_set` and `character/wizard_animation_set` were declared, packed
// and cut into the atlas from the first day the art tree landed, and nothing named them: there
// was no `blueprint/orc_elite` and no `blueprint/wizard`, so `MobaBlueprints` had nothing to
// answer an authored id with and `level/test_level` could not have written one. Two of the six
// characters were dead weight in the bundle, and `OrcSpinExec` - a registered exec with an
// eleven-frame sheet behind it and a `TargetPolicy` pointing at it - was unreachable code in a
// shipped game, because the only unit whose `MobaUnits` entry grants `ability/orc_elite_spin` is
// the elite orc.
//
// The old game's `blueprint/player` inherited `orc_elite`, so a human drove the elite with the
// spin. `level/test_level` names `blueprint/orc_elite` for its `player` entity for that reason.
blueprint(name = "orc_elite", components = unitComponents)

blueprint(name = "wizard", components = unitComponents)
