package dev.wildware.udea.render

import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.render.capture.FrameCaptureSlot

/**
 * Where a game declares what it draws, and in what order.
 *
 * The presentation-side mirror of simulation system registration, and deliberately the same
 * shape: register a constructor reference, optionally constrain it relative to another
 * registration, and let the registry work out the order once.
 *
 * ## What it replaces
 *
 * `UdeaGameManager.kt:280` registered systems by `KClass`, instantiated them with
 * `createInstance()` and decided where each one ran from a `@UdeaSystem(runIn = [...])`
 * annotation found by classpath scanning. Order came from position in a list that four
 * unrelated features edited. Here the factory is a constructor reference, so a missing
 * dependency is a compile error rather than a reflective failure at startup, and order is
 * stated as constraints between named registrations rather than implied by list position.
 *
 * ## Ordering
 *
 * Two keys, in this order:
 *
 * 1. **[RenderPhase] ordinal.** Coarse and engine-wide, so that "debug shapes draw over
 *    sprites" does not have to be restated at every registration site.
 * 2. **Topological order of the `before`/`after` constraints within the phase**, tie-broken
 *    by registration index. See [RenderOrder]; a cycle fails [build] with the cycle printed.
 *
 * Constraints do not cross phases. A cross-phase constraint is either redundant (it agrees
 * with the phase ordinal, and so says nothing) or a contradiction (it disagrees, and one of
 * the two has to lose silently). Both are worth failing on, at the registration site.
 */
public class RenderRegistry(
    /**
     * Where presentation's wall time comes from. Injected so a test can drive it by hand;
     * `FrameClock.Wall` is the only production value.
     */
    clock: FrameClock = FrameClock.Wall,
) {

    private val entries = ArrayList<Entry>()

    private val timer = FrameTimer(clock)

    /**
     * The per-frame wall delta, for the renderers entitled to one.
     *
     * Available *before* [build] because a system that animates on wall time takes it as a
     * constructor parameter, and the constructors run inside [build]:
     *
     * ```
     * registry.register(RenderPhase.World) { ParticleRenderSystem(batch, registry.frameTime) }
     * ```
     *
     * One instance per registry, republished once per frame by the pipeline, so two renderers
     * cannot end up measuring the same frame differently.
     */
    public val frameTime: FrameTime get() = timer

    /**
     * Registers a [RenderSystem] to run in [phase].
     *
     * @param factory a constructor reference, called once by [build]. Deferred so a system
     *   can take what it needs as constructor parameters and still be declared before the
     *   world exists.
     * @param constrain optional `before`/`after` constraints against handles this registry
     *   has already returned.
     * @throws IllegalArgumentException if [phase] is [RenderPhase.Overlay]: that phase runs
     *   after the capture point and draws onto the [ScreenTarget], so it takes
     *   [OverlaySystem]s only. Use [overlay].
     */
    public fun register(
        phase: RenderPhase,
        factory: (RenderResources) -> RenderSystem,
        constrain: RenderConstraints.() -> Unit = {},
    ): RenderHandle {
        require(phase.isCapturable) {
            "RenderPhase.Overlay takes an OverlaySystem, not a RenderSystem: a RenderSystem " +
                "there would be handed an OffscreenTarget after the capture point. Register " +
                "it with RenderRegistry.overlay(...) instead (spec 3.7)."
        }
        return add(phase, Entry.Kind.Scene(factory), constrain)
    }

    /**
     * Registers an [OverlaySystem], which always runs in [RenderPhase.Overlay] -- after the
     * capture point, onto the never-captured [ScreenTarget].
     *
     * There is no phase parameter, on purpose. An overlay that could pick its phase could
     * pick one a capture reads, and the exclusion the types are enforcing would go back to
     * being a convention.
     */
    public fun overlay(
        factory: (RenderResources) -> OverlaySystem,
        constrain: RenderConstraints.() -> Unit = {},
    ): RenderHandle = add(RenderPhase.Overlay, Entry.Kind.Overlay(factory), constrain)

    /**
     * Instantiates every registration, orders it, binds it, and returns the pipeline.
     *
     * Instantiation happens here rather than at registration so the whole declaration can be
     * written before a world exists, and so a system's constructor runs exactly once, at a
     * known point.
     *
     * @throws RenderOrderException if the constraints contain a cycle.
     * @throws IllegalArgumentException if a registered system is also a Fleks
     *   [IntervalSystem].
     */
    public fun build(
        world: World,
        ctx: GameContext,
        targets: RenderTargets,
    ): RenderPipeline {
        val resources = RenderResources(targets.batch, targets.offscreen)
        val instances: List<Bound> = entries.map { entry ->
            when (val kind = entry.kind) {
                is Entry.Kind.Scene -> Bound.Scene(requireNotAFleksSystem(kind.make(resources)))
                is Entry.Kind.Overlay -> Bound.Overlay(requireNotAFleksSystem(kind.make(resources)))
            }
        }

        val systems = ArrayList<RenderSystem>(entries.size)
        val overlays = ArrayList<OverlaySystem>()

        for (phase in RenderPhase.entries) {
            for (index in orderWithin(phase, instances)) {
                when (val instance = instances[index]) {
                    is Bound.Scene -> systems += instance.system
                    is Bound.Overlay -> overlays += instance.system
                }
            }
        }

        for (index in systems.indices) systems[index].onBind(world, ctx)
        val capture = targets.pixels?.let { FrameCaptureSlot(it, ctx.clock) }
        // targets.owned first: a system's own resources were built against the batch and the
        // framebuffer, so reverse-order disposal has to release them before those.
        return RenderPipeline(
            targets,
            systems,
            overlays,
            timer,
            capture,
            targets.owned + resources.owned(),
        )
    }

    /** Registration indices belonging to [phase], in the order they must run. */
    private fun orderWithin(phase: RenderPhase, instances: List<Bound>): List<Int> {
        val members = entries.filter { it.phase == phase }.map { it.index }
        if (members.isEmpty()) return emptyList()

        val localOf = HashMap<Int, Int>(members.size)
        members.forEachIndexed { local, global -> localOf[global] = local }

        val edges = ArrayList<OrderEdge>()
        for (global in members) {
            val entry = entries[global]
            val local = localOf.getValue(global)
            for (other in entry.after) edges += OrderEdge(localOf.getValue(other.index), local)
            for (other in entry.before) edges += OrderEdge(local, localOf.getValue(other.index))
        }

        val sorted = RenderOrder.sort(members.size, edges) { local ->
            describe(instances[members[local]], members[local])
        }
        return sorted.map { local -> members[local] }
    }

    private fun add(
        phase: RenderPhase,
        kind: Entry.Kind,
        constrain: RenderConstraints.() -> Unit,
    ): RenderHandle {
        val index = entries.size
        val handle = RenderHandle(this, index, phase)
        val constraints = RenderConstraints(this, handle).apply(constrain)
        entries += Entry(index, phase, kind, constraints.beforeHandles(), constraints.afterHandles())
        return handle
    }

    private fun describe(instance: Bound, index: Int): String {
        val system = when (instance) {
            is Bound.Scene -> instance.system
            is Bound.Overlay -> instance.system
        }
        return "${system::class.qualifiedName ?: system::class.java.name}#$index"
    }

    /** A registration after its factory has run: still tagged with which kind it is. */
    private sealed interface Bound {
        class Scene(val system: RenderSystem) : Bound
        class Overlay(val system: OverlaySystem) : Bound
    }

    private class Entry(
        val index: Int,
        val phase: RenderPhase,
        val kind: Kind,
        val before: List<RenderHandle>,
        val after: List<RenderHandle>,
    ) {
        sealed interface Kind {
            class Scene(val make: (RenderResources) -> RenderSystem) : Kind
            class Overlay(val make: (RenderResources) -> OverlaySystem) : Kind
        }
    }

    private companion object {

        /**
         * A presentation system that is *also* a Fleks system is registrable into the world's
         * system list, and the moment somebody did that, `world.update(dt)` would issue GL
         * calls again -- the exact defect spec 3.3 removes. The type split makes that a
         * mistake you have to go out of your way to make; this makes it one that fails loudly
         * when you do.
         */
        fun <T : Any> requireNotAFleksSystem(system: T): T {
            require(system !is IntervalSystem) {
                "${system::class.java.name} is a presentation system and a Fleks IntervalSystem " +
                    "at once. Presentation is not in the world's system list: that is what keeps " +
                    "world.update(dt) pure simulation (spec 3.3)."
            }
            return system
        }
    }
}

