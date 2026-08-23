package dev.wildware.udea.core.module

import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SimSystem
import kotlin.reflect.KClass

/**
 * Where a [UdeaModule] declares its simulation systems.
 *
 * ## What it replaces
 *
 * `UdeaGameManager` registered a `KClass<out IntervalSystem>` and called
 * `kClass.createInstance()` (`common/UdeaGameManager.kt:281`). Three things followed, and all
 * three die here:
 *
 * - a system needed a **no-arg constructor**, so every system reached for `inject()` or a
 *   global instead of taking the thing it actually depends on;
 * - a missing dependency was a *runtime* failure, at world construction, in a stack trace
 *   through `kotlin-reflect`;
 * - `kotlin-reflect` was on the runtime classpath at all.
 *
 * Registration here is a **function that builds the system**, so its parameters are ordinary
 * constructor parameters the compiler checks:
 *
 * ```kotlin
 * override fun simulation(r: SimRegistry) {
 *     // constructor reference: the system takes the context and nothing else
 *     r.add(SimPhase.PreSimulation, ::TeleportSystem)
 *
 *     // a lambda when the system takes real collaborators — still compiler-checked,
 *     // still no reflection, and the dependency is visible in the system's own signature
 *     r.add(SimPhase.Physics, { ctx -> PhysicsStepSystem(ctx.physics) }) {
 *         after<TeleportSystem>()
 *     }
 * }
 * ```
 *
 * The factory is handed the [GameContext] because a composition root is exactly where
 * reaching into a context is correct: the *system* then names `PhysicsWorld` in its
 * constructor rather than taking a context and reaching through it, which is the service
 * location the standards forbid.
 *
 * ## Ordering
 *
 * [SimPhase] ordinal first and absolutely. Within a phase, a deterministic topological sort of
 * the `before`/`after` constraints, tie-broken by registration index. Registering the same set
 * of systems in any declaration order therefore yields the same manifest.
 */
public class SimRegistry internal constructor() {

    private val registrations = ArrayList<SystemRegistration>()

    /**
     * Registers a system built by [factory], running in [phase].
     *
     * @param type the system's class. Its identity, so `before`/`after` can name it and so a
     *   double registration is caught rather than silently running the system twice.
     * @throws IllegalArgumentException if [type] is already registered. Two instances of one
     *   system in a tick is never what a module meant, and the ordering constraints would
     *   have no way to say which one they referred to.
     */
    public fun <T : SimSystem> add(
        phase: SimPhase,
        type: KClass<T>,
        factory: (GameContext) -> T,
        configure: SystemConstraints.() -> Unit = {},
    ) {
        require(registrations.none { it.type == type }) {
            "${type.java.name} is already registered in phase " +
                "${registrations.first { it.type == type }.phase}; a system runs once per tick"
        }
        val constraints = SystemConstraints().apply(configure)
        registrations += SystemRegistration(
            index = registrations.size,
            phase = phase,
            type = type,
            factory = factory,
            runsBefore = constraints.runsBefore.toList(),
            runsAfter = constraints.runsAfter.toList(),
        )
    }

    /** [add], with the system's type taken from [factory]'s return type. */
    public inline fun <reified T : SimSystem> add(
        phase: SimPhase,
        noinline factory: (GameContext) -> T,
        noinline configure: SystemConstraints.() -> Unit = {},
    ): Unit = add(phase, T::class, factory, configure)

