package dev.wildware.udea.ecs.component.base

import com.github.quillraven.fleks.EntityTag
import dev.wildware.udea.network.Networked
import kotlinx.serialization.Serializable

@Networked
@Serializable
data object Dead : EntityTag()
