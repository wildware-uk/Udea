package dev.wildware.moba.audio

import com.github.quillraven.fleks.World
import dev.wildware.moba.Position
import dev.wildware.moba.ability.MobaScale
import dev.wildware.udea.audio.AudioDevice
import dev.wildware.udea.audio.AudioListener
import dev.wildware.udea.audio.CueAudio
import dev.wildware.udea.audio.CueSourceLocator
import dev.wildware.udea.core.CueQueue
import dev.wildware.udea.core.CueSink
import dev.wildware.udea.core.innermost
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.CoreModule

/**
 * `moba`'s audio, assembled: a device, a routing table, an ear, and the thing that empties the
 * cue queue.
 *
 * ## The defect this closes
 *
 * Nothing drained `GameContext.cues`. `CueQueue` holds 1024 undrained cues and then discards
 * every further emit, counting them in `droppedCount`, so a fight that emits eight cues a tick
 * saturated it in about two seconds of simulated time and threw the rest of the match away. That
 * is not a lost sound - there was no sound - it is the cue mechanism being *inert*, and it made
 * every `cues.emit` in the game a write to a bin.
 *
 * [frame] is the drain, and it is deliberately not a Fleks system (spec 3.3): presentation runs
 * between ticks, off the frame callback, so `world.update` stays pure simulation by construction.
 *
 * ## Headless
 *
 * [silent] builds the same object over [AudioDevice.Silent]. It loads no files, opens no device,
 * plays nothing and allocates nothing per frame - and it still drains, which is the whole point:
 * a headless server, a CI run and an agent session all keep the queue bounded rather than relying
 * on nobody noticing. [forHost] picks it for [RenderMode.Headless] itself, so a caller cannot get
 * that wrong by forgetting a branch.
 *
 * ## What is honestly not wired
 *
 * The ear follows an *entity* ([listenTo]), not the camera. `CameraRig`'s position is the render
 * thread's and is not exposed for reading; the client's camera follows the player unit, so
 * following that unit puts the ear where the camera is without reaching across the seam. A free
 * camera - which `moba` does not have - would drift away from the ear.
 */
public class MobaAudio private constructor(
    private val device: AudioDevice,
    private val queue: CueQueue,
    private val world: World,
    private val netIds: NetIdIndex,
    /** The mixer. Its counters are what a test reads. */
    public val audio: CueAudio,
    /** The routing table, and the list of cues it had to leave silent. */
    public val sounds: MobaCueSounds,
) {

    /** The entity the ear rides, or [NetId.NONE] for an ear parked at the origin. */
    public var ear: NetId = NetId.NONE
        private set

    /** Puts the ear on [netId] from the next [frame] on. */
    public fun listenTo(netId: NetId) {
        ear = netId
    }

    /** The listener, for a caller that wants to change the falloff or park the ear by hand. */
    public val listener: AudioListener get() = audio.listener

    /**
     * Moves the ear and empties the cue queue. Called once per frame.
     *
     * @return how many cues were drained, so a caller can assert the queue is being served.
     */
    public fun frame(): Int {
        moveEar()
        return audio.drain(queue)
    }

    /** Releases the device. After this the object is spent. */
    public fun close() {
        device.close()
    }

    private fun moveEar() {
        val entity = netIds.resolveOrNull(ear) ?: return
        val position = with(world) { entity.getOrNull(Position) } ?: return
        audio.listener.moveTo(position.x, position.y)
    }

    override fun toString(): String = "MobaAudio(device=$device, ear=$ear, $sounds)"

    /**
     * Resolves a cue's source through the world's own net id index.
     *
     * A cue carries a `NetId` and nothing else - widening `Cue` with a position so the mixer could
     * skip this would put a presentation concern into a kernel type - so the lookup happens here,
     * against the index the rest of the game already resolves ids through. It is O(1) by
     * construction; `NetIdIndex` is the type standards section 1's "identity resolution is O(1)"
     * rule exists for.
     *
     * A `false` is the ordinary case and not a failure: `CharacterAnimationSystem` emits its
     * notify cues with `NetId.NONE` (its own KDoc calls that stubbed, because `MobaModule` does
     * not publish the index on the context), and `DEATH` names an entity the emitting system has
     * just removed. Both play at the ear rather than being dropped.
     */
    private class WorldLocator(
        private val world: World,
        private val netIds: NetIdIndex,
    ) : CueSourceLocator {

        override fun locate(source: NetId, out: FloatArray): Boolean {
            val entity = netIds.resolveOrNull(source) ?: return false
            val position = with(world) { entity.getOrNull(Position) } ?: return false
            out[0] = position.x
            out[1] = position.y
            return true
        }
    }

    public companion object {

        /**
         * How far a sound carries, in world units.
         *
         * Twenty character heights. `MobaScale.WORLD` is "world units per corpus unit" and an orc
         * is about one corpus unit tall, so this is the one number in the audio path that has to
         * be written in the game's own scale - `AudioListener.DEFAULT_FALLOFF` is ten *world*
         * units, which in this game is a quarter of a character and would silence everything.
         * That mismatch is the same class of bug `MobaIntegrationTest` was written for: two halves
         * each self-consistent in scales forty times apart.
         */
        public const val FALLOFF: Float = 20F * MobaScale.WORLD

        /** Full stereo pan six character widths off centre. */
        public const val PAN_WIDTH: Float = 6F * MobaScale.WORLD

        /**
         * Audio for [host], silent in [RenderMode.Headless] and real in either GL mode.
         *
         * The branch is here rather than at every call site for the reason `GameHost` refuses
         * `run()` in a GL mode: the mode decides it, and a caller that had to remember would
         * eventually construct a [GdxAudioDevice] in a process with no `Gdx.audio` and get a
         * `NullPointerException` a long way from the decision.
         */
        public fun forHost(host: GameHost): MobaAudio = of(
            host,
            if (host.mode == RenderMode.Headless) AudioDevice.Silent else GdxAudioDevice(),
        )

        /** Audio for [host] that drains the queue and plays nothing. What CI and an agent run. */
        public fun silent(host: GameHost): MobaAudio = of(host, AudioDevice.Silent)

        /**
         * Builds over an explicit [device].
         *
         * The context's sink may be **decorated**: a debug build wraps it so an observer can see
         * cues on the way past, and such a wrapper is a `CueSink` and not a `CueQueue`. So the
         * chain is walked with `innermost()` rather than cast once. That is not a softening of the
         * refusal below - a chain whose innermost sink is still not a queue is refused exactly as
         * before, because there is genuinely nothing to drain and silently doing nothing is the
         * failure this whole class exists to delete.
         *
         * @throws IllegalStateException when the innermost `CueSink` is not a `CueQueue`.
         */
        public fun of(host: GameHost, device: AudioDevice): MobaAudio {
            val sink: CueSink = host.ctx.cues.innermost()
            val queue = checkNotNull(sink as? CueQueue) {
                "GameContext.cues is a ${sink::class.simpleName}, not a CueQueue, so MobaAudio " +
                    "has nothing to drain. CoreModule is what puts a CueQueue there."
            }
            val netIds = host.ctx[CoreModule.NET_IDS]
            val sounds = MobaCueSounds.load(device)
            return MobaAudio(
                device = device,
                queue = queue,
                world = host.world,
                netIds = netIds,
                audio = CueAudio(
                    device = device,
                    bindings = sounds.bindings,
                    listener = AudioListener(falloff = FALLOFF, panWidth = PAN_WIDTH),
                    locator = WorldLocator(host.world, netIds),
                ),
                sounds = sounds,
            )
        }
    }
}
