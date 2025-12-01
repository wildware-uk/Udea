package dev.wildware.udea.ecs.component.ability

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.ability.AttributeSet
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.ecs.component.configureNetwork
import dev.wildware.udea.network.UdeaNetworked
import dev.wildware.udea.network.serde.UdeaSync

@UdeaNetworked
class Attributes(
    @UdeaSync
    val attributeSet: AttributeSet
) : Component<Attributes> {

    fun <T : AttributeSet> getAttributes(): T {
        return (attributeSet as? T) ?: error("AttributeSet was not of expected type")
    }

    override fun type() = Attributes

    companion object : UdeaComponentType<Attributes>(
        networkComponent = configureNetwork(
            syncStrategy = Update
        )
    )
}
