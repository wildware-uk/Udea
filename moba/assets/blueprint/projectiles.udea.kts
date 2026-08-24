// The two entities this game spawns that are not units: an arrow in flight, and a flash.
//
// Migrated from `blueprint/arrow.udea.kts` and `blueprint/effect.udea.kts` in the corpus that
// used to live under `moba/src/main/assets`. Both name ids that `dev.wildware.moba` already
// answers to in code - `ArrowBlueprint` is `BlueprintId("blueprint/arrow")` and
// `EffectBlueprint` is `BlueprintId("blueprint/effect")` - so the ids were live and the
// declarations were in an unpacked tree.
//
// ## What is authored here and what is not
//
// The component *list*, which is data the pack format carries, and not the component *fields*.
// The source corpus wrote `Box(width = 0.2F, height = 0.1F, isSensor = true)` over a Box2D
// kinematic body and three `onHitEffects` records; `ArrowBlueprint` writes `Projectile(stunTicks,
// knockback)` in Kotlin. Turning an authored `ComponentSpec` field map into a live Fleks component
// needs a name-to-`ComponentType` registry in the engine, which is a piece of engine and not a
// piece of a level - so the fields would be data no loader reads. The list is the half that is
// true today, and `MobaAuthoredContentTest` checks each name against a class that exists rather
// than letting a rename rot it.

blueprint(
    name = "arrow",
    components = listOf(
        "dev.wildware.moba.Position",
        "dev.wildware.moba.ability.Motion",
        "dev.wildware.moba.ability.Projectile",
        "dev.wildware.moba.SpriteView",
    ),
)

blueprint(
    name = "effect",
    components = listOf(
        "dev.wildware.moba.Position",
        "dev.wildware.moba.SpriteView",
    ),
)
