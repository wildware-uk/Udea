package dev.wildware.udea.network.serde

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import dev.wildware.udea.Vector2
import dev.wildware.udea.network.InPlaceSerializer
import dev.wildware.udea.network.UdeaSerializer
import java.nio.ByteBuffer

@UdeaSerializer(Vector2::class)
@UdeaSerializer(com.badlogic.gdx.math.Vector2::class)
object Vector2Serializer : InPlaceSerializer<Vector2?> {
    override fun serialize(component: Vector2?, data: ByteBuffer) {
        if (component == null) return

        data.putFloat(component.x)
        data.putFloat(component.y)
    }

    override fun deserialize(component: Vector2?, data: ByteBuffer) {
        if (component == null) return

        component.x = data.getFloat()
        component.y = data.getFloat()
    }
}
