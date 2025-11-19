package dev.wildware.udea.ecs.component.base

import com.github.quillraven.fleks.EntityTag
import dev.wildware.udea.network.UdeaNetworked
import kotlinx.serialization.Serializable

@UdeaNetworked
@Serializable
data object Dead : EntityTag()
