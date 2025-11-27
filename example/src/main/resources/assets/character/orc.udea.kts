import dev.wildware.udea.ecs.component.base.debug
import dev.wildware.udea.example.ability.CharacterAttributeSet
import dev.wildware.udea.example.character.npcAnimations
import dev.wildware.udea.example.component.Team
import dev.wildware.udea.example.component.npc
import dev.wildware.udea.example.component.team
import dev.wildware.udea.example.tags.Character

bundle {
    character(
        name = "orc",
        animations = npcAnimations(
            walk = "orc_walk",
            run = "orc_walk",
            idle = "orc_idle",
            death = "orc_death",
            attack = "orc_attack",
            hit = "orc_hit",
        ),
        size = characterSize(0.2F, 0.2F),
        attributeSet = ::CharacterAttributeSet,
        components = lazy {
            team(Team.OrcTeam)
            npc()
            debug()
        },
        spriteAnimationSet = reference("character/orc_animation_set"),
        tags = listOf(Character),
    )

    spriteAnimationSet(
        name = "orc_animation_set",
        animations = {
            spriteAnimation(
                name = "orc_idle",
                sheet = reference("character/orc_idle"),
            )

            spriteAnimation(
                name = "orc_walk",
                sheet = reference("character/orc_walk")
            )

            spriteAnimation(
                name = "orc_attack",
                sheet = reference("character/orc_attack"),
                loop = false,
                notifies = {
                    animNotify(3, "attack_hit")
                }
            )

            spriteAnimation(
                name = "orc_hit",
                sheet = reference("character/orc_hit"),
                loop = false,
                interruptable = false
            )

            spriteAnimation(
                name = "orc_death",
                sheet = reference("character/orc_death"),
                loop = false,
                interruptable = false
            )
        }
    )

    val orcScale = 0.02F

    spriteSheet(
        name = "orc_idle",
        spritePath = "/sprites/orc/Orc-Idle.png",
        rows = 1,
        columns = 6,
        scale = orcScale
    )

    spriteSheet(
        name = "orc_walk",
        spritePath = "/sprites/orc/Orc-Walk.png",
        rows = 1,
        columns = 8,
        scale = orcScale
    )

    spriteSheet(
        name = "orc_attack",
        spritePath = "/sprites/orc/Orc-Attack01.png",
        rows = 1,
        columns = 6,
        scale = orcScale
    )

    spriteSheet(
        name = "orc_hit",
        spritePath = "/sprites/orc/Orc-Hurt.png",
        rows = 1,
        columns = 4,
        scale = orcScale
    )

    spriteSheet(
        name = "orc_death",
        spritePath = "/sprites/orc/Orc-Death.png",
        rows = 1,
        columns = 4,
        scale = orcScale
    )
}
