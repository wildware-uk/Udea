// Migrated from example/src/main/resources/assets/character/orc.udea.kts (issue #93).
//
// Sheets are named `<animation>_sheet`. In the source corpus the sheet and the animation that
// plays it were both called `orc_idle`, so both declared the id `character/orc_idle` and the old
// two-key loader silently kept whichever it happened to see last.

val orcScale = 0.02F

character(
    name = "orc",
    size = 0.2F,
    spriteAnimationSet = reference("character/orc_animation_set"),
    animationMap = mapOf(
        "idle" to reference("character/orc_idle"),
        "walk" to reference("character/orc_walk"),
        "run" to reference("character/orc_walk"),
        "attack" to reference("character/orc_attack"),
        "hit" to reference("character/orc_hit"),
        "death" to reference("character/orc_death"),
    ),
    sounds = mapOf(
        "attack" to reference("character/orc_attack_cue"),
        "hit" to reference("character/orc_hurt_cue"),
        "death" to reference("character/orc_death_cue"),
    ),
    attributes = mapOf(
        "health" to 150F,
        "mana" to 0F,
        "magicResist" to 20F,
    ),
    abilitySpecs = {
        abilitySpec(ability = reference("ability/npc_melee"), tags = listOf("Slot.A"))
    },
    components = {
        component("dev.wildware.udea.ecs.component.base.Networkable")
        component("dev.wildware.udea.example.component.Team", "team" to "OrcTeam")
        component("dev.wildware.udea.example.component.GameUnit", "aiTags" to listOf("AITag.Fearless"))
        component("dev.wildware.udea.ecs.component.base.Debug")
    },
)

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

soundCue(
    name = "orc_attack_cue",
    sounds = listOf(
        "sounds/orc/orc_grunt_1.ogg",
        "sounds/orc/orc_grunt_2.ogg",
        "sounds/orc/orc_grunt_3.ogg",
        "sounds/orc/orc_grunt_4.ogg",
        "sounds/orc/orc_grunt_5.ogg",
    ),
)

soundCue(
    name = "orc_hurt_cue",
    sounds = listOf(
        "sounds/orc/orc_hurt_1.ogg",
        "sounds/orc/orc_hurt_2.ogg",
        "sounds/orc/orc_hurt_3.ogg",
        "sounds/orc/orc_hurt_4.ogg",
        "sounds/orc/orc_hurt_5.ogg",
    ),
)

soundCue(
    name = "orc_death_cue",
    sounds = listOf(
        "sounds/orc/orc_death_1.ogg",
        "sounds/orc/orc_death_2.ogg",
        "sounds/orc/orc_death_3.ogg",
        "sounds/orc/orc_death_4.ogg",
    ),
)
