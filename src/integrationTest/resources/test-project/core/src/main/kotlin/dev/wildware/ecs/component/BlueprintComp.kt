package dev.wildware.ecs.component

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.assets.Asset
import dev.wildware.ecs.Blueprint
import dev.wildware.udea.network.Networked
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
class BlueprintComp(
    var blueprint: Asset<@Contextual Blueprint>
) : Component<BlueprintComp> {
    override fun type() = BlueprintComp

    companion object : ComponentType<BlueprintComp>()
}
