package dev.wildware.udea.core.snapshot

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.replication.ComponentTypeId
import dev.wildware.udea.core.replication.FieldStore
import dev.wildware.udea.core.replication.Replicator
import kotlin.reflect.KClass

/**
 * One replicated component type, as the snapshot spine needs to see it.
 *
 * The [Replicator] knows how to move a component's fields into and out of a [FieldStore] and
 * nothing else — deliberately, because it is frozen and must not grow an ECS dependency. What
 * capture and restore additionally need is the other half: how to *reach* the component on an
 * entity, and how to add or remove it. That is this interface, and it is the seam.
 *
 * It is also the seam for the sibling Box2D issue: a physics-backed component whose live state
 * lives in a solver rather than in a field can implement this and rebuild itself on
 * [applyOnto], without the snapshot spine knowing physics exists.
 *
 * Every method is type-erased at the call site on purpose. The registry holds a
 * heterogeneous list of these, and closing the generic inside each implementation is what
 * keeps `SnapshotService` free of unchecked casts.
 */
public interface ReplicatedComponentType<T : Any> {

    /** The generated codec for this component. */
    public val replicator: Replicator<T>

    /** The column layout for this component's lowered fields. */
    public val schema: ComponentSchema

    /**
     * The Kotlin class of the component this type captures.
     *
     * Carried so that [SnapshotCoverage] can ask a live world the one question no other part of
     * the spine can answer: *is everything on this entity actually in the registry?* Capture
     * walks the registry and asks each type whether the entity has it, so a component nobody
     * registered is not merely uncaptured - it is invisible, and a rewind silently rebuilds an
     * entity without it. Going the other way, from the component instances Fleks holds back to
     * the registry, needs an identity for the type, and this is it.
     *
     * Never read on a per-tick path. Registration binds it once, and the audit is an explicit
     * call.
     */
    public val componentClass: KClass<T>

    /** True when [entity] currently carries this component. */
    public fun isPresent(world: World, entity: Entity): Boolean

    /**
     * Captures [entity]'s component into `store[slot]`, using `Replicator.capture`.
     *
     * @return false when the entity does not carry the component, in which case nothing was
     *   written and the caller must not mark it present.
     */
    public fun captureInto(world: World, entity: Entity, store: FieldStore, slot: Int): Boolean

    /**
     * Writes `store[slot]` onto [entity]'s component **in place**, adding the component first
     * if the entity does not have it.
     *
     * Applies `replicator.allMask` — `@Net` and `@Sim` together — because a restore is not a
     * delta: a jungle timer and a bot blackboard must come back too (spec 3.1).
     */
    public fun applyOnto(world: World, entity: Entity, store: FieldStore, slot: Int)

    /** Removes the component from [entity]. A no-op when it is not there. */
    public fun removeFrom(world: World, entity: Entity)
}

/**
 * [ReplicatedComponentType] for an ordinary Fleks component.
 *
 * Hand-written per component today; `udea-codegen` emits one beside each `Replicator` later.
 *
 * ## Why this is an inline factory and not a class
 *
 * Fleks' component accessors — `Entity.getOrNull`, `getOrAdd`, `Entity.minusAssign` — are
 * `inline` with a `reified` type parameter, so a plain generic class cannot call them: its own
 * `T` is erased. Reifying here binds them at each registration site, where the component type
 * *is* concrete, which is also exactly where a generated registration will bind them. The
 * alternative is reaching past the reified API into `ComponentsHolder`, which removes the
 * instance without clearing the entity's component mask — the entity would then be missing
 * from families while `getOrNull` still handed back the component. Fleks' own accessors do
 * both halves; this uses them rather than reimplementing one of them.
 *
 * The returned object is built once per component type at registration, never per tick.
 *
 * @param create runs only when a restore re-creates a component the live world had dropped, so
 *   it is off the per-tick path and allocating there is correct rather than merely tolerated:
 *   an entity that does not exist cannot have its fields applied in place.
 */
public inline fun <reified T> fleksComponentType(
    replicator: Replicator<T>,
    schema: ComponentSchema,
    componentType: ComponentType<T>,
    noinline create: () -> T,
): ReplicatedComponentType<T> where T : Component<T> {
    require(schema.typeId == replicator.typeId) {
        "schema ${schema.typeName} carries ${schema.typeId} but its replicator carries " +
            "${replicator.typeId}; one component type has one id"
    }

    val codec = replicator
    val layout = schema

    val declared = T::class

    return object : ReplicatedComponentType<T> {

        override val replicator: Replicator<T> get() = codec

        override val schema: ComponentSchema get() = layout

        override val componentClass: KClass<T> get() = declared

        override fun isPresent(world: World, entity: Entity): Boolean =
            with(world) { entity.getOrNull(componentType) != null }

        override fun captureInto(
            world: World,
            entity: Entity,
            store: FieldStore,
            slot: Int,
        ): Boolean {
            val component = with(world) { entity.getOrNull(componentType) } ?: return false
            codec.capture(component, store, slot)
            return true
        }

        override fun applyOnto(world: World, entity: Entity, store: FieldStore, slot: Int) {
            with(world) {
                val existing = entity.getOrNull(componentType)
                if (existing != null) {
                    // In place, so the mutable vectors that rendering and physics hold
                    // references to keep their identity across a rewind.
                    codec.apply(store, slot, existing, codec.allMask)
                    return
                }
                entity.configure { target ->
                    val added = target.getOrAdd(componentType, create)
                    codec.apply(store, slot, added, codec.allMask)
                }
            }
        }

        override fun removeFrom(world: World, entity: Entity) {
            with(world) {
                if (entity.getOrNull(componentType) == null) return
                entity.configure { target -> target -= componentType }
            }
        }

        override fun toString(): String = "FleksComponentType(${layout.typeName})"
    }
}

