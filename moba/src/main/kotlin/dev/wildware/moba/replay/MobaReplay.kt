package dev.wildware.moba.replay

import dev.wildware.moba.MobaAssets
import dev.wildware.moba.MobaControls
import dev.wildware.moba.MobaGame
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.moba.net.MobaNet
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.SnapshotService
import dev.wildware.udea.core.snapshot.WorldHasher
import dev.wildware.udea.core.snapshot.WorldSnapshot
import dev.wildware.udea.render.input.Intent
import dev.wildware.udea.render.input.IntentSource
import dev.wildware.udea.render.input.IntentState
import dev.wildware.udea.replay.BuildIdentity
import dev.wildware.udea.replay.InputSample
import dev.wildware.udea.replay.InputSchema
import dev.wildware.udea.replay.PeerId
import dev.wildware.udea.replay.ReplayRecorder
import dev.wildware.udea.replay.ReplayRecording
import dev.wildware.udea.replay.ReplayWorld
import dev.wildware.udea.replay.ReplayWorldFactory

/**
 * `moba`'s half of Phase 7: what this game's inputs are, and how a recording is played back.
 *
 * ## The six lines `udea-replay` cannot write
 *
 * `udea-replay` is a designated headless module, so it may not name `Intent`, `IntentSource` or
 * `IntentState`, all of which live in `udea-render` - the one module on the GL convention. The
 * arrow it would need runs the wrong way through `ModuleGraphRules.HEADLESS_PROJECTS`, and
 * putting `:udea-replay` in `GL_ALLOWED_PROJECTS` to get it would be exactly backwards for the
 * module whose job is running a match with no device attached.
 *
 * So the copy between an [Intent] and an [InputSample] is here, in the game, where both types
 * are already on the classpath. It is genuinely six lines each way, and the direction it does
 * *not* need is the interesting one: nothing here reads a device. `IntentSource` is the seam a
 * keyboard, an agent's `input.*` tools and this file's [ReplayIntentSource] all sit behind, and
 * the simulation cannot tell them apart - which is the property the whole phase rests on.
 *
 * ## What a `moba` recording actually contains
 *
 * One peer - the human champion - and the tick stream of what they held, pressed and pushed a
 * stick to. Everything else in the match is a consequence: the twenty-seven units are AI driven,
 * and their AI draws from the seeded named `RngStream`s, so it is reproduced by the seed rather
 * than recorded. That is not a shortcut, it is the point: if the AI needed recording, the seed
 * would not be doing its job and `RngStreamIsolationTest` would be a lie.
 */
public object MobaReplay {

    /** How a recording made by this game names itself in its own header. */
    public const val GAME_ID: String = MobaGame.NAME

    /** The peer a single-player recording's champion input is filed under. */
    public val LOCAL_PEER: PeerId = PeerId(0)

    /**
     * This game's input vocabulary, as a recording writes it down.
     *
     * Taken from `MobaControls.BINDINGS.catalog`, which is built from the packed
     * `control/controls.udea.kts` asset, so a rebind is an asset edit that changes
     * [InputSchema.hash] and makes every older recording refuse itself by name. That is correct
     * behaviour and worth stating: `InputCatalog` numbers actions by sorted name across the
     * whole game, so binding one new key shifts every id after it, and a recording replayed
     * against the shifted numbering would press `attack_2` where the player pressed `attack` -
     * with the arrays the same length and every value in range, so nothing would notice.
     */
    public val SCHEMA: InputSchema = InputSchema(
        axes = MobaControls.BINDINGS.catalog.axes,
        actions = MobaControls.BINDINGS.catalog.actions,
    )

    /**
     * The four fields a replay of this build is refused over.
     *
     * [BuildIdentity.rootSeed] is read from the host rather than from `EngineConfig`, because
     * the host is what actually seeded the streams and a config read separately could describe
     * a different one.
     */
    public fun identityOf(host: GameHost): BuildIdentity = BuildIdentity(
        rootSeed = host.ctx.rng.seed,
        protoHash = MobaNet.protocol(MobaNet.registry()).protoHash,
        assetGraphHash = MobaAssets.registry.contentHash,
        inputSchemaHash = SCHEMA.hash,
    )

