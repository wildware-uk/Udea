package dev.wildware.udea.replay.equality.fixture

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.EngineConfig
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.RngStream
import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.fixtures.QueueingSceneManager
import dev.wildware.udea.core.fixtures.RecordingCueSink
import dev.wildware.udea.core.fixtures.RecordingPhysicsWorld
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.loop.SimBarrier
import dev.wildware.udea.core.loop.WorldSimulation
import dev.wildware.udea.core.loop.simBarrier
import dev.wildware.udea.core.rng.DefaultRngService
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.SnapshotService
import dev.wildware.udea.core.snapshot.WorldHasher
import dev.wildware.udea.core.snapshot.WorldSnapshot
import dev.wildware.udea.replay.InputSample
import dev.wildware.udea.replay.InputSchema
import dev.wildware.udea.replay.ReplayWorld
import dev.wildware.udea.replay.ReplayWorldFactory

/**
 * This tick's pilot input, as the fixture's systems read it.
 *
 * The seam an `IntentSource` is in a real game: nothing here knows whether the numbers came from
 * a recording, a keyboard or an agent, which is the property replay rests on. Injected into
 * [DriftSystem] by constructor rather than reached for through [GameContext] - a system declares
 * what it needs, and `GameContext` is not a bag things get added to (engineering standards §2).
 */
public class DriftInput {

    /** Steering, `[-1, 1]` on each axis. */
    public var moveX: Float = 0f

    /** Steering, `[-1, 1]` on each axis. */
    public var moveY: Float = 0f

    /** How many times the pilot has pressed `pulse` in total. A press fires a [Charge]. */
    public var pulseCount: Int = 0

    /** Copies one sample in. Called once per tick, immediately before the tick runs. */
    public fun readFrom(sample: InputSample) {
        moveX = sample.axisX(DriftFixture.AXIS_MOVE)
        moveY = sample.axisY(DriftFixture.AXIS_MOVE)
        pulseCount = sample.pressCount(DriftFixture.ACTION_PULSE)
    }
}

/**
 * Integrates every drifter's heading and position, and spends energy doing it.
 *
 * ## Float arithmetic, chosen deliberately
 *
 * Trigonometry goes through `StrictMath`, which is bit-exact by specification, rather than
 * `java.lang.Math` or `MathUtils.sin`. `determinism-audit.md` §3.1 measured `Math.sin` disagreeing
 * with `StrictMath.sin` on 67,912 of 2,000,000 sampled inputs on one JVM, which is what makes
 * LibGDX's `MathUtils$Sin` table a per-JVM artifact - and no bytecode rule can see it, because the
 * bytecode is identical on both platforms.
 *
 * So this fixture models the *correct* choice, and the divergence the gate is proven against is
 * planted rather than borrowed from a JVM that might not oblige. What the fixture supplies is
 * sensitivity: heading feeds position and position feeds the bounce that feeds heading, so a
 * one-ulp difference does not stay one ulp for long.
 */
internal class DriftSystem(private val input: DriftInput) : SimSystem() {

    private val family: Family = world.family { all(Drifter) }

    override fun onTick() {
        val entities = family.entities
        var index = 0
        while (index < entities.size) {
            val entity: Entity = entities[index]
            val drifter = entity[Drifter]
            // The lead drifter - the lowest slot - is the one the pilot steers. Every other one
            // turns on the seeded stream, so the recording matters and so does the seed.
            val steer = if (index == LEAD) input.moveX else ctx.rng.nextFloat(RngStream.AI) - HALF
            drifter.heading += steer * TURN_RATE
            if (drifter.heading > TAU) drifter.heading -= TAU
            if (drifter.heading < -TAU) drifter.heading += TAU

            val speed = BASE_SPEED + drifter.energy * SPEED_PER_ENERGY
            drifter.x += (StrictMath.cos(drifter.heading.toDouble()) * speed).toFloat()
            drifter.y += (StrictMath.sin(drifter.heading.toDouble()) * speed).toFloat()

            if (drifter.x > BOUND || drifter.x < -BOUND) {
                drifter.heading = TAU / 2f - drifter.heading
                drifter.lastTurnTick = tick
            }
            if (drifter.y > BOUND || drifter.y < -BOUND) {
                drifter.heading = -drifter.heading
                drifter.lastTurnTick = tick
            }

            drifter.energy += (if (index == LEAD) input.moveY else ctx.rng.nextFloat(RngStream.AI)) *
                ENERGY_GAIN - ENERGY_DRAIN
            if (drifter.energy < 0f) drifter.energy = 0f
            if (drifter.energy > MAX_ENERGY) drifter.energy = MAX_ENERGY
            index++
        }
    }

