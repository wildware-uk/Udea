package dev.wildware.udea.ecs.system

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IntervalSystem
import dev.wildware.udea.Mouse
import dev.wildware.udea.ability.AbilityExec
import dev.wildware.udea.ability.AbilityInfo
import dev.wildware.udea.ecs.component.base.Networkable
import dev.wildware.udea.game
import dev.wildware.udea.network.AbilityPacket
import dev.wildware.udea.processAndRemoveEach

class AbilitySystem : IntervalSystem() {

    val abilityQueue = mutableListOf<AbilityPacket>()

    override fun onTick() {
        abilityQueue.processAndRemoveEach {
            val ability = it.ability
            context(world) {
                ability.activate(AbilityInfo(it.source, it.targetPos, it.target))
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
        }
    }

    fun activateAbility(abilityInfo: AbilityInfo, abilityExec: AbilityExec) {
        val target = Mouse.mouseTarget
        val targetPos = Mouse.mouseWorldPos

        val networkSource = Entity(abilityInfo.source[Networkable].remoteId, 0u)
        val networkTarget = target?.let { Entity(it[Networkable].remoteId, 0u) }

        if (game.isServer) {
            context(world) {
                abilityExec.activate(AbilityInfo(networkSource, targetPos, networkTarget))
            }

//            game.networkServerSystem!!.server.sendToAllTCP(
//                AbilityPacket(
//                    abilityExec,
//                    networkSource,
//                    targetPos,
//                    networkTarget
//                )
//            )
        } else {
//            game.networkClientSystem!!.client.sendTCP(
//                AbilityPacket(
//                    abilityExec,
//                    networkSource,
//                    targetPos,
//                    networkTarget
//                )
//            )
        }
    }

    fun activateAbility(entity: Entity, ability: AbilityExec) {
        val target = Mouse.mouseTarget
        val targetPos = Mouse.mouseWorldPos

        val networkSource = Entity(entity[Networkable].remoteId, 0u)
        val networkTarget = target?.let { Entity(it[Networkable].remoteId, 0u) }

        activateAbility(AbilityInfo(networkSource, targetPos, networkTarget), ability)
    }
}
