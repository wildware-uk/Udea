package dev.wildware.udea

import com.github.quillraven.fleks.*

/**
 * Returns a [component][Component] of the given [type] for the [entity][Entity].
 * @param world The world context.
 * @throws [FleksNoSuchEntityComponentException] if the [entity][Entity] does not have such a component.
 */
context(world: World)
operator fun <T : Component<out Any>> Entity.get(componentType: ComponentType<T>) =
    with(world) { this@get[componentType] }

/**
 * Returns a [component][Component] of the given [type] for the [entity][Entity]
 * or null if the [entity][Entity] does not have such a [component][Component].
 */
context(world: World)
fun <T : Component<out Any>> Entity.getOrNull(componentType: ComponentType<T>) =
    with(world) { this@getOrNull.getOrNull(componentType) }

/**
 * Returns true if and only if the [entity][Entity] has a [component][Component] or [tag][EntityTag] of the given [type].
 */
context(world: World)
fun <T : Component<out Any>> Entity.contains(componentType: ComponentType<T>) =
    with(world) { componentType in this@contains }
