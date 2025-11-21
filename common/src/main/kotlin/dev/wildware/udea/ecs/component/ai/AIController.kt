package dev.wildware.udea.ecs.component.ai

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.assets.Ability
import dev.wildware.udea.assets.AssetReference
import dev.wildware.udea.dsl.CreateDsl

@CreateDsl(name = "aiController")
class AIController(
    val abilities: List<AbilityHint>
) : Component<AIController> {
    override fun type() = AIController

    companion object : ComponentType<AIController>()
}



@CreateDsl(onlyList = true)
data class AbilityHint(
    val ability: AssetReference<Ability>,
    val target: List<AbilityTarget>,
    val abilityRange: Float
) {
    enum class AbilityTarget {
        Self, Enemy, Ally, Random
    }
}
