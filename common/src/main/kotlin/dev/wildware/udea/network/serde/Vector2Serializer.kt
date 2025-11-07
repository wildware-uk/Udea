package dev.wildware.udea.network.serde

import com.badlogic.gdx.math.Vector2
import dev.wildware.udea.network.InPlaceSerializer
import java.nio.ByteBuffer

@UdeaSerializer(Vector2::class)
object Vector2Serializer : InPlaceSerializer<Vector2> {
    override fun serialize(component: Vector2, byteBuffer: ByteBuffer) {
        byteBuffer.putFloat(component.x)
        byteBuffer.putFloat(component.y)
    }

    override fun deserialize(component: Vector2, byteBuffer: ByteBuffer) {
        component.x = byteBuffer.float
        component.y = byteBuffer.float
    }
}
