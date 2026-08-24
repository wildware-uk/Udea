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

// --- the gameplay half, which this root could not carry until `character` was a published kind
//
// `character(...)` was `AssetKind.Unpublishable`, so a declaration made with it packed as an
// opaque record with no runtime type, and `EntityDefinition.blueprint` - a `Ref<Blueprint>` -
// refused it. That is why this game had two asset roots: `moba/src/main/assets` held the migrated
// corpus with its `character(...)` calls and could not be packed, and this root held the half that
// could. `Character` is a `SpawnRecipe` now, so the two are one root and this is the half that
// came back.
//
// `size` is left at its default: this game's world scale is per *sheet* (`skeletonScale` below),
// because the frames differ in how much transparent margin they carry, and a second scale on the
// character that no renderer reads would be decoration.

character(
    name = "skeleton",
    health = 50F,
    spriteAnimationSet = reference("character/skeleton_animation_set"),
    animationMap = mapOf(
        "idle" to reference("character/skeleton_idle"),
        "walk" to reference("character/skeleton_walk"),
        "attack" to reference("character/skeleton_attack"),
        "hit" to reference("character/skeleton_hit"),
        "death" to reference("character/skeleton_death"),
    ),
    sounds = mapOf(
        "attack" to reference("sounds/melee_swoosh"),
        "hit" to reference("sounds/hurt"),
        "death" to reference("sounds/death"),
    ),
    attributes = mapOf(
        "health" to 50F,
        "strength" to 10F,
        "magicResist" to 20F,
    ),
    abilitySpecs = {
        abilitySpec(ability = reference("ability/npc_melee"), tags = listOf("Slot.A"))
    },
    components = {
        component("dev.wildware.moba.Position")
        component("dev.wildware.moba.level.GameUnit")
        component("dev.wildware.moba.CharacterView")
    },
)
