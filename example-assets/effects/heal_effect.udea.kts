import dev.wildware.udea.example.assets.effect

bundle {
    spriteSheet(
        name = "heal_effect_sheet",
        spritePath = "/sprites/priest/spells/Priest-Heal_Effect.png",
        columns = 4,
        rows = 1,
        scale = 0.01F
    )

    spriteAnimationSet(
        name = "heal_effect_set",
        animations = {
            spriteAnimation(
                name = "heal",
                sheet = reference("effects/heal_effect_sheet"),
                loop = true,
            )
        }
    )

    effect(
        name = "heal_effect",
        animationSet = reference("effects/heal_effect_set"),
        animation = "heal",
        duration = 5.0F
    )
}
