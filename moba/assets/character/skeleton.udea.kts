// The skeleton. See `orc.udea.kts` for the state contract.

val skeletonScale = 1.88F

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