    private companion object {
        const val LEAD: Int = 0
        const val TURN_RATE: Float = 0.037f
        const val TAU: Float = 6.283185f
        const val HALF: Float = 0.5f
        const val BASE_SPEED: Float = 0.11f
        const val SPEED_PER_ENERGY: Float = 0.017f
        const val BOUND: Float = 24f
        const val ENERGY_GAIN: Float = 0.031f
        const val ENERGY_DRAIN: Float = 0.013f
        const val MAX_ENERGY: Float = 8f
    }
}

/**
 * Fires, counts down and removes [Charge]s, so component presence moves while the world runs.
 *
 * A pulse press adds a charge to the lead drifter aimed at another; the charge counts down and is
 * removed. Presence bits, the per-component slot count and a `NetId`-typed field all change over
 * the fixture's life as a result, and all three are folded into the world hash.
 */
internal class ChargeSystem(
    private val input: DriftInput,
    private val netIds: NetIdIndex,
) : SimSystem() {

    private val drifters: Family = world.family { all(Drifter) }
    private val charged: Family = world.family { all(Charge) }
    private var lastPulseCount: Int = 0

    override fun onTick() {
        val active = charged.entities
        var index = active.size - 1
        while (index >= 0) {
            val entity = active[index]
            val charge = entity[Charge]
            charge.remaining--
            if (charge.remaining <= 0) entity.configure { it -= Charge }
            index--
        }

        if (input.pulseCount > lastPulseCount && drifters.entities.size > 1) {
            val source = drifters.entities[0]
            val victim = drifters.entities[1 + ctx.rng.nextInt(RngStream.Combat, drifters.entities.size - 1)]
            source.configure {
                val charge = it.getOrAdd(Charge) { Charge() }
                charge.remaining = CHARGE_TICKS
                charge.target = netIds.netIdOf(victim)
            }
        }
        lastPulseCount = input.pulseCount
    }

    private companion object {
        const val CHARGE_TICKS: Int = 45
    }
}

/**
 * Spawns drifters and retires them, so the roster and the id free list both churn.
 *
 * Without this the `NetIdIndex` free list would be empty for the whole fixture and the
 * `<handles>` cells would be constant - which is exactly the "an empty fixture is not a neutral
 * one" trap: a gate proven only against a world that never frees an id says nothing about one
 * that does.
 */
internal class PopulationSystem(private val netIds: NetIdIndex) : SimSystem() {

    private val drifters: Family = world.family { all(Drifter) }

    override fun onTick() {
        val at = tick.value
        if (at % SPAWN_INTERVAL == 0L && drifters.entities.size < MAX_POPULATION) {
            val entity = world.entity {
                it += Drifter(
                    x = (at % SPREAD).toFloat() * SPREAD_SCALE,
                    y = -(at % SPREAD).toFloat() * SPREAD_SCALE,
                    heading = (at % HEADINGS).toFloat() * HEADING_STEP,
                    energy = (at % ENERGIES).toFloat() * ENERGY_STEP,
                )
            }
            netIds.allocate(entity)
        }
        if (at % RETIRE_INTERVAL == 0L && drifters.entities.size > MIN_POPULATION) {
            // The newest, not the oldest: the lead drifter the pilot steers must survive the
            // whole fixture, or the recording stops mattering half way through it.
            val entity = drifters.entities[drifters.entities.size - 1]
            val netId = netIds.netIdOf(entity)
            world -= entity
            netIds.free(netId)
        }
    }

