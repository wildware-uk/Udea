package dev.wildware.udea.audio

import dev.wildware.udea.core.Cue
import dev.wildware.udea.core.CueQueue
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The thing that was missing: something that actually drains `GameContext.cues`.
 *
 * ## The bug this closes
 *
 * `CueQueue` is bounded at [CueQueue.DEFAULT_CAPACITY] and drops the *newest* emit once it is
 * full, counting the loss in `droppedCount`. That design is correct and it assumed a drainer.
 * There was none. Every `melee_hit`, `swoosh`, `arrow_fired`, `heal`, `knockback`, `spin` and
 * `death` this game emits went into a 1024-slot list that filled inside the first second of the
 * fight and then discarded everything for the rest of the match - so the cue mechanism was,
 * measurably, dead code: `MobaCues`' own KDoc said "nothing draws or plays these yet" and nothing
 * emptied them either. A drain that plays nothing is still the fix for half of that, which is why
 * [AudioDevice.Silent] is a first-class arrangement here and not a stub.
 *
 * ## Where it sits
 *
 * Not a Fleks system (spec 3.3): presentation lives behind `Presentation` or a frame callback, so
 * `world.update(dt)` stays pure simulation by construction. [drain] is called once per frame,
 * between ticks, on the thread that drives frames - the same place `RenderPipeline.render` is
 * called from, and under the same threading rules `CueQueue` states for itself.
 *
 * ## Allocation
 *
 * Nothing on [drain]'s path allocates. The consumer lambda is hoisted for the reason
 * `GasCueForwardSystem` hoists its own forwarder - `drain { ... }` capturing a local would build a
 * closure per frame - [position] is one array owned for the mixer's life, and the voice ledger is
 * stamped rather than cleared. The cue objects themselves are allocated by the *simulation* when
 * it emits them, which is upstream of this module and stated here rather than hidden: `Cue` is a
 * `data class`, so one instance exists per emitted cue whatever presentation does with it.
 *
 * ## Randomness
 *
 * Wall-seeded, and deliberately so. Which of five swoosh recordings plays is a presentation
 * decision that must never enter a snapshot or change a rewind, and seeding it from `RngService`
 * would couple it to the simulation stream: draw one fewer sound and every subsequent combat roll
 * shifts. Spec 5 puts a separately typed, wall-seeded generator in a module simulation cannot see,
 * and `udea-core` cannot see this one.
 */
