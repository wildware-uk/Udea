package dev.wildware.udea.core.level

import com.github.quillraven.fleks.World
import kotlin.reflect.KClass
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModuleBuilder

/**
 * What a module contributes to saving and loading levels, beyond its components' own fields.
 *
 * ## References: live objects a component points at
 *
 * Some components hold a reference to an object the game built at startup - `udea-gas`'s
 * `Attributes` holds the `AttributeTable` its values are indexed by. That object is not level
 * content and must not be written as if it were, and a load has to give the component the running
 * game's instance rather than a copy. [reference] registers a serializer that writes enough to
 * *check* the object (the table's attribute names) and reads back the live instance, refusing a
 * level saved against a different one.
 *
 * ## Sections: state that belongs to the world but to no entity
 *
 * Some simulation state lives in a module's services rather than on a component - `udea-gas`'s
 * effect-handle counter, which must continue from exactly where the saved world left it, or the
 * next effect applied after a load gets a different handle from the one the saved match would
 * have given it. [section] saves that state under a name and hands it back after a load, inside
 * the same barrier action, so no system sees the world between the two.
 *
 * Filled by [dev.wildware.udea.core.module.UdeaModule.level], once per game build.
 */
public class LevelHooks internal constructor() {

    private val references = ArrayList<Reference<*>>()

    private val registeredSections = ArrayList<LevelSection<*>>()

    /**
     * Registers [serializer] for every saved field of [type] marked `@Contextual`.
     *
     * @throws IllegalArgumentException if [type] already has one: two modules disagreeing about
     *   how a live object is referenced is a defect, not a precedence question.
     */
    public fun <T : Any> reference(type: KClass<T>, serializer: KSerializer<T>) {
        require(references.none { it.type == type }) {
            "a level reference for ${type.qualifiedName} is already registered"
        }
        references += Reference(type, serializer)
    }

    /**
     * Saves and restores [section] with every level.
     *
     * @throws IllegalArgumentException if another section already has its name.
     */
    public fun section(section: LevelSection<*>) {
        require(registeredSections.none { it.name == section.name }) {
            "a level section named '${section.name}' is already registered"
        }
        registeredSections += section
    }

    internal val sections: List<LevelSection<*>> get() = registeredSections

    internal fun registerReferences(builder: SerializersModuleBuilder) {
        for (reference in references) reference.registerInto(builder)
    }

    private class Reference<T : Any>(val type: KClass<T>, val serializer: KSerializer<T>) {
        fun registerInto(builder: SerializersModuleBuilder) {
            builder.contextual(type, serializer)
        }
    }
}

/**
 * World-level state a module keeps outside any component, saved with a level. See [LevelHooks].
 */
public interface LevelSection<T> {

    /** The key the state is saved under. Unique within a game, and stable across builds. */
    public val name: String

    /** How the state is written. */
    public val serializer: KSerializer<T>

    /** The state as it stands, read between ticks. */
    public fun save(world: World): T

    /**
     * Puts [saved] back. Called inside the load's barrier action after the world, its `NetId`s,
     * the clock and the random streams have been restored.
     */
    public fun load(world: World, saved: T)
}
