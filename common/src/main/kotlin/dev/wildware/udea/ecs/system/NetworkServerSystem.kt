package dev.wildware.udea.ecs.system

import com.esotericsoftware.kryonet.Connection
import com.esotericsoftware.kryonet.Listener
import com.esotericsoftware.kryonet.Server
import com.github.quillraven.fleks.*
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.assets.GameConfig
import dev.wildware.udea.ecs.component.NetworkAuthority
import dev.wildware.udea.ecs.component.base.Networkable
import dev.wildware.udea.network.*
import dev.wildware.udea.processAndRemoveEach
import java.io.IOException
import java.util.*

class NetworkServerSystem(
    world: World
) : IteratingSystem(
    family = world.family { all(Networkable) },
    world = world,
    interval = Fixed(1 / 20F)
), FamilyOnAdd, FamilyOnRemove {
    val server = Server(1024 * 8, 1024 * 64).apply {
        kryo.registerDefaultPackets()
    }

    val gameConfig = Assets.filterIsInstance<GameConfig>().first()
    private val inputQueue = Collections.synchronizedList(mutableListOf<NetworkPacket>())
    private val playerJoiningQueue = Collections.synchronizedList(mutableListOf<Int>())
    private val newEntities = Collections.synchronizedList(mutableListOf<Entity>())

    var started = false

    init {
        try {
            server.bind(28855, 28856)
            server.start()
            started = true

            server.addListener(object : Listener {
                override fun connected(connection: Connection) {
                    println("Client connected ${connection.id}")
                    playerJoiningQueue += connection.id
                }

                override fun received(connection: Connection, obj: Any) {
//                    println("[SERVER] received $obj")
                    when (obj) {
                        is NetworkPacket -> inputQueue += obj
                    }
                }
            })
        } catch (e: IOException) {
            println("Failed to start server ${e.message}")
        }
    }

    override fun onTick() {
        inputQueue.processAndRemoveEach { packet ->
            when (packet) {
                is EntityUpdate -> world.processEntityUpdate(packet, NetworkAuthority.Client)
                is AbilityPacket -> world.processAbilityPacket(packet)
                else -> {} //disregard other packets
            }
        }

        if (server.connections.isNotEmpty()) {
            super.onTick()
        }

        newEntities.processAndRemoveEach {
            server.sendToAllTCP(it.toEntityCreate(world, NetworkAuthority.Server))
        }

        playerJoiningQueue.processAndRemoveEach { id ->
            val defaultCharacter = gameConfig.defaultCharacter

            if (defaultCharacter == null) {
                println("No default character specified")
                return@processAndRemoveEach
            }

            defaultCharacter.value.newInstance(world).apply {
                println("Setting networkable to $id")
                this.configure {
                    it += Networkable(id)
                }
            }


            // TODO
//            server.sendToTCP(
//                id,
//                CommandPacket(SetLevel, listOf(gameConfig.defaultLevel!!.path))
//            )

            // Sending existing entities to
            val creates = getEntityCreates()
            creates.forEach {
                println("SENDING CREATE $it")
                server.sendToTCP(id, it)
            }
        }
    }

    override fun onTickEntity(entity: Entity) {
        val snapshot = entity.toEntityUpdate(world, NetworkAuthority.Server)
        server.sendToAllUDP(snapshot)
    }

    override fun onAddEntity(entity: Entity) {
        if (server.connections.isEmpty()) return
        newEntities += entity
    }

    override fun onRemoveEntity(entity: Entity) {
        if (server.connections.isEmpty()) return
        server.sendToAllTCP(EntityDestroy(entity))
    }

    override fun onDispose() {
        server.stop()
    }

    fun getEntityCreates(): List<EntityCreate> {
        return family.map {
            it.toEntityCreate(world, NetworkAuthority.Server)
        }
    }
}
