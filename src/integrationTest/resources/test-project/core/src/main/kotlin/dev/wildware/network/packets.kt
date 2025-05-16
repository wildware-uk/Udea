package dev.wildware.network

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.UniqueId
import dev.wildware.udea.assets.Asset
import dev.wildware.ability.Ability
import dev.wildware.ecs.Blueprint
import dev.wildware.math.Vector2
import dev.wildware.network.Networked
import kotlinx.serialization.*

@Serializable
sealed interface NetworkPacket

@Serializable
@Networked
data class EntityCreate(
    val id: Int,
    val blueprint: Asset<@Contextual Blueprint>,
    val networkComponents: List<Component<out @Contextual Any>>,
    val tags: List<UniqueId<out @Contextual Any>>,
    val componentDelegates: List<ComponentDelegate>,
) : dev.wildware.udea.network.NetworkPacket

@Serializable
@Networked
data class EntityDestroy(
    val id: Int,
) : dev.wildware.udea.network.NetworkPacket

@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = EntityUpdateSerializer::class)
@Networked
data class EntityUpdate(
    val id: Int,
    val networkComponents: List<Component<out @Contextual Any>>,
    val tags: List<UniqueId<out @Contextual Any>>,
    val componentDelegates: List<ComponentDelegate>,
    @Transient
    var valid: Boolean = false
) : dev.wildware.udea.network.NetworkPacket

@Serializable
@Networked
data class AbilityPacket(
    val ability: Asset<Ability>,
    val source: Entity,
    val targetPos: Vector2,
    val target: Entity?
) : dev.wildware.udea.network.NetworkPacket
