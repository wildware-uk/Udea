package dev.wildware.udea.ecs.system

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.Vector2
import dev.wildware.udea.ability.AbilityActivation
import dev.wildware.udea.ability.AbilityExec
import dev.wildware.udea.ability.AbilityInfo
import dev.wildware.udea.assets.Ability
import dev.wildware.udea.assets.AssetReference
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.ecs.component.base.Debug
import dev.wildware.udea.ecs.component.base.Networkable
import dev.wildware.udea.gameScreen
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

            abilities.currentAbility?.let {
                if (it.ability.blockedBy.any { tag -> abilities.hasGameplayEffectTag(tag) }) {
                    it.abilityFinished = true
                }

                if (it.abilityFinished) {
                    entity.getOrNull(Debug)?.addMessage("Ability Ended", 0.5F)
                    abilities.currentAbility = null
                }
            }
        }

        abilityQueue.processAndRemoveEach {
            val ability = it.ability!!
            context(world) {
                val remoteEntity = world.getNetworkEntityOrNull(it.source!!)
                val remoteTarget = it.target?.let { t -> world.getNetworkEntityOrNull(t) }

                if (remoteEntity != null) {
                    doAbility(remoteEntity, remoteTarget, it.targetPos!!, ability.value)
                }
            }

            if (gameScreen.isServer) {
                gameScreen.networkServerSystem!!.server.sendToAllTCP(
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

        if (gameScreen.isServer) {
            context(world) {
                doAbility(networkSource, networkTarget, abilityInfo.targetPos, ability)
            }

            gameScreen.networkServerSystem?.server?.sendToAllTCP(
                AbilityPacket(
                    ability.reference as AssetReference<Ability>,
                    networkSource,
                    abilityInfo.targetPos,
                    networkTarget
                )
            )
        } else {
            gameScreen.networkClientSystem?.client?.sendTCP(
                AbilityPacket(
                    ability.reference as AssetReference<Ability>,
                    networkSource,
                    abilityInfo.targetPos,
                    networkTarget
                )
            )
        }
    }

    context(_: World)
    private fun doAbility(remoteSource: Entity, remoteTarget: Entity?, targetPos: Vector2, ability: Ability) {
        if (remoteSource[Abilities].currentAbility == null) {
            if (!canCast(ability, remoteSource)) return

            val activation = AbilityActivation(ability, AbilityInfo(remoteSource, targetPos, remoteTarget))
            remoteSource[Abilities].currentAbility = activation

            context(activation) {
                ability.execInstance()
                    .activate(AbilityInfo(remoteSource, targetPos, remoteTarget))
            }
        }
    }

    private fun canCast(
        ability: Ability,
        remoteSource: Entity
    ): Boolean {
        val onCooldown =
            ability.cooldownEffect == null || remoteSource[Abilities].hasGameplayEffect(ability.cooldownEffect)
        if (onCooldown) return true

        val isBlocked = ability.blockedBy.any { remoteSource[Abilities].hasGameplayEffectTag(it) }

        return !isBlocked
    }

    private fun Ability.execInstance() =
        exec.primaryConstructor!!.call() as AbilityExec
}
