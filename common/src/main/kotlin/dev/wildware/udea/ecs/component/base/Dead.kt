package dev.wildware.udea.ecs.component.base

import com.github.quillraven.fleks.EntityTag
import dev.wildware.udea.network.UdeaNetworked
import kotlinx.serialization.Serializable

/**
 * Add this tag to an entity to mark it as dead.
 * It will be cleaned up next frame.
 * */
@UdeaNetworked
@Serializable
data object Dead : EntityTag()
