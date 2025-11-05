package dev.wildware.udea.network

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.getNetworkEntityOrNull
import dev.wildware.udea.network.EntityContextKSerializer.Companion.contextValid
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object EntityUpdateSerializer : WorldContextKSerializer<EntityUpdate> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("EntityUpdate") {
        // First field is the entity ID that we'll handle manually
        element("id", Int.serializer().descriptor)

        // Add the remaining fields from the generated serializer
//        val generatedDescriptor = EntityUpdate.generatedSerializer().descriptor
//        for (i in 1 until generatedDescriptor.elementsCount) {
//            element(
//                generatedDescriptor.getElementName(i),
//                generatedDescriptor.getElementDescriptor(i),
//                isOptional = generatedDescriptor.isElementOptional(i)
//            )
//        }
    }

    override fun World.deserialize(decoder: Decoder): EntityUpdate {
//        val entityId = decoder.decodeInt()
//        val entity = getNetworkEntityOrNull(entityId)
//        EntityContextKSerializer.withEntity(entity) {
//            return EntityUpdate.generatedSerializer().deserialize(decoder).also {
//                it.valid = contextValid()
//            }
//        }
        TODO()
    }

    override fun World.serialize(encoder: Encoder, obj: EntityUpdate) {
//        encoder.encodeInt(obj.id)
//        val entity = Entity(obj.id, 0u)
//        EntityContextKSerializer.withEntity(entity) {
//            return EntityUpdate.generatedSerializer().serialize(encoder, obj)
//        }
    }
}
