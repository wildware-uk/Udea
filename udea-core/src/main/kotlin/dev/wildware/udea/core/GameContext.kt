package dev.wildware.udea.core

import com.github.quillraven.fleks.InjectableConfiguration

/**
 * Everything a simulation system is allowed to reach that is not a component.
 *
 * ## What it replaces
 *
 * `common/UdeaGameManager.kt:82-83` declared `lateinit var gameScreen: GameScreen` and
 * `lateinit var gameManager: UdeaGameManager` at file level, assigned from a constructor
 * (`init { gameScreen = this }`) and read from 36 files, including simulation code:
 * `Networkable.kt:34` read `gameScreen.isServer`, `Debug.kt:11` read `gameScreen.time`,
 * `CharacterControllerSystem.kt:16` read `gameScreen.gameConfig`. Two worlds in one JVM was
 * structurally impossible, which is precisely what the MCP surface (a headless world beside
 * a rendered one) and networking (a server and a client in one process) both need.
 *
 * A [GameContext] is constructed, injected and read. There is no global to reach for. Field
 * mapping for whoever migrates an old call site:
 *
 * | old | new |
 * |---|---|
 * | `gameScreen.isServer` | `ctx.role` |
 * | `gameScreen.time` | `ctx.clock.time` |
 * | `gameScreen.delta` | `ctx.clock.dt` |
 * | `gameScreen.gameConfig` | `ctx.config` |
 * | `gameScreen.camera` | `FrameContext.camera` — unreachable from simulation, by design |
 *
 * ## The sole injectable
 *
 * This is the **only** thing registered with Fleks. One injectable means a system's
 * dependencies are visible in its source rather than scattered across a world configuration,
 * and it means adding a service never edits a world builder. Register it with [gameContext]
 * and read it with [SimSystem.ctx]; a test asserts nothing else is ever registered.
 *
 * ## Extending it without editing it
 *
 * A module that owns a service `GameContext` does not name registers a [ServiceKey] instead
 * of adding a field. See [GameContextBuilder.service] and [get].
 */
public class GameContext internal constructor(
    /** The simulation clock. `ctx.clock.tick` is the current tick. */
    public val clock: SimClock,
    /** Immutable engine knobs. */
    public val config: EngineConfig,
    /** What this simulation is, authority-wise. */
    public val role: NetRole,
    /** Seeded randomness. The only source simulation code may use. */
    public val rng: RngService,
    /** Non-authoritative physics. Never snapshot state (spec 3.4). */
    public val physics: PhysicsWorld,
    /** Scene lifecycle. Swaps land between ticks. */
    public val scenes: SceneManager,
    /** Outbound presentation cues. Write-only from the simulation's side. */
    public val cues: CueSink,
    /** Engine logging, scoped to this simulation. */
    public val log: Log,
    private val services: Map<ServiceKey<*>, Any>,
) {

    /** Shorthand for `clock.tick`, which is what most systems actually want. */
    public val tick: Tick get() = clock.tick

    /** Every key registered on this context. Stable iteration order: registration order. */
    public val serviceKeys: Set<ServiceKey<*>> get() = services.keys

    /**
     * The service registered under [key].
     *
     * @throws MissingServiceException if nothing was registered under it. Failing loudly is
     *   the point: a system that silently ran without its service would produce a divergence
     *   far from its cause.
     */
    public operator fun <T : Any> get(key: ServiceKey<T>): T =
        getOrNull(key) ?: throw MissingServiceException(
            "No service registered for ${key.name}; register it with GameContextBuilder.service(${key.name}, ...)",
        )

    /** The service registered under [key], or `null`. */
    public fun <T : Any> getOrNull(key: ServiceKey<T>): T? {
        val value = services[key] ?: return null
        return key.type.java.cast(value)
    }

    public operator fun contains(key: ServiceKey<*>): Boolean = services.containsKey(key)

    override fun toString(): String =
        "GameContext(role=$role, tick=${clock.tick}, services=${services.size})"

    public companion object {
        /**
         * The name this context is registered under in a Fleks world.
         *
         * Explicit rather than derived from the class name so that obfuscation or a rename
         * cannot silently change an injection key.
         */
        public const val INJECT_NAME: String = "GameContext"
    }
}

/**
 * The key a module uses to contribute a service without editing [GameContext].
 *
 * Identity-compared, so a key is declared once as a `val` in the module that owns the
 * service and shared by reference. Two keys with the same name are still different keys —
 * which is correct, because the module that declared each one is what actually identifies it.
 */
public class ServiceKey<T : Any>(
    public val name: String,
    public val type: kotlin.reflect.KClass<T>,
) {
    override fun toString(): String = "ServiceKey($name: ${type.simpleName})"
}

/** Declares a [ServiceKey] for [T]. */
public inline fun <reified T : Any> serviceKey(name: String): ServiceKey<T> =
    ServiceKey(name, T::class)

/** A service a [GameContext] needed and did not have. */
public class MissingServiceException(message: String) : IllegalStateException(message)

/**
 * Builds a [GameContext].
 *
 * The named services are required: a context missing one fails here, at construction, naming
 * every absent service at once, rather than at the first tick that happens to touch it. [log]
 * is the exception because discarding log output is a complete and legitimate behaviour.
 */
public class GameContextBuilder {

    public var config: EngineConfig = EngineConfig()

    public var role: NetRole = NetRole.Standalone

    /** Optional: defaults to a clock at tick zero running at [config]'s tick rate. */
    public var clock: SimClock? = null

    public var rng: RngService? = null

    public var physics: PhysicsWorld? = null

    public var scenes: SceneManager? = null

    public var cues: CueSink? = null

    public var log: Log = Log.NoOp

    private val services = LinkedHashMap<ServiceKey<*>, Any>()

    /**
     * Registers [instance] under [key].
     *
     * @throws IllegalArgumentException if [key] is already registered. Silently replacing a
     *   service would let load order decide which implementation a simulation gets.
     */
    public fun <T : Any> service(key: ServiceKey<T>, instance: T) {
        require(!services.containsKey(key)) { "Service already registered: ${key.name}" }
        services[key] = instance
    }

    public fun build(): GameContext {
        val missing = ArrayList<String>(4)
        if (rng == null) missing += "rng"
        if (physics == null) missing += "physics"
        if (scenes == null) missing += "scenes"
        if (cues == null) missing += "cues"
        if (missing.isNotEmpty()) {
            throw MissingServiceException(
                "GameContext is missing required service(s): ${missing.joinToString()}",
            )
        }

        val resolvedClock = clock ?: SimClock(config.tickRate)
        require(resolvedClock.tickRate == config.tickRate) {
            "clock tick rate ${resolvedClock.tickRate} disagrees with config tick rate ${config.tickRate}"
        }

        return GameContext(
            clock = resolvedClock,
            config = config,
            role = role,
            rng = requireNotNull(rng),
            physics = requireNotNull(physics),
            scenes = requireNotNull(scenes),
            cues = requireNotNull(cues),
            log = log,
            services = LinkedHashMap(services),
        )
    }
}

/** Builds a [GameContext]. */
public fun gameContext(configure: GameContextBuilder.() -> Unit): GameContext =
    GameContextBuilder().apply(configure).build()

/**
 * Registers [context] as a Fleks injectable — the only call a world configuration should
 * ever make inside `injectables { }`.
 */
public fun InjectableConfiguration.gameContext(context: GameContext) {
    add(GameContext.INJECT_NAME, context)
}
