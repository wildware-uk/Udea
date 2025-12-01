package dev.wildware.udea.network.serde

import dev.wildware.udea.network.*
import java.nio.ByteBuffer

@UdeaSerializer(ArrayList::class)
object ArrayListSerializer : InPlaceSerializer<ArrayList<out Any>> {
    override fun serialize(component: ArrayList<out Any>, data: ByteBuffer) {
        data.putInt(component.size)
        component.forEach {
            polymorphicSerializerFor(it::class).serialize(it, data)
        }
    }

    override fun deserialize(component: ArrayList<out Any>, data: ByteBuffer) {
        val size = data.getInt()

        if(component.size != size) error("Size mismatch: $size != ${component.size}")
        component.forEach {
            polymorphicSerializerFor(it::class).deserialize(it, data)
        }
    }
}
