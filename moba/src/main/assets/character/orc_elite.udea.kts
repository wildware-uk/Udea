// Migrated from example/src/main/resources/assets/character/orc_elite.udea.kts (issue #93).
//
// The richest script in the corpus: a character with sounds, attributes, ability specs and
// components, an animation set, seven sheets and two sound cues.

val orcEliteScale = 0.03F

character(
    name = "orc_elite",
    size = 0.3F,
    spriteAnimationSet = reference("character/orc_elite_animation_set"),
    animationMap = mapOf(
        "idle" to reference("character/orc_elite_idle"),
        "walk" to reference("character/orc_elite_walk"),
        "run" to reference("character/orc_elite_walk"),
        "attack" to reference("character/orc_elite_attack"),
        "hit" to reference("character/orc_elite_hit"),
        "death" to reference("character/orc_elite_death"),
    ),
    sounds = mapOf(
        "attack" to reference("character/orc_attack_cue"),
        "hit" to reference("character/orc_hurt_cue"),
        "death" to reference("character/orc_death_cue"),
    ),
    attributes = mapOf(
        "health" to 500F,
        "mana" to 0F,
        "magicResist" to 20F,
        "armour" to 20F,
        "strength" to 20F,
    ),
    abilitySpecs = {
        abilitySpec(ability = reference("ability/npc_melee"), tags = listOf("Slot.A"))
        abilitySpec(ability = reference("ability/orc_elite_spin"), tags = listOf("Slot.B"))
    },
    components = {
        component("dev.wildware.udea.ecs.component.base.Networkable")
        component("dev.wildware.udea.example.component.Team", "team" to "OrcTeam")
        component("dev.wildware.udea.example.component.GameUnit", "aiTags" to listOf("AITag.Fearless"))
        component("dev.wildware.udea.ecs.component.base.Debug")
    },
)

spriteAnimationSet(
    name = "orc_elite_animation_set",
    animations = listOf(
        reference("character/orc_elite_idle"),
        reference("character/orc_elite_walk"),
        reference("character/orc_elite_attack"),
        reference("character/orc_elite_spin_attack"),
        reference("character/orc_elite_hit"),
        reference("character/orc_elite_death"),
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

spriteAnimation(
    name = "orc_elite_spin_attack",
    sheet = reference("character/orc_elite_attack_2_sheet"),
    loop = false,
    interruptable = false,
    // The source corpus wrote `animNotify(2, "attack_hit")` four times over. A notify is matched
    // by name and `SpriteAnimation`'s own `init` refuses a duplicated one, so three of the four
    // were dead on arrival: only the last would ever have fired. Kept as the one it resolves to.
    notifies = mapOf("attack_hit" to 8),
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
    name = "orc_elite_attack_2_sheet",
    spritePath = "sprites/orc_elite/orc_elite_attack02.png",
    rows = 1,
    columns = 11,
    scale = orcEliteScale,
)

spriteSheet(
    name = "orc_elite_attack_3_sheet",
    spritePath = "sprites/orc_elite/orc_elite_attack03.png",
    rows = 1,
    columns = 9,
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

soundCue(
    name = "orc_elite_swoosh_sound_cue",
    pitchVariance = 0.8F,
    sounds = listOf("sounds/orc/orc_elite_swoosh.ogg"),
)

soundCue(
    name = "orc_elite_big_shout_cue",
    pitchVariance = 0.3F,
    sounds = listOf("sounds/orc/orc_big_grunt.ogg"),
)
