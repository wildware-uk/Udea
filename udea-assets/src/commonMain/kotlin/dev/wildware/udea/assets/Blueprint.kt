package dev.wildware.udea.assets

/**
 * One component to add to an entity, as data: which component type, and what its fields start as.
 *
 * The old `Blueprint.components` held live Fleks `Component` instances, constructed by evaluating
 * a `.udea.kts` in a `BasicJvmScriptingHost` at runtime. Spec 3.6 kills that host, so what a pack
 * ships has to be decodable without evaluating anything - a type name and a field map, which the
 * generated component registry turns back into a real component at spawn time.
 *
 * [fields] holds only the fields the author set. An absent field means "whatever the component's
 * own default is", which is what makes adding a field to a component a non-breaking change to
 * every pack that already exists.
 */
public data class ComponentSpec(
    /** The component class, e.g. `dev.wildware.moba.Health`. */
    public val type: TypeName,
    /** Field name to authored value; only what the author actually set. */
    public val fields: Map<String, AssetValue> = emptyMap(),
)

/**
 * A recipe for one entity: the components and tags it starts with.
 *
 * ## Flattened
 *
 * The old `Blueprint` had a `parent: AssetReference<Blueprint>?` and walked it on *every* spawn -
 * `allComponents()` recursed the parent chain and concatenated lists per entity created
 * (`common/.../blueprints.kt:20-53`). Parents are an authoring convenience, so they are resolved at
 * build time: [components] and [tags] here are the full, flattened result, and the runtime does
 * zero parent walking. [inheritedFrom] is provenance only - what an agent is told when it asks
 * where a component came from - and nothing reads it to compute behaviour.
 *
 * ## Its relationship to `udea-core`
 *
 * `dev.wildware.udea.core.blueprint.Blueprint` is an *interface* describing behaviour: "configure
 * this freshly created entity". This is the *data*: "here is what to configure it with". They are
 * two types on purpose - `udea-core` has Fleks on its classpath and this module must not - and the
 * Phase 2 adapter that implements the former by replaying this one's [components] is exactly the
 * "flattened `BlueprintAsset`" that core's KDoc anticipates. Core's `BlueprintId` narrows to
 * [AssetId] when that adapter lands; the two are one-to-one by string today.
 */
public data class Blueprint(
    override val id: AssetId,
    /** Every component, parents already flattened in, in application order. */
    override val components: List<ComponentSpec> = emptyList(),
    /** Every Fleks tag, parents already flattened in. */
    override val tags: List<EntityTagName> = emptyList(),
    /**
     * The parent chain this blueprint was flattened from, root first. Empty when it had no parent.
     * Provenance for diagnostics and for `describe_blueprint`; never walked at runtime.
     */
    public val inheritedFrom: List<AssetId> = emptyList(),
) : SpawnRecipe {

    init {
        require(id !in inheritedFrom) { "blueprint '$id' lists itself in its own parent chain" }
    }
}
