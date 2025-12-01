package dev.wildware.udea.ecs.component.audio

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.assets.AudioMap

class AudioMapHolder(
    val audioMap: AudioMap
) : Component<AudioMapHolder> {

    inline fun <reified T : AudioMap> get() = audioMap as? T

    override fun type() = AudioMapHolder

    companion object : ComponentType<AudioMapHolder>()
}
