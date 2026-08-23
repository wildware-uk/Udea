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
