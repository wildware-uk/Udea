package dev.wildware.udea.replay

import dev.wildware.udea.core.Tick

/**
 * The identity a recording was made under, and the identity a build must have to replay it.
 *
 * Everything in [BuildIdentity] is a value that, if it differs between the recording run and the
 * replay run, makes the replay **structurally incapable** of reproducing the recording:
 *
 * - **[rootSeed]** seeds every `RngStream`. A different seed is a different fight from tick one.
 * - **[protoHash]** is the u16 over `net-protocol.lock`: the component ids, the field order and
 *   the quantisation of every replicated component. A build that renumbered a component applies
 *   the same recorded bytes to different fields.
 * - **[assetGraphHash]** is `AssetRegistry.contentHash`, the 32 bytes over the compiled asset
 *   graph. Blueprints, ability data, unit stats and the level layout are all assets, so a
 *   recording made before a balance edit describes units that no longer exist.
 * - **[inputSchemaHash]** is [InputSchema.hash]. `InputCatalog` numbers actions by sorted name
 *   across the whole game, so binding one new key shifts every id after it, and a replay would
 *   press `attack_2` where the player pressed `attack`.
 *
 * ## Refused by name, which is the whole of issue #147's second half
 *
 * [mismatchesAgainst] returns the fields that differ rather than a boolean, so a refusal reads
 * `assetGraphHash: recorded 3f9a..., this build ba21...` and not `cannot replay`. A replay that
 * *cannot* be valid is refused; a replay that can be is never blocked by a field this does not
 * name. The distinction matters because the alternative - replaying anyway and reporting the
 * divergence - produces a first-differing-tick report pointing at a fight that was never the
 * same fight, and an agent bisecting it would be bisecting the wrong thing.
 */
public data class BuildIdentity(
    /** `RngService.seed` at record time. */
    public val rootSeed: Long,
    /** The build's wire-protocol hash, from `net-protocol.lock`. */
    public val protoHash: Int,
    /** `AssetRegistry.contentHash`. Copied on the way in and out; it is a mutable array. */
    public val assetGraphHash: ByteArray,
    /** [InputSchema.hash] of the schema the frames are written in. */
    public val inputSchemaHash: Long,
) {

    init {
        require(assetGraphHash.size <= MAX_ASSET_HASH_BYTES) {
            "an asset graph hash is at most $MAX_ASSET_HASH_BYTES bytes, got " +
                "${assetGraphHash.size}"
        }
    }

    /**
     * Every field of [other] that differs from this one, most-fundamental first.
     *
     * Order is not cosmetic: a seed mismatch explains an asset mismatch (a different match was
     * recorded) but not the other way round, and a reader acting on the first line should be
     * acting on the cause. Empty means this recording can be replayed by that build.
     */
    public fun mismatchesAgainst(other: BuildIdentity): List<IdentityMismatch> = buildList {
        if (rootSeed != other.rootSeed) {
            add(IdentityMismatch("rootSeed", rootSeed.toString(), other.rootSeed.toString()))
        }
        if (!assetGraphHash.contentEquals(other.assetGraphHash)) {
            add(
                IdentityMismatch(
                    "assetGraphHash",
                    hex(assetGraphHash),
                    hex(other.assetGraphHash),
                ),
            )
        }
        if (protoHash != other.protoHash) {
            add(IdentityMismatch("protoHash", hexInt(protoHash), hexInt(other.protoHash)))
        }
        if (inputSchemaHash != other.inputSchemaHash) {
            add(
                IdentityMismatch(
                    "inputSchemaHash",
                    inputSchemaHash.toString(),
                    other.inputSchemaHash.toString(),
                ),
            )
        }
    }

    /** Hand-written because [assetGraphHash] is an array, whose `equals` is its address. */
    override fun equals(other: Any?): Boolean =
        other is BuildIdentity &&
            other.rootSeed == rootSeed &&
            other.protoHash == protoHash &&
            other.inputSchemaHash == inputSchemaHash &&
            other.assetGraphHash.contentEquals(assetGraphHash)

    override fun hashCode(): Int {
        var result = rootSeed.hashCode()
        result = 31 * result + protoHash
        result = 31 * result + inputSchemaHash.hashCode()
        result = 31 * result + assetGraphHash.contentHashCode()
        return result
    }

    override fun toString(): String =
        "BuildIdentity(seed=$rootSeed, proto=${hexInt(protoHash)}, " +
            "assets=${hex(assetGraphHash)}, inputSchema=$inputSchemaHash)"

    public companion object {

        /** A SHA-256 asset hash is 32 bytes; the cap is generous rather than tight. */
        public const val MAX_ASSET_HASH_BYTES: Int = 255

        /** The first eight bytes, which is what a human compares. */
        internal fun hex(bytes: ByteArray): String {
            if (bytes.isEmpty()) return "<none>"
            val shown = bytes.take(8).joinToString("") { "%02x".format(it) }
            return if (bytes.size > 8) "$shown... (${bytes.size} bytes)" else shown
        }

        internal fun hexInt(value: Int): String = "0x%04x".format(value)
    }
}

