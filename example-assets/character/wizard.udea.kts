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
        name = "wizard",
        animations = gameUnitAnimations(
            walk = "wizard_walk",
            run = "wizard_walk",
            idle = "wizard_idle",
            death = "wizard_death",
            attack = "wizard_attack",
            hit = "wizard_hit",
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
                ability = reference("ability/npc_melee"),
                tags = {
                    add(Slot.A)
                }
            )

            abilitySpec(
                ability = reference("ability/wizard_heal"),
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
        spriteAnimationSet = reference("character/wizard_animation_set")
    )

    spriteAnimationSet(
        name = "wizard_animation_set",
        animations = {
            spriteAnimation(
                name = "wizard_idle",
                sheet = reference("character/wizard_idle"),
            )

            spriteAnimation(
                name = "wizard_walk",
                sheet = reference("character/wizard_walk")
            )

            spriteAnimation(
                name = "wizard_attack",
                sheet = reference("character/wizard_attack"),
                loop = false,
                notifies = {
                    animNotify(5, "attack_hit")
                }
            )

            spriteAnimation(
                name = "wizard_hit",
                sheet = reference("character/wizard_hit"),
                loop = false,
                interruptable = false
            )

            spriteAnimation(
                name = "wizard_death",
                sheet = reference("character/wizard_death"),
                loop = false,
                interruptable = false
            )

            spriteAnimation(
                name = "wizard_heal",
                sheet = reference("character/wizard_heal"),
                notifies = {
                    animNotify(4, "heal")
                },
                loop = false,
            )
        }
    )

    val wizardScale = 0.02F

    spriteSheet(
        name = "wizard_idle",
        spritePath = "/sprites/wizard/Priest-Idle.png",
        rows = 1,
        columns = 6,
        scale = wizardScale
    )

    spriteSheet(
        name = "wizard_walk",
        spritePath = "/sprites/wizard/Priest-Walk.png",
        rows = 1,
        columns = 8,
        scale = wizardScale
    )

    spriteSheet(
        name = "wizard_attack",
        spritePath = "/sprites/wizard/Priest-Attack.png",
        rows = 1,
        columns = 9,
        scale = wizardScale
    )

    spriteSheet(
        name = "wizard_heal",
        spritePath = "/sprites/wizard/Priest-Heal.png",
        rows = 1,
        columns = 6,
        scale = wizardScale
    )

    spriteSheet(
        name = "wizard_hit",
        spritePath = "/sprites/wizard/Priest-Hurt.png",
        rows = 1,
        columns = 4,
        scale = wizardScale
    )

    spriteSheet(
        name = "wizard_death",
        spritePath = "/sprites/wizard/Priest-Death.png",
        rows = 1,
        columns = 4,
        scale = wizardScale
    )
}
