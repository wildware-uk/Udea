package dev.wildware.udea.core.blueprint

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.EntityCreateContext
import com.github.quillraven.fleks.World

/**
 * Names a blueprint.
 *
 * A value class rather than a `String` so that a blueprint name cannot be passed where a scene
 * name, an asset path or any other string is meant — the same reason
 * [dev.wildware.udea.core.SceneId] exists, and the same caveat: it is not typo detection.
 * `BlueprintId("fierball")` still compiles, and the did-you-mean diagnostic the spec mandates
 * comes from build-time asset validation.
 *
 * It is a `String` today and will narrow to `AssetId` when the asset pipeline lands (spec 3.6).
 * That narrowing is one declaration to change here instead of a `String` to chase through every
 * module, which is the whole reason it is a type at all.
 */
@JvmInline
public value class BlueprintId(public val value: String) {

    init {
        require(value.isNotEmpty()) { "a BlueprintId must name something; it was empty" }
    }

    override fun toString(): String = value
}

/**
 * A recipe for one entity: the components and tags it starts with.
 *
 * ## What `udea-core` is assuming, and what it is not
 *
 * `udea-assets` is empty, so this is the *minimum* shape the kernel needs to turn a blueprint
 * into an entity, deliberately stated as an interface rather than a data format. The asset
 * pipeline (spec 3.6, Phase 2) owns parsing `.udeapak`, flattening parent chains and validating
 * references; what it produces has to be able to *implement* this, and nothing here constrains
 * how. In particular this type says nothing about parents, overrides-by-path, hot-reload or
 * where the recipe was read from — a flattened `BlueprintAsset` that implements [configure] by
 * replaying a compiled component list satisfies it exactly.
 *
 * The one assumption that would be expensive to get wrong: **[configure] is called once, inside
 * the entity's creation, with no partially built entity visible to anything else.** A pipeline
 * that needed to observe the entity between components, or to create more than one entity per
 * blueprint, would not fit and should say so in Phase 2 rather than working around it.
 *
 * ## Ordering
 *
 * [configure] runs before per-spawn overrides and before [SpawnPlacement], so a blueprint's own
 * spatial component wins over the default and an explicit spawn position wins over both. That
 * is the order `GameScreen`'s spawn loop used (`common/UdeaGameManager.kt:191-217`), and
 * blueprint authors in the example game's asset tree already depend on it.
 */
public interface Blueprint {

    /** Stable identity. What an agent names in `spawn_blueprint`, and what a log line says. */
    public val id: BlueprintId

    /**
     * Adds this blueprint's components and tags to [entity], which has just been created.
     *
     * `context` and `entity` as ordinary parameters rather than a receiver: context parameters
     * are still experimental in Kotlin 2.2 and this is a cross-module contract other modules
     * compile against. Implementations write `with(context) { entity += Health() }`.
     */
    public fun configure(context: EntityCreateContext, entity: Entity)
}

/**
 * Per-spawn components and tags, applied on top of a [Blueprint]'s own.
 *
 * The replacement for the old loop's `entityDef.components()` and `entityDef.tags`
 * (`common/UdeaGameManager.kt:196-201`), where a level entry could add to — and shadow — what
 * its blueprint supplied. Separate from [Blueprint] because a blueprint is shared by every
 * entity made from it and an override belongs to exactly one spawn.
 */
public fun interface SpawnOverrides {

    /** Runs immediately after [Blueprint.configure], on the same entity, in the same creation. */
    public fun applyTo(context: EntityCreateContext, entity: Entity)
}

/**
 * Where a spawn puts its entity, in world units.
 *
 * Two floats and not a `Vector2`: `udea-core` has no gdx-math, and a kernel type naming one
 * would put LibGDX on the compile classpath of every module that reads a spawn (spec 3.5). The
 * caller converts once, at the boundary, which is where the conversion belongs.
 *
 * `null` at a call site is not "the origin". It means the blueprint's own placement stands,
 * which is why this is a nullable value rather than a defaulted `(0, 0)`.
 */
public data class SpawnPosition(public val x: Float, public val y: Float)

/**
 * What "put this entity somewhere" means to a particular game.
 *
 * The kernel has no `Transform`. It has no spatial component at all, on purpose: `udea-core`
 * has no gdx-math, and a component every game must use is a component the kernel has decided
 * for every game. So the two halves of the old spawn loop that touched `Transform` —
 * `if (!has(Transform)) it += Transform()` and `it[Transform].position.set(...)`
 * (`common/UdeaGameManager.kt:203-215`) — are declared here and implemented by the game.
 *
 * A [BlueprintSpawner] built without one is complete and working: it spawns entities from
 * blueprints and never touches their placement. It refuses a spawn that carries a
 * [SpawnPosition] instead of ignoring it, at submit time — see [BlueprintSpawner.spawnAll].
 */
public interface SpawnPlacement {

    /**
     * Adds the game's spatial component to [entity] if the blueprint did not supply one.
     *
     * Called on every spawn, position or no position, which is what the old loop did and what
     * makes "every entity has a Transform" an invariant a movement system may rely on.
     */
    public fun defaultIfAbsent(world: World, entity: Entity)

    /** Writes [x] and [y] into [entity]'s spatial component. Called only for a spawn that named one. */
    public fun moveTo(world: World, entity: Entity, x: Float, y: Float)
}

/**
 * One entity to create: a blueprint, optionally somewhere, optionally with extras.
 *
 * A value so that [BlueprintSpawner.spawnAll] can take twenty of them and the barrier can carry
 * them as one action. `spawn(blueprint, position, overrides)` builds one for the caller.
 */
public class SpawnRequest(
    public val blueprint: Blueprint,
    /** Where to put it, or `null` to leave the blueprint's own placement alone. */
    public val position: SpawnPosition? = null,
    /** Components and tags for this spawn only, applied after the blueprint's. */
    public val overrides: SpawnOverrides? = null,
) {
    override fun toString(): String = "SpawnRequest(${blueprint.id}, position=$position)"
}
