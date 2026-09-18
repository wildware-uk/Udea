// The orc, ported from `example/.../assets/character/orc.udea.kts` onto the packed pipeline.
//
// ## The state contract, and the two places it now lives
//
// `SpriteAnimationSet` is an ordered list with no keys, so "which of these is the walk" has to be
// carried somewhere. It was carried in the **id suffix** - `character/orc_walk` is the `walk`
// state of `orc` - because `character(animationMap = ...)`, which says it directly, was
// `AssetKind.Unpublishable` and packed as a record no reader could turn into an asset.
//
// `Character` exists now, so the map at the bottom of this file is the authoritative statement and
// `MobaCharacters` keeps resolving by suffix. Two statements of one fact is a thing to be checked
// rather than trusted, so `MobaAuthoredContentTest` compares them: the role map and the suffix
// convention agree, or the build is red.

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

// --- the gameplay half, which this root could not carry until `character` was a published kind
//
// `character(...)` was `AssetKind.Unpublishable`, so a declaration made with it packed as an
// opaque record with no runtime type, and `EntityDefinition.blueprint` - a `Ref<Blueprint>` -
// refused it. That is why this game had two asset roots: `moba/src/main/assets` held the migrated
// corpus with its `character(...)` calls and could not be packed, and this root held the half that
// could. `Character` is a `SpawnRecipe` now, so the two are one root and this is the half that
// came back.
//
// `size` is left at its default: this game's world scale is per *sheet* (`orcScale` below),
// because the frames differ in how much transparent margin they carry, and a second scale on the
// character that no renderer reads would be decoration.

character(
    name = "orc",
    health = 150F,
    spriteAnimationSet = reference("character/orc_animation_set"),
    animationMap = mapOf(
        "idle" to reference("character/orc_idle"),
        "walk" to reference("character/orc_walk"),
        "attack" to reference("character/orc_attack"),
        "hit" to reference("character/orc_hit"),
        "death" to reference("character/orc_death"),
    ),
    sounds = mapOf(
        "attack" to reference("sounds/melee_swoosh"),
        "hit" to reference("sounds/hurt"),
        "death" to reference("sounds/death"),
    ),
    attributes = mapOf(
        "health" to 150F,
        "strength" to 10F,
        "magicResist" to 20F,
    ),
    abilitySpecs = {
        abilitySpec(ability = reference("ability/npc_melee"), tags = listOf("Slot.A"))
    },
    components = {
        component("dev.wildware.moba.Position")
        component("dev.wildware.moba.level.GameUnit")
        component("dev.wildware.moba.CharacterView")
    },
)
