package dev.wildware.udea.ecs.system
import com.esotericsoftware.kryonet.Client
import com.esotericsoftware.kryonet.Connection
import com.esotericsoftware.kryonet.Listener
import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.ecs.component.Networkable
import dev.wildware.game
import dev.wildware.network.*
import dev.wildware.network.NetworkAuthority.Client
import dev.wildware.network.NetworkAuthority.Server
import dev.wildware.processAndRemoveEach
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
        val networkUpdates = world.getNetworkUpdates()
        networkUpdates.forEach {
            client.sendUDP(it)
        }

        inputQueue.processAndRemoveEach { packet ->
            when (packet) {
                is EntityCreate -> world.processEntityCreate(packet, Server)
                is EntityUpdate -> world.processEntityUpdate(packet, Server)
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
            .map { it.toEntityUpdate(world, Client) }
    }
}
