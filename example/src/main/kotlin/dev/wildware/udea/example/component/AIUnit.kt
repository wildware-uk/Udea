package dev.wildware.udea.example.component

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.example.ability.AITag

class AIUnit: Component<AIUnit> {
    override fun type() = AIUnit

    companion object : ComponentType<AIUnit>()
}
