import dev.wildware.udea.ecs.component.base.debug
import dev.wildware.udea.example.ability.CharacterAttributeSet
import dev.wildware.udea.example.character.npcAnimations
import dev.wildware.udea.example.component.Team
import dev.wildware.udea.example.component.npc
import dev.wildware.udea.example.component.team
import dev.wildware.udea.example.tags.Character

bundle {
    character(
        name = "soldier",
        animations = npcAnimations(
            walk = "soldier_walk",
            run = "soldier_walk",
            idle = "soldier_idle",
            death = "soldier_death",
            attack = "soldier_attack",
            hit = "soldier_hit",
        ),
        size = characterSize(0.2F, 0.2F),
        attributeSet = ::CharacterAttributeSet,
        components = lazy {
            team(Team.SoldierTeam)
            npc()
            debug()
        },
        tags = listOf(Character),
        spriteAnimationSet = reference("character/soldier_animation_set")
    )

    spriteAnimationSet(
        name = "soldier_animation_set",
        animations = {
            spriteAnimation(
                name = "soldier_idle",
                sheet = reference("character/soldier_idle"),
            )

            spriteAnimation(
                name = "soldier_walk",
                sheet = reference("character/soldier_walk")
            )

            spriteAnimation(
                name = "soldier_attack",
                sheet = reference("character/soldier_attack"),
                loop = false,
                notifies = {
                    animNotify(3, "attack_hit")
                }
            )

            spriteAnimation(
                name = "soldier_hit",
                sheet = reference("character/soldier_hit"),
                loop = false,
                interruptable = false
            )

            spriteAnimation(
                name = "soldier_death",
                sheet = reference("character/soldier_death"),
                loop = false,
                interruptable = false
            )
        }
    )

    val soldierScale = 0.02F

    spriteSheet(
        name = "soldier_idle",
        spritePath = "/sprites/soldier/Soldier-Idle.png",
        rows = 1,
        columns = 6,
        scale = soldierScale
    )

    spriteSheet(
        name = "soldier_walk",
        spritePath = "/sprites/soldier/Soldier-Walk.png",
        rows = 1,
        columns = 8,
        scale = soldierScale
    )

    spriteSheet(
        name = "soldier_attack",
        spritePath = "/sprites/soldier/Soldier-Attack01.png",
        rows = 1,
        columns = 6,
        scale = soldierScale
    )

    spriteSheet(
        name = "soldier_hit",
        spritePath = "/sprites/soldier/Soldier-Hurt.png",
        rows = 1,
        columns = 4,
        scale = soldierScale
    )

    spriteSheet(
        name = "soldier_death",
        spritePath = "/sprites/soldier/Soldier-Death.png",
        rows = 1,
        columns = 4,
        scale = soldierScale
    )
}
