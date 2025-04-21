package dev.wildware.udea.ecs

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.ecs.ComponentDependency.Companion.Empty

/**
 * Base component type for all UDEA components that supports dependency management.
 *
 * @param T The type of component this represents
 * @property dependsOn The dependencies required by this component
 */
abstract class UdeaComponentType<T : Component<T>>(
    val dependsOn: ComponentDependency = Empty,
    val networkComponent: NetworkComponent<T>? = null,
) : ComponentType<T>()

/**
 * Represents dependencies between components in the ECS system.
 *
 * @property dependencies Array of component types that are required dependencies
 */
class ComponentDependency private constructor(
    vararg val dependencies: ComponentType<out Any> = emptyArray()
) {
    companion object {
        /**
         * Represents an empty dependency with no requirements.
         */
        val Empty = ComponentDependency()

        /**
         * Creates a new ComponentDependency with the specified dependencies.
         *
         * @param dependencies The component types that are required
         * @return A new ComponentDependency instance containing the specified dependencies
         */
        fun dependencies(vararg dependencies: ComponentType<out Any>): ComponentDependency {
            return ComponentDependency(*dependencies)
        }
    }
}

annotation class UdeaProperty
