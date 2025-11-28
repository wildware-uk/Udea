package dev.wildware.udea.example.component

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.gameScreen
import dev.wildware.udea.example.assets.Effect as EffectAsset

class Effect(
    val effect: EffectAsset,
    duration: Float = effect.duration
) : Component<Effect> {
    val destroyTime = gameScreen.time + duration

    override fun type() = Effect

    companion object : ComponentType<Effect>()
}
