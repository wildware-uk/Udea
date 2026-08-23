package dev.wildware.udea.gas

/**
 * Identifies one [AbilityExec] implementation.
 *
 * Dense, assigned from sorted class names by [AbilityExecRegistry], so an instance stores an
 * `Int` and a snapshot carries no class reference. What it replaces is worse than a reference:
 * `AbilitySpec` built its executor with `ability.value.exec.createInstance()`
 * (`common/ability/Ability.kt:76`) — `kotlin.reflect.full.createInstance`, at construction, per
 * spec. Reflection on an activation path, a `kotlin-reflect` dependency in the shipped runtime,
 * and a fresh executor object holding per-activation state that no snapshot could reach.
 */
@JvmInline
public value class AbilityExecId(public val index: Int) {
    override fun toString(): String = "AbilityExecId#$index"

    public companion object {
        /** The id that names no executor. */
        public val NONE: AbilityExecId = AbilityExecId(-1)
    }
}

/**
 * The behaviour of an ability: **stateless**, shared by every entity running it.
 *
 * Every implementation is a singleton in [AbilityExecRegistry], so it must keep no per-activation
 * state whatsoever. State lives on the [AbilityInstance] the context hands it — including the
 * scratch slots, which exist precisely because the old code kept per-activation state in captured
 * closures (`AwaitTargetTask`'s `find`/`onTarget` pair, `common/ability/util.kt`) and a closure
 * cannot be snapshotted.
 */
public interface AbilityExec {

    /** Runs when the ability is activated, after gating has passed and costs have been paid. */
    public fun onActivate(context: AbilityContext)

    /** Runs once per tick while the instance is not [AbilityPhase.Inactive]. */
    public fun onTick(context: AbilityContext) {}

    /** Runs when the instance leaves the active phases, cancelled or otherwise. */
    public fun onEnd(context: AbilityContext, cancelled: Boolean) {}
}

/**
 * Dense [AbilityExecId]s for the game's executors.
 *
 * Ids come from sorted class names, so two builds agree; `udea-codegen` emits the registration
 * list later, and this type is what it will emit *into*. Construction is the only place a class
 * name is read — never on an activation path.
 */
public class AbilityExecRegistry private constructor(
    private val execs: Array<AbilityExec>,
    private val names: Array<String>,
) {

    /** How many executors exist. */
    public val size: Int get() = execs.size

    /** The executor [id] names. */
    public fun execAt(id: AbilityExecId): AbilityExec {
        require(id.index in execs.indices) { "no ability exec with id ${id.index}; $size registered" }
        return execs[id.index]
    }

    /** The id of [exec]'s class. */
    public fun idOf(exec: AbilityExec): AbilityExecId {
        val name = exec::class.java.name
        val index = names.indexOf(name)
        require(index >= 0) { "$name is not registered in this registry" }
        return AbilityExecId(index)
    }

    /** The id of the executor whose class is named [className]. */
    public fun idOf(className: String): AbilityExecId {
        val index = names.indexOf(className)
        require(index >= 0) { "$className is not registered in this registry" }
        return AbilityExecId(index)
    }

    override fun toString(): String = "AbilityExecRegistry($size execs)"

    public companion object {
        /** Registers [execs], assigning ids by ascending class name. */
        public fun of(execs: List<AbilityExec>): AbilityExecRegistry {
            val sorted = execs.sortedBy { it::class.java.name }
            val names = Array(sorted.size) { sorted[it]::class.java.name }
            require(names.toSet().size == names.size) {
                "two ability execs share a class name: ${names.toList()}"
            }
            return AbilityExecRegistry(sorted.toTypedArray(), names)
        }
    }
}

/**
 * One resource an ability spends.
 *
 * Carries **both** the amount to check and the effect that applies it, in one declaration, so the
 * two cannot drift. The old code had only the second half: costs were applied at commit
 * (`Ability.kt:137-142`) and `checkCosts()` at `:145` was an empty function body, so an ability
 * fired with zero mana and drove the resource negative — which the `Float.MIN_VALUE` clamp defect
 * then hid, because the attribute could not go below ~0 anyway.
 */
public class AbilityCost(
    /** Which resource is spent — the one an [ActivationResult.InsufficientResource] names. */
    public val attribute: AttributeId,
    /** How much is required. Positive. */
    public val amount: ValueResolver,
    /** The [GameplayEffectTable] index of the effect that actually spends it. */
    public val effectIndex: Int,
    /**
     * The set-by-caller tag [effectIndex]'s magnitude reads, or [GameplayTag.NONE] for an effect
     * whose magnitude is a constant.
     *
     * Named here rather than assumed, because the amount lives on **this** declaration and the
     * effect that spends it is shared: one `ability/cost_mana` serves every ability, and each
     * one spends a different number. `AbilityActivation.activate` stages `-amount` under this tag
     * when it applies the cost.
     */
    public val magnitudeTag: GameplayTag = GameplayTag.NONE,
) {
    override fun toString(): String = "AbilityCost($attribute)"
}

