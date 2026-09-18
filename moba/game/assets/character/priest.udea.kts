// The priest. See `orc.udea.kts` for the state contract.
//
// `priest_heal` is kept even though nothing in this root heals yet: it is the one animation in
// the six whose notify (`heal`, frame 4) is not a weapon connecting, so it is what shows the
// notify channel is a general event and not an "attack landed" flag wearing a name.

val priestScale = 1.43F

spriteAnimationSet(
    name = "priest_animation_set",
    animations = listOf(
        reference("character/priest_idle"),
        reference("character/priest_walk"),
        reference("character/priest_attack"),
        reference("character/priest_hit"),
        reference("character/priest_death"),
        reference("character/priest_heal"),
    ),
)

spriteAnimation(name = "priest_idle", sheet = reference("character/priest_idle_sheet"))

spriteAnimation(name = "priest_walk", sheet = reference("character/priest_walk_sheet"))

spriteAnimation(
    name = "priest_attack",
    sheet = reference("character/priest_attack_sheet"),
    loop = false,
    notifies = mapOf("swoosh" to 4, "attack_hit" to 5),
)

spriteAnimation(
    name = "priest_hit",
    sheet = reference("character/priest_hit_sheet"),
    loop = false,
    interruptable = false,
)

spriteAnimation(
    name = "priest_death",
    sheet = reference("character/priest_death_sheet"),
    loop = false,
    interruptable = false,
)

spriteAnimation(
    name = "priest_heal",
    sheet = reference("character/priest_heal_sheet"),
    loop = false,
    notifies = mapOf("heal" to 4),
)

spriteSheet(
    name = "priest_idle_sheet",
    spritePath = "sprites/priest/Priest-Idle.png",
    rows = 1,
    columns = 6,
    scale = priestScale,
)

spriteSheet(
    name = "priest_walk_sheet",
    spritePath = "sprites/priest/Priest-Walk.png",
    rows = 1,
    columns = 8,
    scale = priestScale,
)

spriteSheet(
    name = "priest_attack_sheet",
    spritePath = "sprites/priest/Priest-Attack.png",
    rows = 1,
    columns = 9,
    scale = priestScale,
)

spriteSheet(
    name = "priest_heal_sheet",
    spritePath = "sprites/priest/Priest-Heal.png",
    rows = 1,
    columns = 6,
    scale = priestScale,
)

spriteSheet(
    name = "priest_hit_sheet",
    spritePath = "sprites/priest/Priest-Hurt.png",
    rows = 1,
    columns = 4,
    scale = priestScale,
)

spriteSheet(
    name = "priest_death_sheet",
    spritePath = "sprites/priest/Priest-Death.png",
    rows = 1,
    columns = 4,
    scale = priestScale,
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
// `size` is left at its default: this game's world scale is per *sheet* (`priestScale` below),
// because the frames differ in how much transparent margin they carry, and a second scale on the
// character that no renderer reads would be decoration.

character(
    name = "priest",
    health = 50F,
    spriteAnimationSet = reference("character/priest_animation_set"),
    animationMap = mapOf(
        "idle" to reference("character/priest_idle"),
        "walk" to reference("character/priest_walk"),
        "attack" to reference("character/priest_attack"),
        "hit" to reference("character/priest_hit"),
        "death" to reference("character/priest_death"),
        "heal" to reference("character/priest_heal"),
    ),
    sounds = mapOf(
        "attack" to reference("sounds/melee_swoosh"),
        "hit" to reference("sounds/hurt"),
        "death" to reference("sounds/death"),
        "heal" to reference("sounds/heal"),
    ),
    attributes = mapOf(
        "health" to 50F,
        "mana" to 100F,
        "strength" to 10F,
        "healthRegen" to 2F,
    ),
    abilitySpecs = {
        abilitySpec(ability = reference("ability/npc_melee"), tags = listOf("Slot.A"))
        abilitySpec(ability = reference("ability/priest_heal"), tags = listOf("Slot.B"))
    },
    components = {
        component("dev.wildware.moba.Position")
        component("dev.wildware.moba.level.GameUnit")
        component("dev.wildware.moba.CharacterView")
    },
)
