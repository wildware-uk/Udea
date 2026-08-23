// Migrated from example/src/main/resources/assets/character/skeleton.udea.kts (issue #93).

val skeletonScale = 0.02F

character(
    name = "skeleton",
    size = 0.2F,
    spriteAnimationSet = reference("character/skeleton_animation_set"),
    animationMap = mapOf(
        "idle" to reference("character/skeleton_idle"),
        "walk" to reference("character/skeleton_walk"),
        "run" to reference("character/skeleton_walk"),
        "attack" to reference("character/skeleton_attack"),
        "hit" to reference("character/skeleton_hit"),
        "death" to reference("character/skeleton_death"),
    ),
    attributes = mapOf(
        "health" to 50F,
        "mana" to 0F,
        "magicResist" to 20F,
        "healthRegen" to 0F,
    ),
    abilitySpecs = {
        abilitySpec(ability = reference("ability/npc_melee"), tags = listOf("Slot.A"))
    },
    components = {
        component("dev.wildware.udea.ecs.component.base.Networkable")
        component("dev.wildware.udea.example.component.Team", "team" to "UndeadTeam")
        component("dev.wildware.udea.example.component.GameUnit", "aiTags" to listOf("AITag.Fearless"))
        component("dev.wildware.udea.ecs.component.base.Debug")
    },
)

spriteAnimationSet(
    name = "skeleton_animation_set",
    animations = listOf(
        reference("character/skeleton_idle"),
        reference("character/skeleton_walk"),
        reference("character/skeleton_attack"),
        reference("character/skeleton_hit"),
        reference("character/skeleton_death"),
    ),
)

spriteAnimation(name = "skeleton_idle", sheet = reference("character/skeleton_idle_sheet"))

spriteAnimation(name = "skeleton_walk", sheet = reference("character/skeleton_walk_sheet"))

spriteAnimation(
    name = "skeleton_attack",
    sheet = reference("character/skeleton_attack_sheet"),
    loop = false,
    notifies = mapOf("swoosh" to 3, "attack_hit" to 4),
)

spriteAnimation(
    name = "skeleton_hit",
    sheet = reference("character/skeleton_hit_sheet"),
    loop = false,
    interruptable = false,
)

spriteAnimation(
    name = "skeleton_death",
    sheet = reference("character/skeleton_death_sheet"),
    loop = false,
    interruptable = false,
)

spriteSheet(
    name = "skeleton_idle_sheet",
    spritePath = "sprites/skeleton/Skeleton-Idle.png",
    rows = 1,
    columns = 6,
    scale = skeletonScale,
)

spriteSheet(
    name = "skeleton_walk_sheet",
    spritePath = "sprites/skeleton/Skeleton-Walk.png",
    rows = 1,
    columns = 8,
    scale = skeletonScale,
)

spriteSheet(
    name = "skeleton_attack_sheet",
    spritePath = "sprites/skeleton/Skeleton-Attack01.png",
    rows = 1,
    columns = 6,
    scale = skeletonScale,
)

spriteSheet(
    name = "skeleton_hit_sheet",
    spritePath = "sprites/skeleton/Skeleton-Hurt.png",
    rows = 1,
    columns = 4,
    scale = skeletonScale,
)

spriteSheet(
    name = "skeleton_death_sheet",
    spritePath = "sprites/skeleton/Skeleton-Death.png",
    rows = 1,
    columns = 4,
    scale = skeletonScale,
)
