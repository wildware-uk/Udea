package dev.wildware.udea.ecs.system

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.Mouse
import dev.wildware.udea.ability.AbilityActivation
import dev.wildware.udea.ability.AbilityExec
import dev.wildware.udea.ability.AbilityInfo
import dev.wildware.udea.assets.Ability
import dev.wildware.udea.assets.AssetReference
import dev.wildware.udea.assets.Network
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.ecs.component.base.Networkable
import dev.wildware.udea.game
import dev.wildware.udea.getNetworkEntityOrNull
import dev.wildware.udea.network.AbilityPacket
import dev.wildware.udea.network.AbilityPacketInstantiator
import dev.wildware.udea.processAndRemoveEach
import kotlin.reflect.full.primaryConstructor

class AbilitySystem : IntervalSystem() {

    val family = family { all(Abilities) }
    val abilityQueue = mutableListOf<AbilityPacket>()

    override fun onTick() {
        family.forEach { entity ->
            val abilities = entity[Abilities]

            if (abilities.currentAbility?.abilityFinished == true) {
                abilities.currentAbility = null
            }
        }

        abilityQueue.processAndRemoveEach {
            val ability = it.ability!!
            context(world) {
                val remoteEntity = world.getNetworkEntityOrNull(it.source!!)
                val remoteTarget = it.target?.let { t -> world.getNetworkEntityOrNull(t) }

                if (remoteEntity != null) {
                    val activation =
                        AbilityActivation(ability.value, AbilityInfo(remoteEntity, it.targetPos!!, remoteTarget))
                    remoteEntity[Abilities].currentAbility = activation
                    ability.value.execInstance(activation)
                        .activate(AbilityInfo(remoteEntity, it.targetPos!!, remoteTarget))
                }
            }

            if (game.isServer) {
                game.networkServerSystem!!.server.sendToAllTCP(
                    AbilityPacket(
                        ability,
                        it.source,
                        it.targetPos,
                        it.target
                    )
                )
            }

            AbilityPacketInstantiator.free(it)
        }
    }

    fun activateAbility(abilityInfo: AbilityInfo, ability: Ability) {
        val networkSource = abilityInfo.source[Networkable].remoteEntity
        val networkTarget = abilityInfo.target?.let { if (Networkable in it) it[Networkable].remoteEntity else null }

        if (game.isServer) {
            context(world) {
                val activation = AbilityActivation(ability, abilityInfo)
                abilityInfo.source[Abilities].currentAbility = activation
                ability.execInstance(activation).activate(AbilityInfo(networkSource, abilityInfo.targetPos, networkTarget))
            }

            game.networkServerSystem?.server?.sendToAllTCP(
                AbilityPacket(
                    ability.reference as AssetReference<Ability>,
                    networkSource,
                    abilityInfo.targetPos,
                    networkTarget
                )
            )
        } else {
            game.networkClientSystem?.client?.sendTCP(
                AbilityPacket(
                    ability.reference as AssetReference<Ability>,
                    networkSource,
                    abilityInfo.targetPos,
                    networkTarget
                )
            )
        }
    }

    fun Ability.execInstance(abilityActivation: AbilityActivation) =
        exec.primaryConstructor!!.call(abilityActivation) as AbilityExec
}
