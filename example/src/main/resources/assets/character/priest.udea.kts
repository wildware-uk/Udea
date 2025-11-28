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
        name = "priest",
        animations = gameUnitAnimations(
            walk = "priest_walk",
            run = "priest_walk",
            idle = "priest_idle",
            death = "priest_death",
            attack = "priest_attack",
            hit = "priest_hit",
        ),
        size = characterSize(0.2F, 0.2F),
        attributeSet = {
            CharacterAttributeSet(
                initHealth = 50F,
                initMana = 100F,
            )
        },
        abilitySpecs = lazy {
            abilitySpec(
                ability = Assets["ability/npc_melee"],
                tags = {
                    add(Slot.A)
                }
            )

            abilitySpec(
                ability = Assets["ability/priest_heal"],
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
        spriteAnimationSet = reference("character/priest_animation_set")
    )

    spriteAnimationSet(
        name = "priest_animation_set",
        animations = {
            spriteAnimation(
                name = "priest_idle",
                sheet = reference("character/priest_idle"),
            )

            spriteAnimation(
                name = "priest_walk",
                sheet = reference("character/priest_walk")
            )

            spriteAnimation(
                name = "priest_attack",
                sheet = reference("character/priest_attack"),
                loop = false,
                notifies = {
                    animNotify(5, "attack_hit")
                }
            )

            spriteAnimation(
                name = "priest_hit",
                sheet = reference("character/priest_hit"),
                loop = false,
                interruptable = false
            )

            spriteAnimation(
                name = "priest_death",
                sheet = reference("character/priest_death"),
                loop = false,
                interruptable = false
            )

            spriteAnimation(
                name = "priest_heal",
                sheet = reference("character/priest_heal"),
                notifies = {
                    animNotify(4, "heal")
                },
                loop = false,
            )
        }
    )

    val priestScale = 0.02F

    spriteSheet(
        name = "priest_idle",
        spritePath = "/sprites/priest/Priest-Idle.png",
        rows = 1,
        columns = 6,
        scale = priestScale
    )

    spriteSheet(
        name = "priest_walk",
        spritePath = "/sprites/priest/Priest-Walk.png",
        rows = 1,
        columns = 8,
        scale = priestScale
    )

    spriteSheet(
        name = "priest_attack",
        spritePath = "/sprites/priest/Priest-Attack.png",
        rows = 1,
        columns = 9,
        scale = priestScale
    )

    spriteSheet(
        name = "priest_heal",
        spritePath = "/sprites/priest/Priest-Heal.png",
        rows = 1,
        columns = 6,
        scale = priestScale
    )

    spriteSheet(
        name = "priest_hit",
        spritePath = "/sprites/priest/Priest-Hurt.png",
        rows = 1,
        columns = 4,
        scale = priestScale
    )

    spriteSheet(
        name = "priest_death",
        spritePath = "/sprites/priest/Priest-Death.png",
        rows = 1,
        columns = 4,
        scale = priestScale
    )
}