    /**
     * The one [dev.wildware.udea.core.snapshot.ComponentRegistry] every replay capture is taken
     * against.
     *
     * A single instance, and it has to be. `WorldFieldStore.diffInto` refuses two stores built
     * from **different registry objects** - `cannot diff stores built from different component
     * registries` - because a column index only means anything relative to the registry that
     * laid it out. Two `MobaGame.componentRegistry()` calls produce registries that are equal in
     * every field and identical in none, so a divergence report over a baseline captured through
     * one and a replay captured through the other throws instead of naming a field.
     *
     * That is not hypothetical: it is what `a corrupted recording is caught at the tick it was
     * corrupted` found, on the only path a bit-exact run never reaches. The bug was invisible to
     * every passing test and would have surfaced the first time an agent bisected a real
     * divergence, which is the worst possible moment for it.
     *
     * Immutable once built - a registry is a list of stateless `ReplicatedComponentType`s - so
     * sharing it across two hosts in one process is safe, and it is built over the default
     * `CharacterAttributes` table, the same one `MobaNet.registry()` uses.
     */
    public val REGISTRY: ComponentRegistry by lazy { MobaGame.componentRegistry() }

    /**
     * A capture service for [host], over [REGISTRY].
     *
     * Its own service rather than the host's snapshot ring: the ring captures on a cadence and
     * pools its slots, and a replay needs a capture on **every** tick into a buffer nothing else
     * will overwrite.
     */
    public fun snapshots(host: GameHost): SnapshotService = SnapshotService(
        REGISTRY,
        host.world,
        host.ctx,
        host.ctx[CoreModule.NET_IDS],
    )

    /** A fresh headless `moba`, with the level loaded and the player standing in it. */
    public fun bootHeadless(): GameHost {
        val host = MobaGame.host(RenderMode.Headless)
        MobaEntry.seed(host)
        return host
    }

    /** A recorder over this game's schema, ready for the first tick. */
    public fun recorder(host: GameHost): ReplayRecorder = ReplayRecorder(
        identityWithoutSchema = identityOf(host),
        schema = SCHEMA,
        peerCount = 1,
        gameId = GAME_ID,
        gameVersion = MobaGame.VERSION,
    )

    /**
     * A [ReplayWorldFactory] that boots a fresh headless `moba` at the recording's first tick.
     *
     * It refuses rather than fast-forwarding, and the refusal is the interesting part. A
     * recording must begin where a fresh boot lands - `MobaEntry.seed` costs exactly one tick,
     * because a scene swap is a barrier action and the world is empty until the tick that drains
     * it - so a recording that starts later was made from a world with unrecorded history in it.
     * Fast-forwarding to reach it would run those ticks with an *idle* input, which is a
     * different game, and the resulting divergence would point at a tick whose cause was in the
     * ticks this factory invented.
     */
    public fun worlds(): ReplayWorldFactory = ReplayWorldFactory { firstTick ->
        val host = bootHeadless()
        check(host.tick == firstTick) {
            "a fresh headless moba comes up at ${host.tick} and this recording starts at " +
                "$firstTick. A recording must begin where a boot lands: reaching a later tick " +
                "would mean running ticks whose input is not in the recording, and the replay " +
                "would then diverge over a history this factory made up"
        }
        MobaReplayWorld(host)
    }

    /** Copies one tick of [intent] into [sample]. Allocation-free; called per tick while recording. */
    public fun capture(intent: Intent, sample: InputSample) {
        for (axis in 0 until SCHEMA.axisCount) {
            val id = dev.wildware.udea.render.input.AxisId(axis)
            sample.setAxis(axis, intent.axisX(id), intent.axisY(id))
        }
        for (action in 0 until SCHEMA.actionCount) {
            val id = dev.wildware.udea.render.input.ActionId(action)
            sample.setPressed(action, intent.isPressed(id))
            sample.setPressCount(action, intent.pressCount(id))
        }
    }

    /** Copies [sample] into [intent], which arrives cleared. The replay half of [capture]. */
    public fun apply(sample: InputSample, intent: Intent) {
        for (axis in 0 until SCHEMA.axisCount) {
            intent.setAxis(
                dev.wildware.udea.render.input.AxisId(axis),
                sample.axisX(axis),
                sample.axisY(axis),
            )
        }
        for (action in 0 until SCHEMA.actionCount) {
            val id = dev.wildware.udea.render.input.ActionId(action)
            intent.setPressed(id, sample.isPressed(action))
            intent.setPressCount(id, sample.pressCount(action))
        }
    }
}