    private companion object {
        const val SPAWN_INTERVAL: Long = 37L
        const val RETIRE_INTERVAL: Long = 53L
        const val MIN_POPULATION: Int = 8
        const val MAX_POPULATION: Int = 20
        const val SPREAD: Long = 17L
        const val SPREAD_SCALE: Float = 0.75f
        const val HEADINGS: Long = 11L
        const val HEADING_STEP: Float = 0.5f
        const val ENERGIES: Long = 7L
        const val ENERGY_STEP: Float = 0.25f
    }
}

/**
 * A whole headless simulation the `replay-equality` fixture is recorded from and replayed into.
 *
 * Everything is real: a real Fleks world, the real `WorldSimulation`, the real `SimBarrier`, the
 * real `DefaultRngService` and the real `SnapshotService`. There is no GL, no window and no
 * global, which is the property that lets the whole gate run inside a plain JVM process on a CI
 * runner with no display.
 *
 * @param plantUlpAt the tick at which to nudge the lead drifter's `x` by one ulp, or `null` for
 *   the honest run. See [DriftFixture.PLANT_DESCRIPTION] for why the perturbation is one ulp and
 *   not something easier to see.
 */
public class DriftWorld(
    seed: Long = DriftFixture.SEED,
    firstTick: Tick = Tick.ZERO,
    private val plantUlpAt: Tick? = null,
) : ReplayWorld {

    /** The registry this world captures through. Handed to the digest for its component table. */
    public val registry: ComponentRegistry = DriftComponents.registry()

    private val netIds = NetIdIndex(capacity = ID_CAPACITY, entityCapacity = ID_CAPACITY)
    private val barrier = SimBarrier()
    private val input = DriftInput()

    private val ctx: GameContext = gameContext {
        config = EngineConfig(seed = seed)
        // The production generator, not the fixture double: a SnapshotService needs a
        // CapturableRng, and the RNG state is folded into the hash this whole gate compares.
        rng = DefaultRngService(seed)
        physics = RecordingPhysicsWorld()
        scenes = QueueingSceneManager(SceneId(SCENE))
        cues = RecordingCueSink()
        simBarrier(barrier)
    }

    private val fleks: World = configureWorld {
        injectables { gameContext(ctx) }
        systems {
            add(PopulationSystem(netIds))
            add(DriftSystem(input))
            add(ChargeSystem(input, netIds))
        }
    }

    private val simulation = WorldSimulation(ctx, fleks, barrier)
    private val service = SnapshotService(registry, fleks, ctx, netIds)
    private val buffer: WorldSnapshot = service.newSnapshot()
    private val leadNetId: NetId

    init {
        check(ctx.clock.tick == firstTick) {
            "a fresh DriftWorld comes up at ${ctx.clock.tick} and was asked for $firstTick"
        }
        val lead = fleks.entity { it += Drifter(x = LEAD_X, y = LEAD_Y, energy = LEAD_ENERGY) }
        leadNetId = netIds.allocate(lead)
        repeat(DriftFixture.INITIAL_FOLLOWERS) { index ->
            val entity = fleks.entity {
                it += Drifter(
                    x = index * FOLLOWER_SPACING,
                    y = -index * FOLLOWER_SPACING,
                    heading = index * FOLLOWER_HEADING,
                    energy = index * FOLLOWER_ENERGY,
                )
            }
            netIds.allocate(entity)
        }
    }

    override val tick: Tick get() = ctx.clock.tick

    override fun applyInput(samples: Array<InputSample>) {
        require(samples.isNotEmpty()) { "the drift fixture needs the pilot's sample" }
        input.readFrom(samples[0])
    }

    override fun step() {
        // Read before the step, because `SimClock.tick` names the tick about to be simulated and
        // has already advanced by the time the step returns. A digest indexes its stream by the
        // tick that *was* simulated, so a plant keyed off the post-step clock lands one tick
        // earlier than the number somebody typed - which is exactly the kind of off-by-one that
        // makes a gate look correct while pointing at the wrong tick.
        val simulated = ctx.clock.tick
        simulation.step()
        plant(simulated)
    }

    /**
     * The planted, float-sensitive divergence: one ulp on one field of one entity, once.
     *
     * After the step rather than inside a system, so it is plainly a plant and cannot be mistaken
     * for the simulation's own arithmetic by somebody reading `DriftSystem`. `Math.nextUp` is one
     * representable step, which is the same magnitude `determinism-audit.md` §3.1 measured
     * `Math.sin` differing by - "disagree in the last bit" - and is deliberately far too small to
     * see in a rendering of the number without the raw bits beside it.
     */
    private fun plant(simulated: Tick) {
        if (plantUlpAt == null || simulated != plantUlpAt) return
        val entity = checkNotNull(netIds.resolveOrNull(leadNetId)) { "$leadNetId is not live" }
        with(fleks) {
            val drifter = entity[Drifter]
            drifter.x = Math.nextUp(drifter.x)
        }
    }

    override fun hash(): Long {
        service.captureInto(buffer)
        return WorldHasher.hash(buffer)
    }

    /**
     * A fresh capture, not [buffer].
     *
     * A digest writer holds the snapshot only for as long as it takes to walk it, but handing out
     * the same buffer the hash was taken into would tie the two together for no reason, and a
     * caller that wanted two ticks at once would silently get one twice.
     */
    override fun snapshot(): WorldSnapshot = service.capture()

    override fun toString(): String = "DriftWorld(at $tick, plantUlpAt=$plantUlpAt)"

    public companion object {

        /** The scene the fixture runs in. One scene, never swapped: a swap is not what is tested. */
        public const val SCENE: String = "drift"

        /** A factory that builds a fresh fixture world at the recording's first tick. */
        public fun worlds(plantUlpAt: Tick? = null): ReplayWorldFactory = ReplayWorldFactory { first ->
            DriftWorld(firstTick = first, plantUlpAt = plantUlpAt)
        }

        private const val ID_CAPACITY: Int = 1024
        private const val LEAD_X: Float = 1.5f
        private const val LEAD_Y: Float = -2.25f
        private const val LEAD_ENERGY: Float = 3.5f
        private const val FOLLOWER_SPACING: Float = 1.25f
        private const val FOLLOWER_HEADING: Float = 0.37f
        private const val FOLLOWER_ENERGY: Float = 0.4f
    }
}

