import dev.wildware.udea.ability.abilitySpec
import dev.wildware.udea.ecs.component.base.debug
import dev.wildware.udea.ecs.component.base.networkable
import dev.wildware.udea.example.ability.AITag
import dev.wildware.udea.example.ability.CharacterAttributeSet
import dev.wildware.udea.example.ability.Slot
import dev.wildware.udea.example.character.gameUnitAnimations
import dev.wildware.udea.example.component.Team
import dev.wildware.udea.example.component.gameUnit
import dev.wildware.udea.example.component.team

bundle {
    character(
        name = "skeleton",
        animations = gameUnitAnimations(
            walk = "skeleton_walk",
            run = "skeleton_walk",
            idle = "skeleton_idle",
            death = "skeleton_death",
            attack = "skeleton_attack",
            hit = "skeleton_hit",
        ),
        size = characterSize(0.2F, 0.2F),
        attributeSet = {
            CharacterAttributeSet(
                initHealth = 50F,
                initMana = 0F,
                initMagicResist = 20F,
                initHealthRegen = 0F
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
            team(Team.UndeadTeam)
            gameUnit(
                aiTags = {
                    add(AITag.Fearless)
                }
            )
            debug()
        },
        spriteAnimationSet = reference("character/skeleton_animation_set"),
    )

    spriteAnimationSet(
        name = "skeleton_animation_set",
        animations = {
            spriteAnimation(
                name = "skeleton_idle",
                sheet = reference("character/skeleton_idle"),
            )

            spriteAnimation(
                name = "skeleton_walk",
                sheet = reference("character/skeleton_walk")
            )

            spriteAnimation(
                name = "skeleton_attack",
                sheet = reference("character/skeleton_attack"),
                loop = false,
                notifies = {
                    animNotify(3, "swoosh")
                    animNotify(4, "attack_hit")
                }
            )

            spriteAnimation(
                name = "skeleton_hit",
                sheet = reference("character/skeleton_hit"),
                loop = false,
                interruptable = false
            )

            spriteAnimation(
                name = "skeleton_death",
                sheet = reference("character/skeleton_death"),
                loop = false,
                interruptable = false
            )
        }
    )

    val skeletonScale = 0.02F

    spriteSheet(
        name = "skeleton_idle",
        spritePath = "/sprites/skeleton/Skeleton-Idle.png",
        rows = 1,
        columns = 6,
        scale = skeletonScale
    )

    spriteSheet(
        name = "skeleton_walk",
        spritePath = "/sprites/skeleton/Skeleton-Walk.png",
        rows = 1,
        columns = 8,
        scale = skeletonScale
    )

    spriteSheet(
        name = "skeleton_attack",
        spritePath = "/sprites/skeleton/Skeleton-Attack01.png",
        rows = 1,
        columns = 6,
        scale = skeletonScale
    )

    spriteSheet(
        name = "skeleton_hit",
        spritePath = "/sprites/skeleton/Skeleton-Hurt.png",
        rows = 1,
        columns = 4,
        scale = skeletonScale
    )

    spriteSheet(
        name = "skeleton_death",
        spritePath = "/sprites/skeleton/Skeleton-Death.png",
        rows = 1,
        columns = 4,
        scale = skeletonScale
    )
}