/**
 * Wraps an [IntentSource] and keeps a copy of whatever it produced, for a recorder to read.
 *
 * A decorator and not a replacement, so the thing being recorded is the *real* source - a
 * keyboard, an agent's injected intent, a scripted pilot - sampled by the real
 * `IntentSampleSystem` at `SimPhase.Intent`, once per tick by construction. A recorder that
 * sampled the device itself would be back where `ControllerSystem` was: reading input at frame
 * rate, so the number of samples in a second of game time depended on the machine.
 */
public class RecordingIntentSource(
    private val delegate: IntentSource,
    /** The vocabulary the copy is written in. */
    public val schema: InputSchema = MobaReplay.SCHEMA,
) : IntentSource {

    /** The last sampled tick's input, overwritten in place. Handed straight to the recorder. */
    public val sample: InputSample = InputSample(schema)

    /** How many ticks have been sampled through this source. */
    public var sampleCount: Long = 0L
        private set

    override fun sample(into: Intent) {
        delegate.sample(into)
        MobaReplay.capture(into, sample)
        sampleCount++
    }

    override fun toString(): String = "RecordingIntentSource($delegate, $sampleCount sample(s))"
}

/**
 * An [IntentSource] fed by a recording rather than by a device.
 *
 * The simulation cannot tell this from a keyboard, which is the whole of `IntentSource`'s
 * design intent and the reason a replay is the same simulation rather than a special mode of it.
 */
public class ReplayIntentSource(
    /** The vocabulary the samples are written in. */
    public val schema: InputSchema = MobaReplay.SCHEMA,
) : IntentSource {

    /** The sample the next tick will read. Overwritten by the replay before every step. */
    public val pending: InputSample = InputSample(schema)

    override fun sample(into: Intent) {
        MobaReplay.apply(pending, into)
    }

    override fun toString(): String = "ReplayIntentSource($pending)"
}

/**
 * A headless `moba` driven by a recording: the [ReplayWorld] `udea-replay` steps.
 *
 * ## The hash is over a snapshot, not a field store
 *
 * [hash] uses `WorldHasher.hash(WorldSnapshot)`, which folds the RNG state and the id allocator
 * as well as the fields. The field-store overload would pass on two runs that reached the same
 * world having drawn a different number of random values, and the divergence would then surface
 * however many ticks later that difference finally moved something visible - which for a bisect
 * is the difference between landing on the cause and landing on a consequence.
 *
 * ## Its own `SnapshotService`, not the host's ring
 *
 * The ring captures on a cadence and pools its slots; this needs a capture on **every** tick
 * into a buffer nothing else will overwrite. Building a second service over [MobaReplay.REGISTRY],
 * this world and this `NetIdIndex` costs one reusable snapshot and reads exactly the same world -
 * and it must be that registry *object*, not an equal one; see [MobaReplay.REGISTRY].
 */
public class MobaReplayWorld(
    /** The headless game being driven. */
    public val host: GameHost,
) : ReplayWorld {

    private val source = ReplayIntentSource()
    private val service = MobaReplay.snapshots(host)
    private val buffer: WorldSnapshot = service.newSnapshot()

    init {
        host.ctx[IntentState.KEY].source = source
    }

    override val tick: Tick get() = host.tick

    override fun applyInput(samples: Array<InputSample>) {
        require(samples.isNotEmpty()) { "a moba replay needs at least the local peer's sample" }
        source.pending.copyFrom(samples[MobaReplay.LOCAL_PEER.value])
    }

    override fun step() {
        host.run(1)
    }

    override fun hash(): Long {
        service.captureInto(buffer)
        return WorldHasher.hash(buffer)
    }

    /**
     * The world as a snapshot.
     *
     * A **fresh** one rather than [buffer], because a divergence report holds two snapshots at
     * once and handing back the buffer would have the caller diffing a snapshot against itself.
     * Off the per-tick path by construction: this is only called when a divergence has already
     * been found.
     */
    override fun snapshot(): WorldSnapshot = service.capture()

    override fun toString(): String = "MobaReplayWorld(at $tick)"
}

/**
 * A verified replay of [recording] against a fresh headless `moba`.
 *
 * The one-call form of "does this recording still reproduce", for a test, a CI gate or a tool.
 */
public fun replayMoba(
    recording: ReplayRecording,
    identity: BuildIdentity? = null,
): dev.wildware.udea.replay.ReplayVerification =
    dev.wildware.udea.replay.ReplayVerifier.verify(
        recording = recording,
        factory = MobaReplay.worlds(),
        identity = identity,
    )
