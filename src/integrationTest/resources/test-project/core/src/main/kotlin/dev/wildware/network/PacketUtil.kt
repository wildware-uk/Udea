package dev.wildware.network

import com.esotericsoftware.kryo.Kryo
import com.github.quillraven.fleks.*
import dev.wildware.udea.assets.Asset
import dev.wildware.ability.AttributeSet
import dev.wildware.ecs.component.BlueprintComp
import dev.wildware.ecs.component.Dead
import dev.wildware.ecs.component.Networkable
import dev.wildware.ecs.component.RigidBodyPacketDelegate
import dev.wildware.ecs.newInstance
import dev.wildware.ecs.system.AbilitySystem
import dev.wildware.getNetworkData
import dev.wildware.getNetworkEntityOrNull
import dev.wildware.hasAuthority
import dev.wildware.isNetworkable
import dev.wildware.network.NetworkAuthority.Server
import dev.wildware.network.SyncStrategy.Create
import dev.wildware.spellcastgame.CharacterAttributeSet
import dev.wildware.spellcastgame.gameScreen
import dev.wildware.spellcastgame.spell.modifiers.ParamValue
import dev.wildware.udea.assets.AssetReferenceSerializer
import dev.wildware.network.Networked
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.serializer
import org.reflections.Reflections
import kotlin.reflect.full.companionObjectInstance

val reflections = Reflections("dev.wildware")
val networkedTypes: Set<Class<*>> = reflections.getTypesAnnotatedWith(Networked::class.java)
val networkedComponents = networkedTypes
    .filter { Component::class.java.isAssignableFrom(it) }

fun Kryo.registerDefaultPackets() {
    addDefaultSerializer(NetworkPacket::class.java, SerializableSerializer)
    networkedTypes.forEach {
        register(it)
    }
}

@OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
val cbor = Cbor {
    serializersModule = SerializersModule {
        contextual(Asset::class, AssetReferenceSerializer)
        contextual(EntityUpdate::class, EntityUpdateSerializer)

        polymorphic(ParamValue::class) {
            subclass(Float::class, Float::class.serializer())
            subclass(Int::class, Int::class.serializer())
            subclass(Asset::class, AssetReferenceSerializer)
            subclass(Boolean::class, Boolean::class.serializer())
        }

        polymorphic(Component::class) {
            println(networkedComponents)
            networkedComponents
                .filterIsInstance<Class<Component<*>>>()
                .filter { !(it.kotlin.companionObjectInstance as NetworkComponent<*>).isDelegated }
                .forEach {
                    println("Registering ${it.simpleName}")
                    subclass(it.kotlin, it.kotlin.serializer())
                }
        }

        polymorphic(AttributeSet::class) {
            subclass(CharacterAttributeSet::class, CharacterAttributeSet.serializer())
        }

        polymorphic(UniqueId::class) {
            //Find a way to serialize Tags
            subclass(Dead::class, Dead.serializer())
        }

        polymorphic(ComponentDelegate::class) {
            subclass(RigidBodyPacketDelegate::class, RigidBodyPacketDelegate.serializer())
        }
    }
}

fun World.processEntityCreate(create: EntityCreate, authority: NetworkAuthority) {
    val (id, blueprint, networkComponents, tags, delegates) = create

    val entity = getNetworkEntityOrNull(id)

    if (entity == null) {
        if (authority != Server) return
        val entity = blueprint.newInstance(this) {
            it[Networkable].remoteId = id

            it += networkComponents
                .filter { it.getNetworkData().checkNetworkAuthority(authority) }
            it += tags as List<EntityTag>
        }.apply {
            delegates.forEach {
                with(it) {
                    applyToEntity(this@apply)
                }
            }
        }

        if (hasAuthority(entity) && blueprint.value.name == "player") {
            gameScreen.localPlayer = entity
        }
    }
}

fun World.processEntityUpdate(update: EntityUpdate, authority: NetworkAuthority) {
    if (!update.valid) return

    val (id, networkComponents, tags, delegates) = update

    val entity = getNetworkEntityOrNull(id)

    if (entity != null) {
        entity.configure { entity ->
            entity += networkComponents
                .filter { it.getNetworkData().checkNetworkAuthority(authority) }
            entity += tags as List<EntityTag>
        }

        delegates.forEach {
            with(it) {
                applyToEntity(entity)
            }
        }
    }
}

fun World.processEntityDestroy(entityDestroy: EntityDestroy) {
    val (id) = entityDestroy

    getNetworkEntityOrNull(id)?.configure { entity ->
        this@processEntityDestroy -= entity
    }
}

fun World.processAbilityPacket(packet: AbilityPacket) {
    system<AbilitySystem>().abilityQueue.add(packet)
}

fun Entity.toEntityUpdate(world: World, authority: NetworkAuthority): EntityUpdate {
    with(world) {
        val snapshot = world.snapshotOf(this@toEntityUpdate)

        val networkedComponents = snapshot.components
            .filter { it.isNetworkable() && it.getNetworkData().shouldSync(SyncStrategy.Update, authority) }

        val (delegates, nonDelegates) = networkedComponents.partition { it.getNetworkData().isDelegated }

        val delegateInstances = delegates.map { component ->
            component.getNetworkData().delegate!!.createRaw(component)
        }

        return EntityUpdate(
            this@toEntityUpdate[Networkable].remoteId,
            nonDelegates,
            snapshot.tags,
            delegateInstances
        )
    }
}

fun Entity.toEntityCreate(world: World, authority: NetworkAuthority): EntityCreate {
    with(world) {
        val snapshot = world.snapshotOf(this@toEntityCreate)

        val networkedComponents = snapshot.components
            .filter { it.isNetworkable() && it.getNetworkData().shouldSync(Create, authority) }

        val (delegates, nonDelegates) = networkedComponents
            .partition { it.getNetworkData().isDelegated }

        val delegateInstances = delegates.map { component ->
            component.getNetworkData().delegate!!.createRaw(component)
        }

        return EntityCreate(
            this@toEntityCreate[Networkable].remoteId,
            this@toEntityCreate[BlueprintComp].blueprint,
            nonDelegates,
            snapshot.tags,
            delegateInstances
        )
    }
}

private fun NetworkComponent<*>.shouldSync(syncStrategy: SyncStrategy, networkAuthority: NetworkAuthority): Boolean {
    return this.checkSyncStrategy(syncStrategy) && this.checkNetworkAuthority(networkAuthority)
}
