package dev.wildware.udea.ecs.system

import com.esotericsoftware.kryonet.Client
import com.esotericsoftware.kryonet.Connection
import com.esotericsoftware.kryonet.Listener
import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.ecs.NetworkAuthority
import dev.wildware.udea.ecs.component.base.Networkable
import dev.wildware.udea.game
import dev.wildware.udea.network.AbilityPacket
import dev.wildware.udea.network.EntityCreate
import dev.wildware.udea.network.EntityDestroy
import dev.wildware.udea.network.EntityUpdate
import dev.wildware.udea.network.NetworkPacket
import dev.wildware.udea.network.processAbilityPacket
import dev.wildware.udea.network.processEntityCreate
import dev.wildware.udea.network.processEntityDestroy
import dev.wildware.udea.network.processEntityUpdate
import dev.wildware.udea.network.registerDefaultPackets
import dev.wildware.udea.network.toEntityUpdate
import dev.wildware.udea.processAndRemoveEach
import java.util.*

class NetworkClientSystem : IntervalSystem() {
    val client = Client(1024 * 64, 1024 * 64).apply {
        kryo.registerDefaultPackets()
    }

    val inputQueue = Collections.synchronizedList(mutableListOf<NetworkPacket>())
    val myEntities = family { all(Networkable) }

    fun start(host: String, tcpPort: Int, udpPort: Int) {
        client.start()
        client.addListener(object : Listener {
            override fun connected(connection: Connection) {
                println("Connected ${connection.id}")
                game.clientId = connection.id
            }

            override fun received(connection: Connection, obj: Any) {
                when (obj) {
                    is NetworkPacket -> inputQueue += obj
                }
            }
        })

        client.connect(5000, host, tcpPort, udpPort)
    }

    override fun onTick() {
//        val networkUpdates = world.getNetworkUpdates()
//        networkUpdates.forEach {
//            client.sendUDP(it)
//        }

        inputQueue.processAndRemoveEach { packet ->
            when (packet) {
                is EntityCreate -> world.processEntityCreate(packet, NetworkAuthority.Server)
                is EntityUpdate -> world.processEntityUpdate(packet, NetworkAuthority.Server)
                is EntityDestroy -> world.processEntityDestroy(packet)
                is AbilityPacket -> world.processAbilityPacket(packet)
            }
        }
    }

    override fun onDispose() {
        client.stop()
    }

    fun World.getNetworkUpdates(): List<EntityUpdate> {
        return myEntities
            .filter { it[Networkable].owner == client.id }
            .map { it.toEntityUpdate(world, NetworkAuthority.Client) }
    }
}
