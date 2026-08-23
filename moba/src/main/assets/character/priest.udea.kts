// Migrated from example/src/main/resources/assets/character/priest.udea.kts (issue #93).

val priestScale = 0.02F

character(
    name = "priest",
    size = 0.2F,
    spriteAnimationSet = reference("character/priest_animation_set"),
    animationMap = mapOf(
        "idle" to reference("character/priest_idle"),
        "walk" to reference("character/priest_walk"),
        "run" to reference("character/priest_walk"),
        "attack" to reference("character/priest_attack"),
        "hit" to reference("character/priest_hit"),
        "death" to reference("character/priest_death"),
    ),
    attributes = mapOf(
        "health" to 50F,
        "mana" to 100F,
        "healthRegen" to 2F,
    ),
    abilitySpecs = {
        abilitySpec(ability = reference("ability/npc_melee"), tags = listOf("Slot.A"))
        abilitySpec(ability = reference("ability/priest_heal"), tags = listOf("Slot.B"))
    },
    components = {
        component("dev.wildware.udea.ecs.component.base.Networkable")
        component("dev.wildware.udea.example.component.Team", "team" to "SoldierTeam")
        component("dev.wildware.udea.example.component.GameUnit")
        component("dev.wildware.udea.ecs.component.base.Debug")
    },
)

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
