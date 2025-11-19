package dev.wildware.udea.network

import com.badlogic.gdx.utils.Pool
import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.UniqueId
import dev.wildware.udea.Vector2
import dev.wildware.udea.ability.AbilityExec
import dev.wildware.udea.assets.Blueprint
import dev.wildware.udea.ecs.ComponentDelegate
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer

annotation class UdeaNetworked

@Serializable
sealed interface NetworkPacket : Pool.Poolable

@UdeaNetworked
data class EntityCreate(
    val id: Int,
    val blueprint: @Contextual Blueprint,
    val networkComponents: List<Component<out @Contextual Any>>,
    val tags: List<UniqueId<out @Contextual Any>>,
    val componentDelegates: List<ComponentDelegate>,
) : NetworkPacket {
    override fun reset() {
        TODO("Not yet implemented")
    }
}

@UdeaNetworked
data class EntityDestroy(
    val id: Int,
) : NetworkPacket {
    override fun reset() {
        TODO("Not yet implemented")
    }
}

@UdeaNetworked
data class EntityUpdate(
    var id: Int,
    val byteBuffer: ByteBuffer
) : NetworkPacket {
    override fun reset() {
        id = -1
        byteBuffer.clear()
    }
}

@UdeaNetworked
data class AbilityPacket(
    val ability: AbilityExec,
    val source: Entity,
    val targetPos: Vector2,
    val target: Entity?
) : NetworkPacket {
    override fun reset() {
        TODO("Not yet implemented")
    }
}
