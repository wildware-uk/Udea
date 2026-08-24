// The soldier. See `orc.udea.kts` for the state contract.
//
// `soldier_fire_arrow` keeps its `fire_arrow` notify on frame 8. That notify is what spawned the
// projectile in the old game - the arrow left the bow on the frame the string released, not on
// the frame the ability was cast - and it is the clearest reason a notify has to be a frame index
// in the art rather than a delay written into an ability.

val soldierScale = 1.58F

spriteAnimationSet(
    name = "soldier_animation_set",
    animations = listOf(
        reference("character/soldier_idle"),
        reference("character/soldier_walk"),
        reference("character/soldier_attack"),
        reference("character/soldier_hit"),
        reference("character/soldier_death"),
        reference("character/soldier_fire_arrow"),
    ),
)

spriteAnimation(name = "soldier_idle", sheet = reference("character/soldier_idle_sheet"))

spriteAnimation(name = "soldier_walk", sheet = reference("character/soldier_walk_sheet"))

spriteAnimation(
    name = "soldier_attack",
    sheet = reference("character/soldier_attack_sheet"),
    loop = false,
    notifies = mapOf("swoosh" to 3, "attack_hit" to 4),
)

spriteAnimation(
    name = "soldier_hit",
    sheet = reference("character/soldier_hit_sheet"),
    loop = false,
    interruptable = false,
)

spriteAnimation(
    name = "soldier_death",
    sheet = reference("character/soldier_death_sheet"),
    loop = false,
    interruptable = false,
)

spriteAnimation(
    name = "soldier_fire_arrow",
    sheet = reference("character/soldier_fire_arrow_sheet"),
    loop = false,
    notifies = mapOf("fire_arrow" to 8),
)

spriteSheet(
    name = "soldier_idle_sheet",
    spritePath = "sprites/soldier/Soldier-Idle.png",
    rows = 1,
    columns = 6,
    scale = soldierScale,
)

spriteSheet(
    name = "soldier_walk_sheet",
    spritePath = "sprites/soldier/Soldier-Walk.png",
    rows = 1,
    columns = 8,
    scale = soldierScale,
)

spriteSheet(
    name = "soldier_attack_sheet",
    spritePath = "sprites/soldier/Soldier-Attack01.png",
    rows = 1,
    columns = 6,
    scale = soldierScale,
)

spriteSheet(
    name = "soldier_fire_arrow_sheet",
    spritePath = "sprites/soldier/Soldier-Attack03.png",
    rows = 1,
    columns = 9,
    scale = soldierScale,
)

spriteSheet(
    name = "soldier_hit_sheet",
    spritePath = "sprites/soldier/Soldier-Hurt.png",
    rows = 1,
    columns = 4,
    scale = soldierScale,
)

spriteSheet(
    name = "soldier_death_sheet",
    spritePath = "sprites/soldier/Soldier-Death.png",
    rows = 1,
    columns = 4,
    scale = soldierScale,
)

// --- the gameplay half, which this root could not carry until `character` was a published kind
//
// `character(...)` was `AssetKind.Unpublishable`, so a declaration made with it packed as an
// opaque record with no runtime type, and `EntityDefinition.blueprint` - a `Ref<Blueprint>` -
// refused it. That is why this game had two asset roots: `moba/src/main/assets` held the migrated
// corpus with its `character(...)` calls and could not be packed, and this root held the half that
// could. `Character` is a `SpawnRecipe` now, so the two are one root and this is the half that
// came back.
//
// `size` is left at its default: this game's world scale is per *sheet* (`soldierScale` below),
// because the frames differ in how much transparent margin they carry, and a second scale on the
// character that no renderer reads would be decoration.

character(
    name = "soldier",
    health = 100F,
    spriteAnimationSet = reference("character/soldier_animation_set"),
    animationMap = mapOf(
        "idle" to reference("character/soldier_idle"),
        "walk" to reference("character/soldier_walk"),
        "attack" to reference("character/soldier_attack"),
        "hit" to reference("character/soldier_hit"),
        "death" to reference("character/soldier_death"),
        "shoot" to reference("character/soldier_fire_arrow"),
    ),
    sounds = mapOf(
        "attack" to reference("sounds/melee_swoosh"),
        "hit" to reference("sounds/hurt"),
        "death" to reference("sounds/death"),
        "shoot" to reference("sounds/arrow_fired"),
    ),
    attributes = mapOf(
        "health" to 100F,
        "strength" to 10F,
        "armour" to 50F,
    ),
    abilitySpecs = {
        abilitySpec(ability = reference("ability/npc_melee"), tags = listOf("Slot.A"))
        abilitySpec(ability = reference("ability/soldier_fire_arrow"), tags = listOf("Slot.B"))
    },
    components = {
        component("dev.wildware.moba.Position")
        component("dev.wildware.moba.level.GameUnit")
        component("dev.wildware.moba.CharacterView")
    },
)
