// The orc elite - the richest of the six, and the one that exercises every feature of the
// animation pipeline: six sheets, two separate attacks, and a non-interruptible spin that lands
// four times.
//
// See `orc.udea.kts` for the id-suffix state contract `MobaCharacters` reads, and why the
// `character(...)` at the bottom of this file says the same thing a second way.

val orcEliteScale = 1.25F

spriteAnimationSet(
    name = "orc_elite_animation_set",
    animations = listOf(
        reference("character/orc_elite_idle"),
        reference("character/orc_elite_walk"),
        reference("character/orc_elite_attack"),
        reference("character/orc_elite_hit"),
        reference("character/orc_elite_death"),
        reference("character/orc_elite_spin"),
    ),
)

spriteAnimation(name = "orc_elite_idle", sheet = reference("character/orc_elite_idle_sheet"))

spriteAnimation(name = "orc_elite_walk", sheet = reference("character/orc_elite_walk_sheet"))

spriteAnimation(
    name = "orc_elite_attack",
    sheet = reference("character/orc_elite_attack_sheet"),
    loop = false,
    notifies = mapOf("swoosh" to 3, "attack_hit" to 4),
)

// The spin. The source corpus wrote `animNotify(n, "attack_hit")` four times over, which
// `SpriteAnimation` refuses as a duplicated name - a notify is matched by name, so only the last
// of the four could ever have fired. Four hits need four distinct names, so that is what this
// declares: the spin lands on frames 2, 4, 6 and 8 and the notify sink sees four separate events
// rather than one.
spriteAnimation(
    name = "orc_elite_spin",
    sheet = reference("character/orc_elite_spin_sheet"),
    loop = false,
    interruptable = false,
    notifies = mapOf("attack_hit" to 2, "attack_hit_2" to 4, "attack_hit_3" to 6, "attack_hit_4" to 8),
)

spriteAnimation(
    name = "orc_elite_hit",
    sheet = reference("character/orc_elite_hit_sheet"),
    loop = false,
    interruptable = false,
)

spriteAnimation(
    name = "orc_elite_death",
    sheet = reference("character/orc_elite_death_sheet"),
    loop = false,
    interruptable = false,
)

spriteSheet(
    name = "orc_elite_idle_sheet",
    spritePath = "sprites/orc_elite/orc_elite_idle.png",
    rows = 1,
    columns = 6,
    scale = orcEliteScale,
)

spriteSheet(
    name = "orc_elite_walk_sheet",
    spritePath = "sprites/orc_elite/orc_elite_walk.png",
    rows = 1,
    columns = 8,
    scale = orcEliteScale,
)

spriteSheet(
    name = "orc_elite_attack_sheet",
    spritePath = "sprites/orc_elite/orc_elite_attack01.png",
    rows = 1,
    columns = 7,
    scale = orcEliteScale,
)

spriteSheet(
    name = "orc_elite_spin_sheet",
    spritePath = "sprites/orc_elite/orc_elite_attack02.png",
    rows = 1,
    columns = 11,
    scale = orcEliteScale,
)

spriteSheet(
    name = "orc_elite_hit_sheet",
    spritePath = "sprites/orc_elite/orc_elite_hurt.png",
    rows = 1,
    columns = 4,
    scale = orcEliteScale,
)

spriteSheet(
    name = "orc_elite_death_sheet",
    spritePath = "sprites/orc_elite/orc_elite_death.png",
    rows = 1,
    columns = 4,
    scale = orcEliteScale,
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
// `size` is left at its default: this game's world scale is per *sheet* (`orcEliteScale` below),
// because the frames differ in how much transparent margin they carry, and a second scale on the
// character that no renderer reads would be decoration.

character(
    name = "orc_elite",
    health = 500F,
    spriteAnimationSet = reference("character/orc_elite_animation_set"),
    animationMap = mapOf(
        "idle" to reference("character/orc_elite_idle"),
        "walk" to reference("character/orc_elite_walk"),
        "attack" to reference("character/orc_elite_attack"),
        "hit" to reference("character/orc_elite_hit"),
        "death" to reference("character/orc_elite_death"),
        "spin" to reference("character/orc_elite_spin"),
    ),
    sounds = mapOf(
        "attack" to reference("sounds/melee_swoosh"),
        "hit" to reference("sounds/hurt"),
        "death" to reference("sounds/death"),
        "spin" to reference("sounds/spin"),
    ),
    attributes = mapOf(
        "health" to 500F,
        "strength" to 20F,
        "armour" to 20F,
        "magicResist" to 20F,
    ),
    abilitySpecs = {
        abilitySpec(ability = reference("ability/npc_melee"), tags = listOf("Slot.A"))
        abilitySpec(ability = reference("ability/orc_elite_spin"), tags = listOf("Slot.B"))
    },
    components = {
        component("dev.wildware.moba.Position")
        component("dev.wildware.moba.level.GameUnit")
        component("dev.wildware.moba.CharacterView")
    },
)
