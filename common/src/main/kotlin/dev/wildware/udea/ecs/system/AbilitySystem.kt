package dev.wildware.udea.ecs.system

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.Vector2
import dev.wildware.udea.ability.AbilityInfo
import dev.wildware.udea.ability.AbilitySpec
import dev.wildware.udea.ability.GameplayTag
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.ecs.component.base.Networkable
import dev.wildware.udea.gameScreen
import dev.wildware.udea.getNetworkEntityOrNull
import dev.wildware.udea.network.AbilityPacket
import dev.wildware.udea.network.AbilityPacketInstantiator
import dev.wildware.udea.processAndRemoveEach

class AbilitySystem : IntervalSystem() {

    val family = family { all(Abilities) }
    val abilityQueue = mutableListOf<AbilityPacket>()

    override fun onTick() {
        family.forEach { entity ->
            val abilities = entity[Abilities]

            abilities.abilities.forEach {
                if (it.ability.blockedBy.any { tag -> abilities.hasGameplayEffectTag(tag) }) {
                    it.finish(cancelled = true)
                }
            }
        }

        abilityQueue.processAndRemoveEach {
            val abilityId = it.abilityId
            context(world) {
                val remoteSource = world.getNetworkEntityOrNull(it.source!!)
                val remoteTarget = it.target?.let { t -> world.getNetworkEntityOrNull(t) }


                if (remoteSource != null) {
                    val ability = remoteSource[Abilities].findAbilityById(abilityId)
                    doAbility(remoteSource, remoteTarget, it.targetPos!!, ability)
                }
            }

            if (gameScreen.isServer) {
                gameScreen.networkServerSystem!!.server.sendToAllTCP(
                    AbilityPacket(
                        abilityId,
                        it.source,
                        it.targetPos,
                        it.target
                    )
                )
            }

            AbilityPacketInstantiator.free(it)
        }
    }

    fun activateAbilityByTag(abilityInfo: AbilityInfo, tag: GameplayTag) {
        val abilities = abilityInfo.source[Abilities]
        val ability = abilities.findAbilityByTag(tag)

        if (ability != null) activateAbility(abilityInfo, ability)
    }

    fun activateAbility(abilityInfo: AbilityInfo, spec: AbilitySpec) {
        val networkSource = abilityInfo.source[Networkable].remoteEntity
        val networkTarget = abilityInfo.target?.let { if (Networkable in it) it[Networkable].remoteEntity else null }

        if (gameScreen.isServer) {
            context(world) {
                doAbility(networkSource, networkTarget, abilityInfo.targetPos, spec)
            }

            gameScreen.networkServerSystem?.server?.sendToAllTCP(
                AbilityPacket(
                    spec.id,
                    networkSource,
                    abilityInfo.targetPos,
                    networkTarget
                )
            )
        } else {
            gameScreen.networkClientSystem?.client?.sendTCP(
                AbilityPacket(
                    spec.id,
                    networkSource,
                    abilityInfo.targetPos,
                    networkTarget
                )
            )
        }
    }

    context(_: World)
    private fun doAbility(remoteSource: Entity, remoteTarget: Entity?, targetPos: Vector2, spec: AbilitySpec) {
        spec.activate(AbilityInfo(remoteSource, targetPos, remoteTarget))
    }
}