/**
 * The immutable definition of an ability: what an asset declares.
 *
 * Cooldowns are **ticks**. `orc_elite_abilities.udea.kts:16` wrote
 * `setByCallerTags = mapOf(Data.Cooldown to 15.0F)` in seconds; that conversion happens once, at
 * asset-compile time, through [ticksFromSeconds].
 */
public class AbilityDef(
    /** Asset name, for diagnostics and agent output. */
    public val name: String,
    /** Which executor runs it. */
    public val execId: AbilityExecId,
    /** Base cooldown in ticks, before reduction. `0` for none. */
    public val cooldownTicks: Int = 0,
    /**
     * The [GameplayEffectTable] index of the effect that holds the cooldown, or `-1`.
     *
     * Its duration must be `SetByCaller`, keyed by [cooldownTag]: the *effective* cooldown is
     * computed at activation from [cooldownTicks] and the reduction attribute, and staged onto
     * the application. A fixed `Ticks` duration would make cooldown reduction unexpressible
     * without an engine change, which is exactly what Phase 5's items must not need.
     */
    public val cooldownEffectIndex: Int = -1,
    /** The tag the cooldown effect's `SetByCaller` duration reads. */
    public val cooldownTag: GameplayTag = GameplayTag.NONE,
    /** The attribute carrying cooldown reduction as a percentage, or [AttributeId.NONE]. */
    public val cooldownReductionAttribute: AttributeId = AttributeId.NONE,
    /** What activating it spends. */
    public val costs: List<AbilityCost> = emptyList(),
    /** Tags this ability carries. `findAbilityByTag` matches on these. */
    public val tags: TagSet,
    /** Activation is refused, and an in-flight instance cancelled, while the entity has any of these. */
    public val blockedBy: TagSet,
) {
    init {
        require(name.isNotEmpty()) { "an ability name must not be empty" }
        require(cooldownTicks >= 0) { "cooldownTicks must not be negative, was $cooldownTicks" }
        require(cooldownEffectIndex < 0 || cooldownTag != GameplayTag.NONE) {
            "'$name' declares a cooldown effect but no cooldownTag; the effect's SetByCaller " +
                "duration would resolve to zero and the ability would never be on cooldown"
        }
    }

    override fun toString(): String = "AbilityDef($name)"
}

/** Every ability definition in one game, addressed by dense index assigned from sorted names. */
public class AbilityTable private constructor(
    private val defs: Array<AbilityDef>,
    private val indexByName: Map<String, Int>,
) {

    /** How many abilities exist. */
    public val size: Int get() = defs.size

    /** The definition at [index]. */
    public fun defAt(index: Int): AbilityDef {
        require(index in defs.indices) { "no ability at index $index; the table holds $size" }
        return defs[index]
    }

    /** The index of the ability named [name]. */
    public fun indexOf(name: String): Int =
        indexByName[name] ?: error("no ability named '$name'; the table holds ${defs.map { it.name }}")

    override fun toString(): String = "AbilityTable($size abilities)"

    public companion object {
        /** Builds a table, assigning indices by ascending name. */
        public fun of(defs: List<AbilityDef>): AbilityTable {
            val sorted = defs.sortedBy { it.name }
            val byName = HashMap<String, Int>(sorted.size * 2)
            sorted.forEachIndexed { index, def ->
                require(byName.put(def.name, index) == null) { "two abilities are named '${def.name}'" }
            }
            return AbilityTable(sorted.toTypedArray(), byName)
        }
    }
}

/**
 * Why an activation did or did not happen.
 *
 * `canCast()` returned a bare `Boolean` (`common/ability/Ability.kt:150`), so neither the HUD nor
 * the agent's `activate_ability` tool could say *why* — a player saw a dead button and an agent
 * saw `false`. Both [AbilitySystem.canActivate] and [AbilitySystem.activate] return this, and the
 * check runs in full before any state is mutated, so a rejected activation is a no-op.
 *
 * [Activated] is an object, so the success path — the one that runs every time a player presses a
 * key — allocates nothing. The refusals carry their reason, which costs one small object on a path
 * that by definition did not change the world.
 */
public sealed interface ActivationResult {

    /** True only for [Activated]. */
    public val isActivated: Boolean get() = this is Activated

    /** The ability activated. */
    public data object Activated : ActivationResult

    /** Still cooling down, with [remainingTicks] to go. */
    public data class OnCooldown(public val remainingTicks: Int) : ActivationResult

    /** A tag on the entity forbids it. */
    public data class BlockedByTag(public val tag: GameplayTag) : ActivationResult

    /** Not enough of a resource; carries what was needed and what was there. */
    public data class InsufficientResource(
        public val attribute: AttributeId,
        public val required: Float,
        public val available: Float,
    ) : ActivationResult

    /** The entity does not have this ability in that slot. */
    public data object NotGranted : ActivationResult

    /** This simulation may not activate abilities on that entity. */
    public data object NoAuthority : ActivationResult

    /** The instance is already running. */
    public data object AlreadyActive : ActivationResult
}
