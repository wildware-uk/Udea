package dev.wildware.udea.ecs.component.base

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.gameScreen

class Debug : Component<Debug> {
    val debugMessages = mutableListOf<DebugMessage>()

    fun addMessage(message: String, time: Float = 0.0F) {
        debugMessages += DebugMessage(message, gameScreen.time + time)
    }

    override fun type()= Debug
    companion object : ComponentType<Debug>()
}

data class DebugMessage(
    val message: String,
    val destroyTime: Float
)
