import dev.wildware.udea.ability.abilitySpec
import dev.wildware.udea.ecs.component.base.debug
import dev.wildware.udea.ecs.component.base.networkable
import dev.wildware.udea.example.ability.CharacterAttributeSet
import dev.wildware.udea.example.ability.Slot
import dev.wildware.udea.example.character.gameUnitAnimations
import dev.wildware.udea.example.component.Team
import dev.wildware.udea.example.component.gameUnit
import dev.wildware.udea.example.component.team

bundle {
    character(
        name = "soldier",
        animations = gameUnitAnimations(
            walk = "soldier_walk",
            run = "soldier_walk",
            idle = "soldier_idle",
            death = "soldier_death",
            attack = "soldier_attack",
            hit = "soldier_hit",
        ),
        size = characterSize(0.2F, 0.2F),
        attributeSet = {
            CharacterAttributeSet(
                initHealth = 100F,
                initArmour = 50F,
            )
        },
        abilitySpecs = lazy {
            abilitySpec(
                ability = reference("ability/npc_melee"),
                tags = {
                    add(Slot.A)
                }
            )

            abilitySpec(
                ability = reference("ability/soldier_fire_arrow"),
                tags = {
                    add(Slot.B)
                }
            )
        },
        components = lazy {
            networkable()
            team(Team.SoldierTeam)
            gameUnit()
            debug()
        },
        spriteAnimationSet = reference("character/soldier_animation_set"),
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
                    animNotify(3, "swoosh")
                    animNotify(4, "attack_hit")
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

            spriteAnimation(
                name = "soldier_fire_arrow",
                sheet = reference("character/soldier_fire_arrow"),
                notifies = {
                    animNotify(8, "fire_arrow")
                },
                loop = false,
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
        name = "soldier_fire_arrow",
        spritePath = "/sprites/soldier/Soldier-Attack03.png",
        rows = 1,
        columns = 9,
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
