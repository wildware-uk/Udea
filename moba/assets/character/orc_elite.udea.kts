// The orc elite - the richest of the six, and the one that exercises every feature of the
// animation pipeline: six sheets, two separate attacks, and a non-interruptible spin that lands
// four times.
//
// See `orc.udea.kts` for why there is no `character(...)` here and for the id-suffix state
// contract `MobaCharacters` reads.

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
