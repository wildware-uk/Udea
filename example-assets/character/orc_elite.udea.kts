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
        name = "orc_elite",
        animations = gameUnitAnimations(
            walk = "orc_elite_walk",
            run = "orc_elite_walk",
            idle = "orc_elite_idle",
            death = "orc_elite_death",
            attack = "orc_elite_attack",
            hit = "orc_elite_hit",
        ),
        sounds = gameUnitSoundMap(
            attack = reference("character/orc_attack_cue"),
            hit = reference("character/orc_hurt_cue"),
            death = reference("character/orc_death_cue")
        ),
        size = characterSize(0.3F, 0.3F),
        attributeSet = {
            CharacterAttributeSet(
                initHealth = 500F,
                initMana = 0F,
                initMagicResist = 20F,
                initArmour = 20F,
                initStrength = 20F
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
                ability = reference("ability/orc_elite_spin"),
                tags = {
                    add(Slot.B)
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
        spriteAnimationSet = reference("character/orc_elite_animation_set"),
    )

    spriteAnimationSet(
        name = "orc_elite_animation_set",
        animations = {
            spriteAnimation(
                name = "orc_elite_idle",
                sheet = reference("character/orc_elite_idle"),
            )

            spriteAnimation(
                name = "orc_elite_walk",
                sheet = reference("character/orc_elite_walk")
            )

            spriteAnimation(
                name = "orc_elite_attack",
                sheet = reference("character/orc_elite_attack"),
                loop = false,
                notifies = {
                    animNotify(3, "swoosh")
                    animNotify(4, "attack_hit")
                }
            )

            spriteAnimation(
                name = "orc_elite_spin_attack",
                sheet = reference("character/orc_elite_attack_2"),
                loop = false,
                interruptable = false,
                notifies = {
                    animNotify(2, "attack_hit")
                    animNotify(4, "attack_hit")
                    animNotify(6, "attack_hit")
                    animNotify(8, "attack_hit")
                }
            )

            spriteAnimation(
                name = "orc_elite_hit",
                sheet = reference("character/orc_elite_hit"),
                loop = false,
                interruptable = false
            )

            spriteAnimation(
                name = "orc_elite_death",
                sheet = reference("character/orc_elite_death"),
                loop = false,
                interruptable = false
            )
        }
    )

    val orcEliteScale = 0.03F

    spriteSheet(
        name = "orc_elite_idle",
        spritePath = "/sprites/orc_elite/orc_elite_idle.png",
        rows = 1,
        columns = 6,
        scale = orcEliteScale
    )

    spriteSheet(
        name = "orc_elite_walk",
        spritePath = "/sprites/orc_elite/orc_elite_walk.png",
        rows = 1,
        columns = 8,
        scale = orcEliteScale
    )

    spriteSheet(
        name = "orc_elite_attack",
        spritePath = "/sprites/orc_elite/orc_elite_attack01.png",
        rows = 1,
        columns = 7,
        scale = orcEliteScale
    )

    spriteSheet(
        name = "orc_elite_attack_2",
        spritePath = "/sprites/orc_elite/orc_elite_attack02.png",
        rows = 1,
        columns = 11,
        scale = orcEliteScale
    )

    spriteSheet(
        name = "orc_elite_attack_3",
        spritePath = "/sprites/orc_elite/orc_elite_attack03.png",
        rows = 1,
        columns = 9,
        scale = orcEliteScale
    )

    spriteSheet(
        name = "orc_elite_hit",
        spritePath = "/sprites/orc_elite/orc_elite_hurt.png",
        rows = 1,
        columns = 4,
        scale = orcEliteScale
    )

    spriteSheet(
        name = "orc_elite_death",
        spritePath = "/sprites/orc_elite/orc_elite_death.png",
        rows = 1,
        columns = 4,
        scale = orcEliteScale
    )

    soundCue(
        name = "orc_elite_swoosh_sound_cue",
        pitchVariance = 0.8F,
        sounds = {
            add("/sounds/orc/orc_elite_swoosh.ogg")
        }
    )

    soundCue(
        name = "orc_elite_big_shout_cue",
        pitchVariance = 0.3F,
        sounds = {
            add("/sounds/orc/orc_big_grunt.ogg")
        }
    )
}
