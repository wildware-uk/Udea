package dev.wildware.udea

import dev.wildware.udea.network.Vector2Serializer
import kotlinx.serialization.Serializable
import com.badlogic.gdx.math.Vector2 as GdxVec2

typealias Vector2 = @Serializable(with = Vector2Serializer::class) GdxVec2