/** One field of [BuildIdentity] two builds disagree about. */
public data class IdentityMismatch(
    /** The field's name, spelled as the property is. */
    public val field: String,
    /** What the recording carries. */
    public val recorded: String,
    /** What this build has. */
    public val actual: String,
) {
    override fun toString(): String = "$field: recorded $recorded, this build $actual"
}

/**
 * A `.udearep`'s header: who made it, of what, and how much of it there is.
 *
 * [identity] is what a replay is refused over. Everything else is description: how long the
 * recording is, what the peers are, what game wrote it. The split is deliberate - a recording
 * from a different game **version** replays fine if the four identity fields match, and one from
 * the same version does not if they do not.
 */
public data class ReplayHeader(
    /** What a replaying build must match. */
    public val identity: BuildIdentity,
    /** The schema [frames][ReplayRecording.sample] are written in. */
    public val schema: InputSchema,
    /** The simulation's fixed rate. 60 everywhere in this tree; recorded so a reader need not assume. */
    public val tickRateHz: Int,
    /** The tick the first frame belongs to. Rarely zero: a recording starts after the scene loads. */
    public val firstTick: Tick,
    /** How many ticks of frames and hashes follow. */
    public val tickCount: Int,
    /** How many peers each tick carries a sample for. At least one, even for an AI-only match. */
    public val peerCount: Int,
    /** The game that wrote it, for a human reading `replay.info`. Never checked. */
    public val gameId: String,
    /** That game's version string. Never checked; see the class KDoc for why not. */
    public val gameVersion: String,
) {

    init {
        require(tickRateHz > 0) { "a tick rate is positive, was $tickRateHz" }
        require(tickCount >= 0) { "a tick count is not negative, was $tickCount" }
        require(tickCount <= ReplayFormat.MAX_TICKS) {
            "a recording may carry at most ${ReplayFormat.MAX_TICKS} ticks, got $tickCount"
        }
        require(peerCount in 1..ReplayFormat.MAX_PEERS) {
            "a recording carries 1..${ReplayFormat.MAX_PEERS} peers, got $peerCount"
        }
        require(identity.inputSchemaHash == schema.hash) {
            "the header's inputSchemaHash (${identity.inputSchemaHash}) is not the hash of the " +
                "schema it carries (${schema.hash}); one of the two was built from the other's " +
                "predecessor"
        }
    }

    /** The tick one past the last recorded one. */
    public val endTick: Tick get() = firstTick + tickCount.toLong()

    /** True when [tick] has a frame and a hash in this recording. */
    public operator fun contains(tick: Tick): Boolean =
        tick.value >= firstTick.value && tick.value < endTick.value

    /** [tick]'s index into the frame and hash tables, or `-1` when it is outside the recording. */
    public fun indexOf(tick: Tick): Int =
        if (tick in this) (tick.value - firstTick.value).toInt() else -1

    /** How long the recording is, in simulated seconds. For a human, never for the simulation. */
    public val durationSeconds: Double get() = tickCount.toDouble() / tickRateHz

    override fun toString(): String =
        "ReplayHeader($gameId $gameVersion, $tickCount ticks from $firstTick, " +
            "$peerCount peer(s), $identity)"
}

/**
 * This recording is well-formed and **this build cannot replay it**.
 *
 * Carries the fields rather than only a message so a tool can publish them as JSON, which is
 * what `replay.load` does: an agent reading `mismatches[0].field == "assetGraphHash"` knows to
 * rebuild assets without parsing English.
 */
public class ReplayRefusedException(
    /** Every identity field that differs, most-fundamental first. */
    public val mismatches: List<IdentityMismatch>,
    /** The recording's own header, for a caller that wants to report what it was. */
    public val header: ReplayHeader,
) : IllegalStateException(
    "this recording cannot be replayed by this build; " +
        "${mismatches.size} identity field(s) differ:\n  " +
        mismatches.joinToString("\n  "),
)
