// Migrated from example/src/main/resources/assets/character/soldier.udea.kts (issue #93).

val soldierScale = 0.02F

character(
    name = "soldier",
    size = 0.2F,
    spriteAnimationSet = reference("character/soldier_animation_set"),
    animationMap = mapOf(
        "idle" to reference("character/soldier_idle"),
        "walk" to reference("character/soldier_walk"),
        "run" to reference("character/soldier_walk"),
        "attack" to reference("character/soldier_attack"),
        "hit" to reference("character/soldier_hit"),
        "death" to reference("character/soldier_death"),
    ),
    attributes = mapOf(
        "health" to 100F,
        "armour" to 50F,
    ),
    abilitySpecs = {
        abilitySpec(ability = reference("ability/npc_melee"), tags = listOf("Slot.A"))
        abilitySpec(ability = reference("ability/soldier_fire_arrow"), tags = listOf("Slot.B"))
    },
    components = {
        component("dev.wildware.udea.ecs.component.base.Networkable")
        component("dev.wildware.udea.example.component.Team", "team" to "SoldierTeam")
        component("dev.wildware.udea.example.component.GameUnit")
        component("dev.wildware.udea.ecs.component.base.Debug")
    },
)

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
