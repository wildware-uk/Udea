package dev.wildware.udea.network

import com.badlogic.gdx.utils.DefaultPool
import com.badlogic.gdx.utils.Pool
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.UniqueId
import dev.wildware.udea.Vector2
import dev.wildware.udea.ability.AbilitySpec
import dev.wildware.udea.ability.AbilityTargeting
import dev.wildware.udea.assets.Ability
import dev.wildware.udea.assets.AssetReference
import dev.wildware.udea.assets.Blueprint
import dev.wildware.udea.assets.EmptyReference
import dev.wildware.udea.network.CommandPacket.Command.None
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.objenesis.instantiator.ObjectInstantiator
import java.nio.ByteBuffer

/**
 * Annotating classes with this type does the following:
 * - The class will be registered with the KotlinX serializer (if [Serializable] annotation present)
 * - The class will be sent over the network
 * */
annotation class UdeaNetworked

@Serializable
sealed interface NetworkPacket : Pool.Poolable

@Serializable
@UdeaNetworked
data class EntityCreate(
    var entity: Entity,
    var blueprint: AssetReference<@Contextual Blueprint>,
    var networkComponents: List<Component<out @Contextual Any>>,
    var tags: List<UniqueId<out @Contextual Any>>,
) : NetworkPacket {
    override fun reset() {
        entity = NONE
        blueprint = EmptyReference as AssetReference<Blueprint>
        networkComponents = emptyList()
        tags = emptyList()
    }
}

@Serializable
@UdeaNetworked
data class EntityDestroy(
    var entity: Entity = NONE,
) : NetworkPacket {
    override fun reset() {
        entity = Entity.NONE
    }
}

@UdeaNetworked
data class EntityUpdate(
    var entity: Entity = NONE,
    val byteBuffer: ByteBuffer = ByteBuffer.allocate(2048),
    var tags: List<UniqueId<out Any>> = emptyList()
) : NetworkPacket {
    override fun reset() {
        entity = NONE
        byteBuffer.clear()
        tags = emptyList()
    }
}

@Serializable
@UdeaNetworked
data class AbilityPacket(
    var abilityId: Int = -1,
    var entity: Entity = NONE
) : NetworkPacket {
    override fun reset() {
        abilityId = -1
        entity = NONE
    }
}

fun <T> Pool<T>.obtainSafe() = synchronized(this) { obtain() }
fun <T> Pool<T>.freeSafe(instance: T) = synchronized(this) { free(instance) }

val EntityUpdatePool = DefaultPool { EntityUpdate() }
val EntityDestroyInstantiator = PooledInstantiator<EntityDestroy> { EntityDestroy(NONE) }
val AbilityPacketInstantiator = PooledInstantiator<AbilityPacket> { AbilityPacket() }

// TODO do better
data class CommandPacket(
    var command: Command,
    var args: List<String>
) : NetworkPacket {
    override fun reset() {
        command = None
        args = emptyList()
    }

    enum class Command {
        None, SetLevel, Kick, Ban
    }
}


object EntityUpdateSerializer : Serializer<EntityUpdate>() {
    override fun write(kryo: Kryo, output: Output, entityUpdate: EntityUpdate) {
        kryo.writeObject(output, entityUpdate.entity)

//        println("[EntityCreateSerializer] serializing $entityUpdate.blueprint.qualifiedName with $entityUpdate.networkComponents")

        output.writeBytes(entityUpdate.byteBuffer.array())
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out EntityUpdate>): EntityUpdate {
        input as ByteBufferInput
        val entityUpdate = EntityUpdatePool.obtainSafe()

        val entity = kryo.readObject(input, Entity::class.java)

        entityUpdate.byteBuffer.put(input.byteBuffer)
        entityUpdate.byteBuffer.flip()

        return entityUpdate.apply {
            this.entity = entity
            this.tags = emptyList() // TODO
        }
    }
}

class PooledInstantiator<T>(
    supplier: DefaultPool.PoolSupplier<T>
) : ObjectInstantiator<T> {
    private val pool = DefaultPool(supplier)

    fun free(instance: T) {
        pool.freeSafe(instance)
    }

    override fun newInstance(): T {
        return pool.obtainSafe()
    }
}
