package dev.wildware.udea.ecs.component.ai

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.Entity

class AIPerception(
    val perceptionRadius: Float = 10f
) : Component<AIPerception> {

    val perceivedEntities = mutableListOf<Entity>()

    override fun type() = AIPerception

    companion object : ComponentType<AIPerception>()
}
