package dev.wildware.math

import com.badlogic.gdx.math.Affine2 as GdxAffine2
import dev.wildware.network.Affine2Serializer
import dev.wildware.network.Vector2Serializer
import kotlinx.serialization.Serializable
import com.badlogic.gdx.math.Vector2 as GdxVec2

typealias Vector2 = @Serializable(with = Vector2Serializer::class) GdxVec2
typealias Affine2 = @Serializable(with = Affine2Serializer::class) GdxAffine2
