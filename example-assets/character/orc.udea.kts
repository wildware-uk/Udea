import dev.wildware.udea.ability.abilitySpec
import dev.wildware.udea.ecs.component.base.debug
import dev.wildware.udea.ecs.component.base.networkable
import dev.wildware.udea.example.ability.AITag
import dev.wildware.udea.example.ability.CharacterAttributeSet
import dev.wildware.udea.example.ability.Slot
import dev.wildware.udea.example.character.gameUnitAnimations
import dev.wildware.udea.example.character.gameUnitSoundMap
import dev.wildware.udea.example.component.Team
import dev.wildware.udea.example.component.gameUnit
import dev.wildware.udea.example.component.team

bundle {
    character(
        name = "orc",
        animations = gameUnitAnimations(
            walk = "orc_walk",
            run = "orc_walk",
            idle = "orc_idle",
            death = "orc_death",
            attack = "orc_attack",
            hit = "orc_hit",
        ),
        sounds = gameUnitSoundMap(
            attack = reference("character/orc_attack_cue"),
            hit = reference("character/orc_hurt_cue"),
            death = reference("character/orc_death_cue")
        ),
        size = characterSize(0.2F, 0.2F),
        attributeSet = {
            CharacterAttributeSet(
                initHealth = 150F,
                initMana = 0F,
                initMagicResist = 20F
            )
        },
        abilitySpecs = lazy {
            abilitySpec(
                ability = reference("ability/npc_melee"),
                tags = {
                    add(Slot.A)
                }
            )
        },
        components = lazy {
            networkable()
            team(Team.OrcTeam)
            gameUnit(
                aiTags = {
                    add(AITag.Fearless)
                }
            )
            debug()
        },
        spriteAnimationSet = reference("character/orc_animation_set"),
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
                    animNotify(3, "swoosh")
                    animNotify(4, "attack_hit")
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

    soundCue(
        name = "orc_attack_cue",
        sounds = {
            add("/sounds/orc/orc_grunt_1.ogg")
            add("/sounds/orc/orc_grunt_2.ogg")
            add("/sounds/orc/orc_grunt_3.ogg")
            add("/sounds/orc/orc_grunt_4.ogg")
            add("/sounds/orc/orc_grunt_5.ogg")
        }
    )

    soundCue(
        name = "orc_hurt_cue",
        sounds = {
            add("/sounds/orc/orc_hurt_1.ogg")
            add("/sounds/orc/orc_hurt_2.ogg")
            add("/sounds/orc/orc_hurt_3.ogg")
            add("/sounds/orc/orc_hurt_4.ogg")
            add("/sounds/orc/orc_hurt_5.ogg")
        }
    )

    soundCue(
        name = "orc_death_cue",
        sounds = {
            add("/sounds/orc/orc_death_1.ogg")
            add("/sounds/orc/orc_death_2.ogg")
            add("/sounds/orc/orc_death_3.ogg")
            add("/sounds/orc/orc_death_4.ogg")
        }
    )
}
