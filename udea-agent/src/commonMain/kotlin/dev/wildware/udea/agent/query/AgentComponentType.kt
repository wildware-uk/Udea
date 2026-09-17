package dev.wildware.udea.agent.query

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.replication.Replicator

/**
 * One component type, as the agent's query and inspection surface needs to see it.
 *
 * ## No reflection, and that is the whole point
 *
 * Spec 3.1: `describe_entity` and `set_component_field` are *free consequences of
 * `Replicator.getField/setField`* - the MCP surface needs no reflection and survives R8. Every
 * field read below goes through the generated [Replicator]; nothing here calls
 * `kotlin.reflect` or `java.lang.reflect`, and `NoReflectionInQueryPathTest` asserts the
 * absence at source level so the next convenient shortcut is a red build.
 *
 * The old engine's answer was the opposite on both counts: `common/.../reflection.kt` held a
 * classpath-scanning `Reflections` singleton, and the inspector took a concrete screen type
 * and needed a bespoke `var debugXxx` on a game class for every field an agent could see.
 * Neither generalises to a component an agent has never heard of.
 *
 * ## Why the generic is closed inside the implementation
 *
 * A `Replicator<T>` needs a `T` to read from, and an index of heterogeneous component types
 * can only hold `AgentComponentType`s if the `T` is closed somewhere. It is closed by
 * [agentComponent], at the one site where the component type is concrete - the same shape, and
 * for the same reason, as `fleksComponentType` in `udea-core`'s snapshot spine. The alternative
 * is an unchecked cast at every call site of the index.
 */
public interface AgentComponentType {

    /** The name an agent addresses this component by. Conventionally the simple class name. */
    public val name: String

    /** The generated codec. Its `fieldNames` are the field vocabulary an agent sees. */
    public val replicator: Replicator<*>

    /**
     * Whether `set_component_field` may write field [fieldIndex].
     *
     * `false` by default, because spec 5 makes `agentWritable = false` the default on `@Net`: an
     * agent write is opt-in per field so that a debug tool can never quietly become a gameplay
     * backdoor. Generated code answers this from the annotation; a hand-registered component that
     * says nothing is therefore read-only, which is the safe direction to be wrong in.
     *
     * A predicate rather than a `FieldMask` property deliberately. `Replicator<T>` couples four
     * modules and spec 7 mitigates the coming widening past 64 fields by keeping mask *storage*
     * inside `udea-core` - `ReplicatorApiShapeTest` fails the build for a declared `FieldMask`
     * property anywhere else, and it is right to: a mask held here is a mask that has to change
     * type when the storage does. Field indices are the vocabulary the rest of this surface
     * already speaks, and they survive the widening untouched.
     */
    public fun isAgentWritable(fieldIndex: Int): Boolean

    /** Whether [entity] currently carries this component. */
    public fun isPresent(world: World, entity: Entity): Boolean

    /**
     * The value of field [fieldIndex] on [entity], or `null` if the entity does not carry the
     * component.
     *
     * Boxed, which the frozen contract permits here and nowhere else: this runs once per
     * entity per queried field on an agent tool call, not once per entity per tick.
     *
     * @throws dev.wildware.udea.core.replication.NoSuchFieldIndexException if the index is not
     *   a field of this component.
     */
    public fun read(world: World, entity: Entity, fieldIndex: Int): Any?

    /**
     * Writes [value] into field [fieldIndex] of [entity]'s component.
     *
     * The seam `set_component_field` needs. Nothing in the query engine calls it - a query is a
     * read - but it belongs here rather than in the tool that will use it, because permission
     * ([isAgentWritable]) and access must be declared together or a caller can have one
     * without the other.
     *
     * @throws IllegalArgumentException if the entity does not carry the component, or if
     *   [value] is not assignable to the field.
     */
    public fun write(world: World, entity: Entity, fieldIndex: Int, value: Any?)

    /** The field names, index-aligned with mask bits. Shorthand for `replicator.fieldNames`. */
    public val fieldNames: List<String> get() = replicator.fieldNames

    /**
     * The index of [fieldName], or -1.
     *
     * A linear scan over a handful of names, on a path that runs once per query rather than
     * once per entity: the query resolves every field it needs *before* it walks the world.
     */
    public fun fieldIndexOf(fieldName: String): Int = fieldNames.indexOf(fieldName)
}

/**
 * Binds [replicator] to a Fleks component type.
 *
 * `inline` with a `reified` parameter because Fleks' accessors are - `entity.getOrNull(type)`
 * cannot be called from a generic class whose own `T` is erased. Reifying here binds them where
 * the component type is concrete, which is also exactly where generated registration will bind
 * them.
 *
 * @param agentWritableFields indices of the fields `set_component_field` may write. Empty means
 *   read-only, which is spec 5's default.
 * @param name what an agent calls this component. Given rather than derived: `Replicator<T>` is
 *   frozen and carries field names but no type name, and `T::class.simpleName` would be the
 *   obfuscated name after R8 - which is precisely the surface this design promises survives it.
 */
public inline fun <reified T> agentComponent(
    name: String,
    replicator: Replicator<T>,
    componentType: ComponentType<T>,
    agentWritableFields: Set<Int> = emptySet(),
): AgentComponentType where T : Component<T> {
    require(name.isNotBlank()) { "a component needs a name an agent can address it by" }
    for (fieldIndex in agentWritableFields) {
        require(fieldIndex in replicator.fieldNames.indices) {
            "$name has no field at index $fieldIndex to make agent-writable; it has " +
                "${replicator.fieldNames.size}"
        }
    }

    val codec = replicator
    val writable = agentWritableFields.toSet()
    val componentName = name

    return object : AgentComponentType {

        override val name: String get() = componentName

        override val replicator: Replicator<*> get() = codec

        override fun isAgentWritable(fieldIndex: Int): Boolean = fieldIndex in writable

        override fun isPresent(world: World, entity: Entity): Boolean =
            with(world) { entity.getOrNull(componentType) != null }

        override fun read(world: World, entity: Entity, fieldIndex: Int): Any? {
            val component = with(world) { entity.getOrNull(componentType) } ?: return null
            return codec.getField(component, fieldIndex)
        }

        override fun write(world: World, entity: Entity, fieldIndex: Int, value: Any?) {
            val component = with(world) { entity.getOrNull(componentType) }
            requireNotNull(component) { "$componentName is not on $entity" }
            codec.setField(component, fieldIndex, value)
        }

        override fun toString(): String = "AgentComponentType($componentName)"
    }
}
