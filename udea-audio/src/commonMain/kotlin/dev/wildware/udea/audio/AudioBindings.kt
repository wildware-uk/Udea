package dev.wildware.udea.audio

import dev.wildware.udea.assets.SoundCue
import dev.wildware.udea.core.CueId

/**
 * What one [CueId] sounds like: the loaded files it may pick from, and how to vary them.
 *
 * The `IntArray` holds [SoundHandle.slot]s rather than handles, because this array is indexed on
 * the per-frame path and a `SoundHandle[]` would be an array of boxed value classes.
 */
public class CueSound(
    /** The cue this plays for. */
    public val cue: CueId,
    /** Human name, for a failure message and for a debug readout. Never matched on. */
    public val name: String,
    /** Device slots, one per authored file. Never empty. */
    private val slots: IntArray,
    /** Linear gain at the listener's own position, from `SoundCue.volume`. */
    public val volume: Float,
    /** Fraction either side of unit pitch, from `SoundCue.pitchVariance`. */
    public val pitchVariance: Float,
) {

    init {
        require(slots.isNotEmpty()) { "cue sound '$name' names no loaded files" }
        require(volume >= 0F && volume.isFinite()) { "cue sound '$name' has volume $volume" }
        require(pitchVariance >= 0F && pitchVariance < 1F) {
            "cue sound '$name' has pitchVariance $pitchVariance; it is a fraction either side of " +
                "unit pitch, so 1 or more would allow a pitch of zero or below"
        }
    }

    /** How many files this cue may pick from. */
    public val size: Int get() = slots.size

    /** The [index]th file's device handle. */
    public fun handleAt(index: Int): SoundHandle = SoundHandle(slots[index])

    override fun toString(): String = "CueSound($name, ${slots.size} file(s))"

    public companion object {

        /**
         * Loads every file [cue] names through [device] and binds the result to [id].
         *
         * The authored `volume` and `pitchVariance` come straight off the asset, which is the
         * whole reason `SoundCue` carries them: the old `sounds.udea.kts` said `volume = 0.2F` for
         * a swoosh and `0.5F` for a hit, and that judgement belongs to whoever mixed the pack
         * rather than to a constant in a renderer.
         */
        public fun load(id: CueId, cue: SoundCue, device: AudioDevice): CueSound = CueSound(
            cue = id,
            name = cue.id.value,
            slots = IntArray(cue.sounds.size) { device.load(cue.sounds[it].value).slot },
            volume = cue.volume,
            pitchVariance = cue.pitchVariance,
        )
    }
}

/**
 * Every [CueSound] this game has, in a table `CueAudio` can index without allocating.
 *
 * ## Why a dense array and not a map
 *
 * This is read once per drained cue, and a busy tick in a twenty-seven unit fight drains dozens.
 * `HashMap<CueId, CueSound>` would box the key on every lookup - `CueId` is a value class, so it
 * only stays an `Int` while nothing asks for it as an `Any`. Standards section 1: identity
 * resolution on a per-tick path is indexed.
 *
 * A cue id outside the table is not an error. Most cues a simulation emits have no sound, and
 * `CueAudio` counts them as [CueAudio.unbound] rather than refusing them - a game that could not
 * emit a cue until somebody had recorded audio for it would be a game whose cue vocabulary was
 * decided by its sound designer.
 */
public class AudioBindings private constructor(private val table: Array<CueSound?>) {

    /** How many cues have a sound bound to them. */
    public val size: Int = table.count { it != null }

    /** The highest cue id this table can answer for. */
    public val highestCueId: Int get() = table.size - 1

    /** What [cue] sounds like, or `null` when nothing is bound to it. */
    public operator fun get(cue: CueId): CueSound? =
        if (cue.raw in table.indices) table[cue.raw] else null

    /** Every bound cue, ascending by id. For a test and for a start-up log line. */
    public fun bound(): List<CueSound> = table.filterNotNull()

    override fun toString(): String = "AudioBindings($size bound, ids 0..$highestCueId)"

    public companion object {

        /** An empty table. Every cue is unbound; nothing plays. */
        public val EMPTY: AudioBindings = AudioBindings(arrayOfNulls(1))

        /**
         * The largest cue id a table may hold.
         *
         * Not a buffer size somebody guessed at (standards section 1): the table is one reference
         * per id from zero to the highest bound cue, so the ceiling exists only to turn a
         * `CueId(Int.MAX_VALUE)` typo into a message instead of an eight-gigabyte allocation. A
         * game with more than this many *distinct sounded cues* wants a different structure, and
         * would rather be told so than discover it in a heap dump.
         */
        public const val MAX_CUE_ID: Int = 1023

        /**
         * Builds the table from [sounds].
         *
         * @throws IllegalArgumentException on a duplicate cue id. Last-writer-wins here would mean
         *   the sound a cue makes depended on the order a binding list happened to be written in,
         *   which is the class of bug `AssetGraph.of` reports for asset ids and for the same
         *   reason.
         */
        public fun of(sounds: List<CueSound>): AudioBindings {
            if (sounds.isEmpty()) return EMPTY
            val highest = sounds.maxOf { it.cue.raw }
            require(highest <= MAX_CUE_ID) {
                "cue sound '${sounds.first { it.cue.raw == highest }.name}' is bound to " +
                    "CueId($highest), above the $MAX_CUE_ID ceiling AudioBindings holds a dense " +
                    "table up to"
            }
            require(sounds.none { it.cue.raw < 0 }) {
                "a CueId is a table index here, so ${sounds.first { it.cue.raw < 0 }.name} " +
                    "cannot be bound to ${sounds.first { it.cue.raw < 0 }.cue}"
            }
            val table = arrayOfNulls<CueSound>(highest + 1)
            sounds.forEach { sound ->
                val existing = table[sound.cue.raw]
                require(existing == null) {
                    "${sound.cue} is bound to both '${existing?.name}' and '${sound.name}'; a cue " +
                        "makes one sound, and which one it made would otherwise depend on list order"
                }
                table[sound.cue.raw] = sound
            }
            return AudioBindings(table)
        }
    }
}