/** The one [InputSchema] the fixture's recording is written in, and its constants. */
public object DriftFixture {

    /** The seed every fixture world is built with. */
    public const val SEED: Long = 20_260_831L

    /** How many drifters stand beside the lead one at boot. */
    public const val INITIAL_FOLLOWERS: Int = 11

    /** The pilot's steering axis. */
    public const val AXIS_MOVE: Int = 0

    /** The pilot's one action. */
    public const val ACTION_PULSE: Int = 0

    /** The PR fixture's length: one minute of simulated time at 60Hz. */
    public const val PR_TICKS: Int = 3600

    /** The name the PR fixture is checked in under, and the name a digest header carries. */
    public const val PR_FIXTURE: String = "drift-3600.udearep"

    /** The classpath resource the PR fixture is read from. */
    public const val PR_RESOURCE: String = "/fixtures/drift-3600.udearep"

    /** How this game names itself in a recording header. */
    public const val GAME_ID: String = "udea-replay-equality-fixture"

    /** Bumped whenever the fixture world's arithmetic changes, so an old recording is refused. */
    public const val GAME_VERSION: String = "1"

    /**
     * The tick the proof plants its divergence at, when one is asked for.
     *
     * A third of the way in, so a report has the full five ticks of history to print and the run
     * has plenty of matching ticks behind it - a divergence at tick one would pass every
     * assertion here while proving nothing about a stream that has to stay in step for a minute.
     */
    public val PLANT_TICK: Tick = Tick(1_200L)

    /** Why the plant is one ulp, in one sentence, for a report that has to justify itself. */
    public const val PLANT_DESCRIPTION: String =
        "one ulp on Drifter.x of the lead drifter, which is the magnitude determinism-audit.md " +
            "section 3.1 measured Math.sin differing from StrictMath.sin by"

    /**
     * The vocabulary a fixture recording is written in.
     *
     * One axis and one action, because the recording's job is to make the replay depend on
     * something outside the seed, not to model a control scheme.
     */
    public val SCHEMA: InputSchema = InputSchema(
        axes = listOf("drift/move"),
        actions = listOf("drift/pulse"),
    )
}