    /**
     * Every registration in execution order.
     *
     * @throws SystemOrderException if a constraint names an unregistered system, contradicts
     *   the [SimPhase] ordinals, or forms a cycle.
     */
    internal fun resolve(): List<SystemRegistration> {
        val byType = HashMap<KClass<out SimSystem>, SystemRegistration>(registrations.size)
        for (registration in registrations) byType[registration.type] = registration

        val ordered = ArrayList<SystemRegistration>(registrations.size)
        for (phase in SimPhase.entries) {
            val inPhase = registrations.filter { it.phase == phase }
            if (inPhase.isEmpty()) continue

            val localIndex = HashMap<KClass<out SimSystem>, Int>(inPhase.size)
            inPhase.forEachIndexed { local, registration -> localIndex[registration.type] = local }

            val edges = ArrayList<OrderEdge>()
            inPhase.forEachIndexed { local, registration ->
                for (target in registration.runsBefore) {
                    edgeFor(registration, target, byType, localIndex, local, before = true)?.let(edges::add)
                }
                for (target in registration.runsAfter) {
                    edgeFor(registration, target, byType, localIndex, local, before = false)?.let(edges::add)
                }
            }

            for (local in SystemOrder.sort(inPhase.size, edges) { inPhase[it].name }) {
                ordered += inPhase[local]
            }
        }
        return ordered
    }

    /**
     * The edge [constraint] contributes, or `null` when it crosses a phase boundary and the
     * phase ordinals already satisfy it.
     *
     * A cross-phase constraint is not silently dropped: it is *checked*. `after<X>()` on a
     * system whose phase runs before X's phase is a contradiction the ordinals win — so it is
     * reported, at world construction, rather than leaving a module believing an ordering it
     * does not have. Checked and consistent, it stays as a live assertion: move either
     * system's phase later and the build fails at the constraint instead of at a desync.
     */
    private fun edgeFor(
        owner: SystemRegistration,
        target: KClass<out SimSystem>,
        byType: Map<KClass<out SimSystem>, SystemRegistration>,
        localIndex: Map<KClass<out SimSystem>, Int>,
        ownerLocal: Int,
        before: Boolean,
    ): OrderEdge? {
        val relation = if (before) "before" else "after"
        val other = byType[target] ?: throw SystemOrderException(
            "${owner.name} declares $relation(${target.java.name}), which no module registered; " +
                "register it or drop the constraint",
        )
        if (other.phase == owner.phase) {
            val otherLocal = requireNotNull(localIndex[target]) { "${other.name} is missing from its own phase" }
            return if (before) OrderEdge(ownerLocal, otherLocal) else OrderEdge(otherLocal, ownerLocal)
        }

        val satisfied =
            if (before) owner.phase.ordinal < other.phase.ordinal else owner.phase.ordinal > other.phase.ordinal
        if (!satisfied) {
            throw SystemOrderException(
                "${owner.name} in phase ${owner.phase} declares $relation(${other.name}) in phase " +
                    "${other.phase}, which the phase order contradicts; phases are absolute, so " +
                    "move one of the systems to a phase that agrees",
            )
        }
        return null
    }
}

/**
 * The `before`/`after` constraints of one registration.
 *
 * Systems are named by class rather than by the constructor reference used to build them: two
 * `::MySystem` references are two objects, so a constraint spelled that way could not be
 * matched to the registration it refers to, whereas `MySystem::class` is one identity the
 * compiler resolves.
 */
public class SystemConstraints internal constructor() {

    internal val runsBefore = ArrayList<KClass<out SimSystem>>()

    internal val runsAfter = ArrayList<KClass<out SimSystem>>()

    /** This system runs before [other]. */
    public fun before(other: KClass<out SimSystem>) {
        runsBefore += other
    }

    /** This system runs after [other]. */
    public fun after(other: KClass<out SimSystem>) {
        runsAfter += other
    }
}

/** This system runs before [T]. */
public inline fun <reified T : SimSystem> SystemConstraints.before(): Unit = before(T::class)

/** This system runs after [T]. */
public inline fun <reified T : SimSystem> SystemConstraints.after(): Unit = after(T::class)

/** One entry in a [SimRegistry], before it has been ordered. */
internal class SystemRegistration(
    /** Declaration order across every module, and the ordering tie-break. */
    val index: Int,
    val phase: SimPhase,
    val type: KClass<out SimSystem>,
    val factory: (GameContext) -> SimSystem,
    val runsBefore: List<KClass<out SimSystem>>,
    val runsAfter: List<KClass<out SimSystem>>,
) {
    /** Fully qualified, from the JVM class rather than `qualifiedName`: no reflection needed. */
    val name: String get() = type.java.name
}