public class CueAudio(
    /** Where the noise comes out. [AudioDevice.Silent] for a headless process. */
    private val device: AudioDevice,
    /** What each cue sounds like. [AudioBindings.EMPTY] still drains; it just plays nothing. */
    private val bindings: AudioBindings,
    /** The ear. Moved by whoever owns the camera, once a frame. */
    public val listener: AudioListener = AudioListener(),
    /** How a cue's source becomes a position. */
    private val locator: CueSourceLocator = CueSourceLocator.Unlocated,
    /** At most this many voices per cue id per drain. See [DEFAULT_VOICE_CAP]. */
    private val voiceCap: Int = DEFAULT_VOICE_CAP,
    seed: Long = System.nanoTime(),
) {

    init {
        require(voiceCap > 0) { "voiceCap must be positive, was $voiceCap" }
    }

    /** Cues taken off the queue, whether or not they made a sound. */
    public var drained: Long = 0L
        private set

    /** Cues handed to the device. */
    public var played: Long = 0L
        private set

    /** Cues drained with no [CueSound] bound to their id. The ordinary case, not a failure. */
    public var unbound: Long = 0L
        private set

    /** Cues bound to a sound too far away to hear, or past [voiceCap] for this drain. */
    public var suppressed: Long = 0L
        private set

    private val random = Random(seed)

    /** Reused by every locate call. One array for the mixer's life. */
    private val position = FloatArray(2)

    /**
     * Voices already spent on each cue id, stamped with the drain they were spent in.
     *
     * Stamping rather than a fill per frame: the table is as long as the binding table, and a
     * clear costs a pass over it every frame whether or not anything was played. The stamp makes
     * the reset free at the cost of one comparison per lookup.
     */
    private val voiceStamp = IntArray(bindings.highestCueId + 1)
    private val voiceCount = IntArray(bindings.highestCueId + 1)
    private var drainStamp: Int = 0

    /** Hoisted, so [drain] does not build a closure per frame. Captures `this` and nothing else. */
    private val consume: (Cue) -> Unit = { cue -> playCue(cue) }

    /**
     * Empties [queue], playing what it can, and returns how many cues it took.
     *
     * Called once a frame. Calling it every *tick* would be wrong in the other direction: a
     * fast-forward runs many ticks between frames and the cues of all of them belong to the one
     * frame that follows, which is what makes the queue's capacity a backlog allowance rather than
     * a per-tick budget.
     */
    public fun drain(queue: CueQueue): Int {
        drainStamp++
        val count = queue.drain(consume)
        drained += count.toLong()
        return count
    }

    private fun playCue(cue: Cue) {
        val sound = bindings[cue.id]
        if (sound == null) {
            unbound++
            return
        }
        if (!claimVoice(cue.id.raw)) {
            suppressed++
            return
        }
        val dx: Float
        val dy: Float
        if (locator.locate(cue.source, position)) {
            dx = position[0] - listener.x
            dy = position[1] - listener.y
        } else {
            // A world-level cue, or a source that has already been removed. It plays at the ear:
            // centred and unattenuated. Skipping it instead would silence every death, because the
            // system that emits that one is the system that deletes the entity.
            dx = 0F
            dy = 0F
        }
        val volume = sound.volume * listener.attenuationAt(sqrt(dx * dx + dy * dy))
        if (volume <= SILENCE_FLOOR) {
            suppressed++
            return
        }
        device.play(
            sound.handleAt(if (sound.size == 1) 0 else random.nextInt(sound.size)),
            volume.coerceAtMost(1F),
            pitchFor(sound),
            listener.panAt(dx),
        )
        played++
    }

    /**
     * A pitch within `pitchVariance` either side of unit pitch, clamped to [MIN_PITCH]..[MAX_PITCH].
     *
     * `SoundCue.pitchVariance`'s own KDoc defines it as "a fraction either side of unit pitch, so
     * `0.1` means 0.9x to 1.1x", and that is what this implements. The old `SoundSystem` computed
     * `pitch + (random * variance - variance / 2)`, which is half the range the asset asked for -
     * which is why every authored `pitchVariance` in the old tree was `0.5F`. The values are
     * carried across unchanged and now mean what they say.
     */
    private fun pitchFor(sound: CueSound): Float {
        if (sound.pitchVariance <= 0F) return 1F
        val offset = (random.nextFloat() * 2F - 1F) * sound.pitchVariance
        return (1F + offset).coerceIn(MIN_PITCH, MAX_PITCH)
    }

    /**
     * Takes one of this drain's voices for [cueId], or reports there are none left.
     *
     * Twenty-seven units swinging on one tick emit twenty-seven melee hits, and twenty-seven copies
     * of one 200ms recording started in the same millisecond is not twenty-seven hits - it is one
     * loud click, and it costs the device twenty-seven voices to make it. The old game had no such
     * cap and this is the first place the tree can state one.
     */
    private fun claimVoice(cueId: Int): Boolean {
        if (cueId !in voiceStamp.indices) return true
        if (voiceStamp[cueId] != drainStamp) {
            voiceStamp[cueId] = drainStamp
            voiceCount[cueId] = 0
        }
        if (voiceCount[cueId] >= voiceCap) return false
        voiceCount[cueId]++
        return true
    }

    override fun toString(): String =
        "CueAudio(device=$device, bindings=$bindings, drained=$drained, played=$played)"

    public companion object {

        /**
         * Voices per cue id per frame.
         *
         * Three, because the ear stops counting simultaneous copies of one short sample at about
         * that many and every one past it is spent voice budget. It is per *cue id*, so three hits
         * and three swooshes and three deaths in one frame are nine sounds, not three.
         */
        public const val DEFAULT_VOICE_CAP: Int = 3

        /**
         * Gain below which a sound is not started at all.
         *
         * Not an epsilon: at 16-bit output anything under this rounds to a handful of least
         * significant bits, and starting a voice to play inaudible samples costs the device the
         * same as an audible one.
         */
        public const val SILENCE_FLOOR: Float = 1F / 512F

        /** Half speed. Below this a hit sounds like a different event. */
        public const val MIN_PITCH: Float = 0.5F

        /** Double speed, the ceiling every gdx backend documents for its own sound playback. */
        public const val MAX_PITCH: Float = 2.0F
    }
}
