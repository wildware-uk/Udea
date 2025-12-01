package dev.wildware.udea.ecs.system

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.ability.AbilitySpec
import dev.wildware.udea.ability.GameplayTag
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.ecs.component.base.Debug
import dev.wildware.udea.ecs.component.base.Networkable
import dev.wildware.udea.gameScreen
import dev.wildware.udea.getNetworkEntityOrNull
import dev.wildware.udea.network.AbilityPacket
import dev.wildware.udea.network.AbilityPacketInstantiator
import dev.wildware.udea.processAndRemoveEach
import dev.wildware.udea.remoteEntity

class AbilitySystem : IntervalSystem() {

    val family = family { all(Abilities) }
    val abilityQueue = mutableListOf<AbilityPacket>()

    override fun onTick() {
        family.forEach { entity ->
            val abilities = entity[Abilities]


            entity.getOrNull(Debug)?.addMessage("${abilities.abilities.map { it.ability.value.name }}")
            entity.getOrNull(Debug)?.addMessage("${abilities.gameplayEffectSpecs.map { it.gameplayEffect.value.name }}")


            abilities.abilities.forEach {
                context(world) {
                    it.tick()

                    if (it.ability.value.blockedBy.any { tag -> abilities.hasGameplayEffectTag(tag) }) {
                        it.finish(cancelled = true)
                    }
                }
            }
        }

        abilityQueue.processAndRemoveEach {
            val abilityId = it.abilityId
            context(world) {
                val remoteSource = world.getNetworkEntityOrNull(it.entity)
                    ?: return@processAndRemoveEach
                val spec = remoteSource[Abilities].findAbilityById(abilityId)
                doAbility(remoteSource, spec)
                println(
                    "${if (gameScreen.isServer) "SERVER" else "CLIENT"} " +
                            "Ability activated: $abilityId ${spec.ability.value.name} by ${it.entity} -> $remoteSource"
                )

                if (gameScreen.isServer) {
                    gameScreen.networkServerSystem!!.server.sendToAllTCP(
                        AbilityPacket(abilityId, remoteSource)
                    )
                }
            }

            AbilityPacketInstantiator.free(it)
        }
    }

    context(world: World)
    fun activateAbilityByTag(entity: Entity, tag: GameplayTag) {
        val abilities = entity.remoteEntity[Abilities]
        val ability = abilities.findAbilityByTag(tag)

        if (ability != null) activateAbility(entity, ability)
    }

    fun activateAbility(entity: Entity, spec: AbilitySpec) {
        val networkEntity = entity[Networkable].remoteEntity

        if (gameScreen.isServer) {
            context(world) {
                doAbility(networkEntity, spec)
            }

            gameScreen.networkServerSystem?.server?.sendToAllTCP(
                AbilityPacket(
                    spec.id,
                    networkEntity
                )
            )
        } else {
            gameScreen.networkClientSystem?.client?.sendTCP(
                AbilityPacket(spec.id, networkEntity)
            )
        }
    }

    context(_: World)
    private fun doAbility(entity: Entity, spec: AbilitySpec) {
        spec.activate(entity)
    }
}