/**
 * What a registration gives back: the identity a later registration constrains itself
 * against.
 *
 * A handle rather than a `KClass` because registering two instances of one renderer class is
 * legitimate (two viewports, two cameras), and because comparing handles needs no reflection.
 */
public class RenderHandle internal constructor(
    internal val registry: RenderRegistry,
    /** Registration order, and the tie-break between two otherwise unordered systems. */
    internal val index: Int,
    /** The phase this registration runs in. Constraints may not cross phases. */
    public val phase: RenderPhase,
) {
    override fun toString(): String = "RenderHandle($phase#$index)"
}

/**
 * Collects the `before`/`after` constraints of one registration.
 *
 * A registration cannot constrain itself: the handle that identifies it is returned *after*
 * this block runs, so there is nothing to name. A cycle therefore always involves at least
 * two registrations, and [RenderOrder] is what reports it.
 */
public class RenderConstraints internal constructor(
    private val registry: RenderRegistry,
    private val self: RenderHandle,
) {
    private val before = ArrayList<RenderHandle>()
    private val after = ArrayList<RenderHandle>()

    /** This system runs before [other]. */
    public fun before(other: RenderHandle) {
        before += checked(other, "before")
    }

    /** This system runs after [other]. */
    public fun after(other: RenderHandle) {
        after += checked(other, "after")
    }

    internal fun beforeHandles(): List<RenderHandle> = before.toList()

    internal fun afterHandles(): List<RenderHandle> = after.toList()

    private fun checked(other: RenderHandle, relation: String): RenderHandle {
        require(other.registry === registry) {
            "$other was registered with a different RenderRegistry; a constraint across two " +
                "registries orders nothing"
        }
        require(other.phase == self.phase) {
            "$self cannot be constrained '$relation' $other: they are in different phases, and " +
                "the phase ordinal already decides that. A cross-phase constraint is either " +
                "redundant or a contradiction."
        }
        return other
    }
}
