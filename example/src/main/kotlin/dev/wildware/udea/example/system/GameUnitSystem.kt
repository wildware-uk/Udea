package dev.wildware.udea.example.system

import com.badlogic.gdx.physics.box2d.BodyDef.BodyType.StaticBody
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.FamilyOnAdd
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.ability.GameplayEffectSpec
import dev.wildware.udea.assets.reference
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.ecs.component.ability.Attributes
import dev.wildware.udea.ecs.component.animation.AnimationMapHolder
import dev.wildware.udea.ecs.component.audio.AudioMapHolder
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.ecs.component.control.CharacterController
import dev.wildware.udea.ecs.component.physics.Body
import dev.wildware.udea.ecs.system.AnimationSetSystem
import dev.wildware.udea.ecs.system.SoundSystem
import dev.wildware.udea.example.ability.CharacterAttributeSet
import dev.wildware.udea.example.ability.Debuffs
import dev.wildware.udea.example.character.GameUnitAnimationMap
import dev.wildware.udea.example.character.GameUnitSoundMap
import dev.wildware.udea.example.component.GameUnit

class GameUnitSystem : IteratingSystem(
    family { all(GameUnit, Abilities, CharacterController) }
), FamilyOnAdd {
    override fun onTickEntity(entity: Entity) {
        val abilities = entity[Abilities]
        val attributes = entity[Attributes]
        val gameUnit = entity[GameUnit]
        val controller = entity[CharacterController]

        val attributeSet = attributes.attributeSet as CharacterAttributeSet

        checkDead(gameUnit, attributeSet, entity, controller)

        if (gameUnit.isDead) return

        controller.isActive = !abilities.hasGameplayEffectTag(Debuffs.Stunned)
    }

    override fun onAddEntity(entity: Entity): Unit = context(world) {
        // `with(world)` wraps this whole body (issue #186). Kotlin 2.4 will not choose between
        // the system's own `EntityComponentContext` and the `context(world)` this body already
        // declares, and reports every `entity[...]`, `in` and `configure` call as ambiguous. For a
        // Fleks system those two candidates are the same `World` object, so naming one changes
        // nothing; `with` is the spelling the compiler itself suggests. The `context(world)` stays
        // because some calls in here -- `Entity.position`, `applyGameplayEffectToSelf` -- want
        // `World` as a context *parameter*, which an implicit receiver does not supply.
        // Diffing this file with whitespace ignored shows only these added lines.
        with(world) {
                val healthRegen = GameplayEffectSpec(reference("ability/passive_health_regen"))
                entity[Abilities].applyGameplayEffectToSelf(entity, healthRegen)
        }
    }

    private fun checkDead(
        gameUnit: GameUnit,
        attributes: CharacterAttributeSet,
        entity: Entity,
        controller: CharacterController
    ) {
        if (!gameUnit.isDead && attributes.health.currentValue <= 0F) {
            entity[Body].body.fixtureList.forEach {
                it.isSensor = true
            }
            entity[Body].body.type = StaticBody

            controller.isActive = false

            world.system<AnimationSetSystem>()
                .setAnimation(
                    entity,
                    entity[AnimationMapHolder].animationMap<GameUnitAnimationMap>().death,
                    force = true
                )

            entity.getOrNull(AudioMapHolder)?.get<GameUnitSoundMap>()?.death?.value?.let {
                world.system<SoundSystem>().playSoundAtPosition(it, entity[Transform].position)
            }

            gameUnit.isDead = true
        }
    }
}
