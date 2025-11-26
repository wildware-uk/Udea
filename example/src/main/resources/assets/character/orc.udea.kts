import dev.wildware.udea.example.ability.CharacterAttributeSet

bundle {
    character(
        name = "orc",
        animations = characterAnimations(
            animationSet = reference("character/orc_animation_set"),
            walk = "orc_walk",
            run = "orc_run",
            idle = "orc_idle",
            death = "necromancer_death",
        ),
        size = characterSize(0.5F, 0.5F),
        attributeSet = ::CharacterAttributeSet,
    )

    spriteAnimationSet(
        name = "orc_animation_set",
        animations = {
            spriteAnimation(
                name = "orc_idle",
                sheet = reference("character/orc_idle"),
            )

            spriteAnimation(
                name = "necromancer_walk",
                sheet = reference("character/orc_walk")
            )

            spriteAnimation(
                name = "orc_attack",
                sheet = reference("character/orc_attack"),
                loop = false,
            )

            spriteAnimation(
                name = "necromancer_death",
                sheet = reference("character/orc_death"),
                loop = false
            )
        }
    )

    val OrcScale = 0.02F

    spriteSheet(
        name = "orc_idle",
        spritePath = "/sprites/orc/Orc-Idle.png",
        rows = 1,
        columns = 6,
        scale = OrcScale
    )

    spriteSheet(
        name = "orc_run",
        spritePath = "/sprites/orc/Orc-Walk.png",
        rows = 1,
        columns = 8,
        scale = OrcScale
    )

    spriteSheet(
        name = "orc_attack",
        spritePath = "/sprites/orc/Orc-Attack01.png",
        rows = 1,
        columns = 6,
        scale = OrcScale
    )

    spriteSheet(
        name = "orc_death",
        spritePath = "/sprites/orc/Orc-Death.png",
        rows = 1,
        columns = 4,
        scale = OrcScale
    )
}
