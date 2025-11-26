package dev.wildware.udea.network

import com.esotericsoftware.kryo.Kryo
import com.github.quillraven.fleks.*
import dev.wildware.udea.*
import dev.wildware.udea.UdeaReflections.udeaReflections
import dev.wildware.udea.assets.AssetReference
import dev.wildware.udea.ecs.component.NetworkAuthority
import dev.wildware.udea.ecs.component.NetworkComponent
import dev.wildware.udea.ecs.component.SyncStrategy
import dev.wildware.udea.ecs.component.SyncStrategy.Update
import dev.wildware.udea.ecs.component.base.Blueprint
import dev.wildware.udea.ecs.component.base.Dead
import dev.wildware.udea.ecs.component.base.Networkable
import dev.wildware.udea.ecs.system.AbilitySystem
import dev.wildware.udea.network.InPlaceSerializers.inPlaceSerializer
import dev.wildware.udea.network.serde.AssetReferenceSerializer
import dev.wildware.udea.network.serde.SerializableSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.serializer
import kotlin.reflect.full.findAnnotation

val udeaNetworkedTypes = udeaReflections
    .getTypesAnnotatedWith(UdeaNetworked::class.java)
    .filter { Component::class.java.isAssignableFrom(it) }

fun Kryo.registerDefaultPackets() {
    addDefaultSerializer(NetworkPacket::class.java, SerializableSerializer)

    register(EntityCreate::class.java, SerializableSerializer)
    register(AbilityPacket::class.java).apply {
        instantiator = AbilityPacketInstantiator
    }
    register(EntityDestroy::class.java).apply {
        instantiator = EntityDestroyInstantiator
    }
    register(EntityUpdate::class.java, EntityUpdateSerializer)

    register(Entity::class.java, SerializableSerializer)
    register(AssetReference::class.java, AssetReferenceSerializer)

    udeaNetworkedTypes.forEach {
        register(it)
    }
}

@OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
val cbor = Cbor {
    serializersModule = SerializersModule {
        //TODO register polymorphic types

        polymorphic(Component::class) {
            udeaNetworkedTypes
                .filterIsInstance<Class<Component<*>>>()
                .filter { it.kotlin.findAnnotation<UdeaNetworked>()?.registerKotlinXSerializer == true }
                .forEach {
                    println("Registering ${it.simpleName}")
                    subclass(it.kotlin, it.kotlin.serializer())
                }
        }

        polymorphic(UniqueId::class) {
            //Find a way to serialize Tags
            subclass(Dead::class, Dead.serializer())
        }
    }
}

fun World.processEntityCreate(create: EntityCreate, authority: NetworkAuthority) {
    val (ent, blueprint, networkComponents, tags) = create

    val entity = getNetworkEntityOrNull(ent)

    if (entity == null) {
        if (authority != NetworkAuthority.Server) return
        val blueprint = blueprint.value
        val entity = blueprint.newInstance(this) {
            it[Networkable].remoteEntity = ent //TODO this may incorrect

            it += networkComponents
                .filter { it.getNetworkData().checkNetworkAuthority(authority) }
            it += tags as List<EntityTag>
        }

        if (hasAuthority(entity) && blueprint.name == "player") {
            gameScreen.localPlayer = entity // TODO this may be useless
        }
    }
}

fun World.processEntityUpdate(update: EntityUpdate, authority: NetworkAuthority) {
    val remoteEntity = getNetworkEntityOrNull(update.entity) ?: return
    val entitySnapshot = snapshotOf(remoteEntity)
    val networkComponents = entitySnapshot.components
        .filter { it.isNetworkable() && it.getNetworkData().shouldSync(Update, authority) }

    networkComponents.forEach {
        val serializer = it::class.inPlaceSerializer() as InPlaceSerializer<Component<out Any>>
        serializer.deserialize(it, update.byteBuffer)
    }

    EntityUpdatePool.freeSafe(update)
}

fun World.processEntityDestroy(entityDestroy: EntityDestroy) {
    val (id) = entityDestroy

    getNetworkEntityOrNull(id)?.configure { entity ->
        this@processEntityDestroy -= entity
    }

    EntityDestroyInstantiator.free(entityDestroy)
}

fun World.processAbilityPacket(packet: AbilityPacket) {
    system<AbilitySystem>().abilityQueue.add(packet)
}

fun Entity.toEntityUpdate(world: World, authority: NetworkAuthority): EntityUpdate {
    val snapshot = world.snapshotOf(this@toEntityUpdate)

    val networkedComponents = snapshot.components
        .filter { it.isNetworkable() && it.getNetworkData().shouldSync(Update, authority) }

    val remoteEntity = with(world) { this@toEntityUpdate[Networkable].remoteEntity }

    val entityUpdate = EntityUpdatePool.obtainSafe().apply {
        this.entity = remoteEntity
        // TODO TAGS
    }

    networkedComponents.forEach {
        val serializer = it::class.inPlaceSerializer() as InPlaceSerializer<Component<out Any>>
        serializer.serialize(it, entityUpdate.byteBuffer)
    }


    return entityUpdate
}

fun Entity.toEntityCreate(world: World, authority: NetworkAuthority): EntityCreate {
    with(world) {
        val snapshot = world.snapshotOf(this@toEntityCreate)

        val networkedComponents = snapshot.components
            .filter { it.isNetworkable() && it.getNetworkData().shouldSync(SyncStrategy.Create, authority) }

        return EntityCreate(
            this@toEntityCreate[Networkable].remoteEntity,
            this@toEntityCreate[Blueprint].blueprint,
            networkedComponents,
            snapshot.tags,
        )
    }
}

fun NetworkComponent<*>.shouldSync(syncStrategy: SyncStrategy, networkAuthority: NetworkAuthority): Boolean {
    return this.checkSyncStrategy(syncStrategy) && this.checkNetworkAuthority(networkAuthority)
}
