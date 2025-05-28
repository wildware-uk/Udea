package dev.wildware.network

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.game
import dev.wildware.network.EntityContextKSerializer.Companion.CurrentEntity
import dev.wildware.network.EntityContextKSerializer.Companion.invalidateContext
import dev.wildware.network.WorldContextKSerializer.Companion.CurrentWorld
import kotlinx.serialization.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract


/**
 * Serializes types with an entity context.
 * */
@OptIn(ExperimentalContracts::class)
interface EntityContextKSerializer<T> : KSerializer<T> {
    fun deserialize(decoder: Decoder, entity: Entity): T

    override fun deserialize(decoder: Decoder): T {
        val currentEntity = CurrentEntity
        val currentWorld = CurrentWorld

        require(currentWorld != null) { "Must be run with a world in context!" }
        require(currentEntity != null) { "Must be run with an entity in context!" }

        return deserialize(decoder, currentEntity)
    }

    companion object {
        @PublishedApi
        internal var CurrentEntity: Entity? = null

        @PublishedApi
        internal var ContextValid = false

        fun invalidateContext() {
            ContextValid = false
        }

        fun contextValid() = ContextValid

        inline fun withEntity(entity: Entity?, block: () -> Unit) {
            contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }

            try {
                CurrentEntity = entity
                ContextValid = true
                block()
            } finally {
                CurrentEntity = null
                ContextValid = false
            }
        }
    }
}

/**
 * Serializes entity components in-place.
 * */
interface ComponentKSerializer<T : Component<T>> : KSerializer<T> {
    val type: ComponentType<T>

    /**
     * An empty instance to serialize into, in the circumstance of no entity in context.
     * */
    val dummy: T

    fun deserialize(decoder: Decoder, component: T)

    override fun deserialize(decoder: Decoder): T {
        val currentEntity = CurrentEntity
        val currentWorld = CurrentWorld

        require(currentWorld != null) { "Must be run with a world in context!" }

        with(currentWorld) {
            val component = currentEntity?.get(type)
                ?: dummy.also { invalidateContext() }
            deserialize(decoder, component)
            return component
        }
    }
}

@OptIn(ExperimentalContracts::class)
interface WorldContextKSerializer<T> : KSerializer<T> {

    fun World.deserialize(decoder: Decoder): T
    fun World.serialize(encoder: Encoder, obj: T)

    override fun serialize(encoder: Encoder, value: T) {
        val currentWorld = CurrentWorld
        require(currentWorld != null) { "Must be run with a world in context!" }
        currentWorld.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): T {
        val currentWorld = CurrentWorld
        require(currentWorld != null) { "Must be run with a world in context!" }
        return currentWorld.deserialize(decoder)
    }

    companion object {
        @PublishedApi
        internal var CurrentWorld: World? = null

        inline fun withWorld(world: World, block: () -> Unit) {
            contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }

            try {
                CurrentWorld = world
                block()
            } finally {
                CurrentWorld = null
            }
        }
    }
}

@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
object SerializableSerializer : Serializer<Any>() {
    override fun write(kryo: Kryo, output: Output, obj: Any) {
        WorldContextKSerializer.withWorld(game.world) {
            val serializer = obj::class.serializerOrNull()
                ?: error("No serializer found for ${obj::class}")
            val bytes = cbor.encodeToByteArray(serializer as SerializationStrategy<Any>, obj)
            output.writeInt(bytes.size)
            output.writeBytes(bytes)
        }
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out Any>): Any {
        WorldContextKSerializer.withWorld(game.world) {
            val size = input.readInt()
            val bytes = input.readBytes(size)
            val kClass = type.kotlin
            val serializer = kClass.serializerOrNull()
                ?: error("No serializer found for $type")

            return cbor.decodeFromByteArray(serializer as DeserializationStrategy<Any>, bytes)
        }
    }
}

interface RawSerializerDelegate {
    fun createRaw(component: Component<*>): ComponentDelegate
}

/**
 * Allows a class to delegate its serialization to a simple object, and apply that data later.
 * */
interface SerializerDelegate<COMP : Component<COMP>, out DELEGATE : ComponentDelegate> : RawSerializerDelegate {
    fun create(component: COMP): DELEGATE

    @Suppress("UNCHECKED_CAST")
    override fun createRaw(component: Component<*>): ComponentDelegate {
        return this.create(component as COMP)
    }
}

/**
 * A interface for data classes to delegate serialization of a component.
 * */
interface ComponentDelegate {
    fun World.applyToEntity(entity: Entity)
}