/**
 * Every replicated component type in one simulation, in canonical order.
 *
 * Canonical order is **ascending [ComponentTypeId]**, and the registry sorts rather than
 * trusts its input. That is the same reason spec 5 assigns ids from sorted FQNs in one
 * generator: two independently built processes — a server and a client, or two CI machines —
 * must lay out and hash a world identically no matter what order their `ServiceLoader`
 * happened to discover modules in. [WorldHasher] walks this order, so an unstable registry
 * would present as a determinism failure with no cause anywhere near it.
 *
 * The dense [indexOf] mapping exists because a `ComponentTypeId` is sparse — ids come from a
 * whole-project FQN sort — while a presence bitset and a per-type store array want a dense
 * index. Resolving one to the other is an array read, not a map lookup, because restore walks
 * it once per entity per component.
 */
public class ComponentRegistry(types: List<ReplicatedComponentType<*>>) {

    private val ordered: Array<ReplicatedComponentType<*>> =
        types.sortedBy { it.schema.typeId.raw }.toTypedArray()

    /** `ComponentTypeId.raw` to dense index, `-1` where the id is not registered. */
    private val denseIndex: IntArray

    init {
        require(ordered.isNotEmpty()) { "a ComponentRegistry with no component types can capture nothing" }

        var highestId = 0
        for (type in ordered) {
            if (type.schema.typeId.raw > highestId) highestId = type.schema.typeId.raw
        }
        denseIndex = IntArray(highestId + 1) { NOT_REGISTERED }

        for (index in ordered.indices) {
            val raw = ordered[index].schema.typeId.raw
            require(denseIndex[raw] == NOT_REGISTERED) {
                "two component types share ${ComponentTypeId(raw)}: " +
                    "${ordered[denseIndex[raw]].schema.typeName} and ${ordered[index].schema.typeName}"
            }
            denseIndex[raw] = index
        }
    }

    /** Every registered component's class, for [covers]. Built once; never walked per tick. */
    private val coveredClasses: Set<KClass<*>> = ordered.mapTo(LinkedHashSet()) { it.componentClass }

    /** How many component types are registered. Also the width of a presence mask. */
    public val size: Int get() = ordered.size

    /** The type at dense [index], in ascending [ComponentTypeId] order. */
    public fun typeAt(index: Int): ReplicatedComponentType<*> {
        require(index in ordered.indices) { "component index out of range: $index (0 until $size)" }
        return ordered[index]
    }

    /** The schema at dense [index]. */
    public fun schemaAt(index: Int): ComponentSchema = typeAt(index).schema

    /**
     * The dense index of [typeId].
     *
     * @throws IllegalArgumentException when the id is not registered. A snapshot naming a
     *   component this simulation does not have is a protocol mismatch, and silently skipping
     *   it would restore a world that is quietly missing state.
     */
    public fun indexOf(typeId: ComponentTypeId): Int {
        val index = if (typeId.raw < denseIndex.size) denseIndex[typeId.raw] else NOT_REGISTERED
        require(index != NOT_REGISTERED) { "no component type registered for $typeId" }
        return index
    }

    /** Whether [typeId] is registered here at all. */
    public operator fun contains(typeId: ComponentTypeId): Boolean =
        typeId.raw < denseIndex.size && denseIndex[typeId.raw] != NOT_REGISTERED

    /**
     * Whether a component of [componentClass] is captured and restored by this registry.
     *
     * Exact class identity, not `isInstance`: a subclass of a registered component is a
     * different Fleks `ComponentType` with a different `Replicator`, and answering "yes" for one
     * would report coverage the spine does not have.
     */
    public fun covers(componentClass: KClass<*>): Boolean = componentClass in coveredClasses

    /** Every component class this registry captures, for a diagnostic that wants to list them. */
    public val componentClasses: Set<KClass<*>> get() = coveredClasses

    override fun toString(): String =
        "ComponentRegistry(${ordered.joinToString { it.schema.typeName }})"

    private companion object {
        const val NOT_REGISTERED: Int = -1
    }
}
