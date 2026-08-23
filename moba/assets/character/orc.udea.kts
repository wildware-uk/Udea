// The orc, ported from `example/.../assets/character/orc.udea.kts` onto the packed pipeline.
//
// ## Why there is no `character(...)` call here
//
// The old script's `character(...)` carried the animation map, the sounds, the attributes, the
// ability specs and the component list in one declaration. `AssetKind.Unpublishable` still says
// `character` has no runtime type, so a `character(...)` in **this** root would pack as a record
// the bundle reader cannot turn into an asset - which is exactly why `moba/src/main/assets` is a
// corpus that compiles and a bundle that cannot be loaded. Closing that is issue #84's.
//
// What a renderer needs in order to make an orc appear and animate is the half that *is*
// publishable: sheets, animations, and the set that groups them. That half is here and it packs,
// so the picture is real. The gameplay half is not stubbed here - it is absent, and named as
// absent rather than half-written.
//
// ## The state contract
//
// `SpriteAnimationSet` is an ordered list with no keys, so "which of these is the walk" has to be
// carried somewhere. It is carried in the **id suffix**: `character/orc_walk` is the `walk` state
// of `orc`. `MobaCharacters` resolves states that way and fails loudly on a missing one, which is
// the same information `character(animationMap = ...)` held, in the one place the pack preserves.

val orcScale = 1.88F

spriteAnimationSet(
    name = "orc_animation_set",
    animations = listOf(
        reference("character/orc_idle"),
        reference("character/orc_walk"),
        reference("character/orc_attack"),
        reference("character/orc_hit"),
        reference("character/orc_death"),
    ),
)

spriteAnimation(name = "orc_idle", sheet = reference("character/orc_idle_sheet"))

spriteAnimation(name = "orc_walk", sheet = reference("character/orc_walk_sheet"))

// `swoosh` is the wind-up and `attack_hit` is the frame the axe connects on. Both are carried
// through to the renderer's notify sink; the old game timed damage off `attack_hit`.
spriteAnimation(
    name = "orc_attack",
    sheet = reference("character/orc_attack_sheet"),
    loop = false,
    notifies = mapOf("swoosh" to 3, "attack_hit" to 4),
)

spriteAnimation(
    name = "orc_hit",
    sheet = reference("character/orc_hit_sheet"),
    loop = false,
    interruptable = false,
)

spriteAnimation(
    name = "orc_death",
    sheet = reference("character/orc_death_sheet"),
    loop = false,
    interruptable = false,
)

spriteSheet(name = "orc_idle_sheet", spritePath = "sprites/orc/Orc-Idle.png", rows = 1, columns = 6, scale = orcScale)
spriteSheet(name = "orc_walk_sheet", spritePath = "sprites/orc/Orc-Walk.png", rows = 1, columns = 8, scale = orcScale)
spriteSheet(name = "orc_attack_sheet", spritePath = "sprites/orc/Orc-Attack01.png", rows = 1, columns = 6, scale = orcScale)
spriteSheet(name = "orc_hit_sheet", spritePath = "sprites/orc/Orc-Hurt.png", rows = 1, columns = 4, scale = orcScale)
spriteSheet(name = "orc_death_sheet", spritePath = "sprites/orc/Orc-Death.png", rows = 1, columns = 4, scale = orcScale)
