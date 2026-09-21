package dev.wildware.udea.core.spatial

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.annotations.Net
import dev.wildware.udea.annotations.Replicated
import dev.wildware.udea.assets.AssetIndex
import dev.wildware.udea.assets.AssetRegistry
import dev.wildware.udea.assets.Model
import dev.wildware.udea.assets.Ref
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.ReplicatedComponentType
import dev.wildware.udea.core.snapshot.fleksComponentType
import kotlinx.serialization.Serializable

/**
 * Which model this entity draws, said by the **simulation** (issue #270).
 *
 * ```
 * // a blueprint spawns a chassis and a turret, and says what each one looks like
 * world.entity {
 *     it += Transform3D(x = 4f, y = 0f)
 *     it += Drawn(GameAssets.models.chassis, assets.registry)
 * }
 * ```
 *
 * That is all a game writes. `ModelRenderSystem` loads the file the first time it draws the
 * entity and keeps the entity's `ModelRenderer` in step with this component from then on, on the
 * Kool render thread, so a game does not write - and does not get subtly wrong - the loop that
 * turns "this is a chassis" into a picture.
 *
 * ## Why it is not `ModelRenderer`
 *
 * `ModelRenderer` holds a *loaded* model: meshes, materials and textures with GL handles behind
 * them. It lives in `udea-render`, it is never replicated and never snapshotted, and that is
 * right - a dedicated server has no GL context and no business holding one.
 *
 * But a unit's parts are created by the simulation. A blueprint spawns a chassis and five
 * modules on its sockets, and a client has to draw the same five modules; a snapshot has to
 * carry them, or a rewind resurrects an assembly as invisible shells. So something shared has
 * to say what each part looks like, and what it holds has to be the kind of thing that goes on
 * a wire.
 *
 * This is that thing, and the shape is the one [Animator] already has: the simulation holds the
 * reference, the renderer applies it. [Animator] holds a clip's index in its file; this holds an
 * asset's slot in the packed graph.
 *
 * ## What is on the wire is a slot, not a name
 *
 * [model] is an [AssetIndex]'s value - the asset's position in the packed graph - which spec
 * 3.6 makes the only asset identity allowed into a snapshot: it is one int, it is stable across
 * a hot reload, and it needs no string table on the wire. It is an `Int` field for the reason
 * `Inventory`'s six slots are and `AttachedTo.node` is: `FieldLowering` stores an `Int`, and an
 * [AssetIndex] is a value class whose only property is a `val`, which it cannot restore in
 * place. The typed constructor below and [at] are how a game names one, so a game never writes
 * the int itself.
 *
 * [NONE] means "nothing to draw yet" - a part spawned before its blueprint has decided what it
 * is. The renderer draws nothing for it and takes any model it had off it.
 *
 * `@Serializable`, so a level file saves a placed prop the way it saves anything else, and
 * `@Replicated` with [model] `@Net`, so a client draws what the server spawned. Nothing here
 * changes tick to tick for most entities, so a delta carries it once, in the create.
 */
@Serializable
@Replicated
public class Drawn(
    /**
     * The [AssetIndex] value of the [Model] this entity draws, or [NONE].
     *
     * A `var`: a unit that is upgraded, damaged into a wreck or disguised changes what it draws
     * by assigning here, and the renderer follows on the next frame.
     */
    @Net public var model: Int = NONE,
) : Component<Drawn> {

    /**
     * The model [assets] resolves [model] to: `Drawn(GameAssets.models.chassis, registry)`.
     *
     * The registry is a parameter rather than something this reaches for, because there is no
     * global to reach - and it is what turns a name into the slot that goes on the wire. It
     * also type-checks the reference: a `Ref` pointing at something that is not a [Model] fails
     * here, naming the id and both kinds, rather than at the first frame that tried to draw it.
     */
    public constructor(model: Ref<Model>, assets: AssetRegistry) : this(slotOf(model, assets))

    /** True when this names no model, so there is nothing to draw. */
    public fun isEmpty(): Boolean = model == NONE

    /** Points this at [asset]'s slot, or at nothing when [asset] is null. */
    public fun show(asset: AssetIndex?) {
        model = asset?.value ?: NONE
    }

    /** Points this at the model [assets] resolves [asset] to. */
    public fun show(asset: Ref<Model>, assets: AssetRegistry) {
        model = slotOf(asset, assets)
    }

    override fun type(): ComponentType<Drawn> = Drawn

    override fun toString(): String = if (isEmpty()) "Drawn(nothing)" else "Drawn(slot $model)"

    public companion object : ComponentType<Drawn>() {

        /** [model] when this entity has no model yet: nothing is drawn for it. */
        public const val NONE: Int = -1

        /**
         * The entity draws whatever is at [slot]: `Drawn.at(catalog.index)`, for a game that
         * already holds slots rather than references.
         *
         * A factory and not a second constructor, because Kotlin does not mangle a
         * constructor's JVM signature the way it mangles a function's - `Drawn(Int)` and
         * `Drawn(AssetIndex)` would be the same `(I)V` and neither would compile.
         */
        public fun at(slot: AssetIndex): Drawn = Drawn(slot.value)

        /**
         * The snapshot registration for a game's `ComponentRegistry`, which is what makes a
         * snapshot, a rewind, a replay hash and replication see a [Drawn] at all: capture walks
         * the registry, and a component left out of it is invisible rather than partly captured.
         * Built fresh per call, like [Transform3D.snapshotType].
         */
        public fun snapshotType(): ReplicatedComponentType<Drawn> = fleksComponentType(
            DrawnReplicator,
            ComponentSchema.of(DrawnReplicator, "Drawn", listOf(FieldKind.Int)),
            Drawn,
        ) { Drawn() }

        /**
         * [model]'s slot, having first checked that it really names a [Model].
         *
         * `assets[model]` is the check and the bind in one: it refuses a reference of the wrong
         * kind and interns the slot on the reference, so a `Ref` used for many entities is
         * resolved once.
         */
        private fun slotOf(model: Ref<Model>, assets: AssetRegistry): Int {
            assets[model]
            return assets.indexOf(model.id).value
        }
    }
}
