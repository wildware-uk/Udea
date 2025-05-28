package dev.wildware.udea.ecs.component.base

import com.github.quillraven.fleks.Component
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.assets.Blueprint as BlueprintAsset

class Blueprint(
    val blueprint: BlueprintAsset
) : Component<Blueprint> {
    override fun type() = Blueprint

    companion object : UdeaComponentType<Blueprint>()
}
