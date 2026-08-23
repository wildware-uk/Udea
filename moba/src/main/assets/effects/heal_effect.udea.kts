// Migrated from example/src/main/resources/assets/effects/heal_effect.udea.kts (issue #93).
//
// `effect` is a game kind — the old tree declared it in `example/.../assets/Effect.kt` — so it
// is `AssetKind.Unpublishable` like `character`.

spriteSheet(
    name = "heal_effect_sheet",
    spritePath = "sprites/priest/spells/Priest-Heal_Effect.png",
    columns = 4,
    rows = 1,
    scale = 0.01F,
)

spriteAnimation(name = "heal", sheet = reference("effects/heal_effect_sheet"))

spriteAnimationSet(
    name = "heal_effect_set",
    animations = listOf(reference("effects/heal")),
)

effect(
    name = "heal_effect",
    animationSet = reference("effects/heal_effect_set"),
    animation = "heal",
    duration = 5.0F,
)
