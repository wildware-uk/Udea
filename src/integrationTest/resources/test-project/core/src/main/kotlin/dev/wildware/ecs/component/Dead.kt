package dev.wildware.ecs.component

import com.github.quillraven.fleks.EntityTag
import dev.wildware.network.Networked
import kotlinx.serialization.Serializable

@Networked
@Serializable
data object Dead : EntityTag()
