package dev.wildware.ecs.system

import com.badlogic.gdx.math.Vector2
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IntervalSystem
import dev.wildware.*
import dev.wildware.ability.Ability
import dev.wildware.ability.AbilityInfo
import dev.wildware.ecs.component.Networkable
import dev.wildware.network.AbilityPacket
import dev.wildware.udea.assets.Asset

class AbilitySystem : IntervalSystem() {

    val abilityQueue = mutableListOf<AbilityPacket>()

    override fun onTick() {
        abilityQueue.processAndRemoveEach {
            val ability = it.ability
            ability.value.activate(world, AbilityInfo(it.source, it.targetPos, it.target))

            if (game.isServer) {
                game.networkServerSystem.server.sendToAllTCP(AbilityPacket(ability, it.source, it.targetPos, it.target))
            }
        }
    }

    fun activateAbility(entity: Entity, ability: Asset<Ability>) {
        with(world) {
            val target = Mouse.mouseTarget
            val targetPos = Mouse.mouseWorldPos

            val networkSource = Entity(entity[Networkable].remoteId, 0u)
            val networkTarget = target?.let { Entity(it[Networkable].remoteId, 0u) }

            if (game.isServer) {
                ability.value.activate(world, AbilityInfo(networkSource, targetPos, networkTarget))
                game.networkServerSystem.server.sendToAllTCP(
                    AbilityPacket(
                        ability,
                        networkSource,
                        targetPos,
                        networkTarget
                    )
                )
            } else {
                game.networkClientSystem.client.sendTCP(AbilityPacket(ability, networkSource, targetPos, networkTarget))
            }
        }
    }
}

