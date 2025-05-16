package dev.wildware.udea.network

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.UniqueId
import dev.wildware.udea.Vector2
import dev.wildware.udea.ability.Ability
import dev.wildware.udea.assets.Blueprint
import dev.wildware.udea.ecs.ComponentDelegate
import kotlinx.serialization.*

annotation class Networked

@Serializable
sealed interface NetworkPacket

@Serializable
@Networked
data class EntityCreate(
    val id: Int,
    val blueprint: @Contextual Blueprint,
    val networkComponents: List<Component<out @Contextual Any>>,
    val tags: List<UniqueId<out @Contextual Any>>,
    val componentDelegates: List<ComponentDelegate>,
) : NetworkPacket

@Serializable
@Networked
data class EntityDestroy(
    val id: Int,
) : NetworkPacket

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
) : NetworkPacket

@Serializable
@Networked
data class AbilityPacket(
    val ability: Ability,
    val source: Entity,
    val targetPos: Vector2,
    val target: Entity?
) : NetworkPacket
