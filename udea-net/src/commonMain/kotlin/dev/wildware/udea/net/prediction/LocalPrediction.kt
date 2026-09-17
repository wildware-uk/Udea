package dev.wildware.udea.net.prediction

import dev.wildware.udea.core.Tick
import dev.wildware.udea.net.input.JitterBuffer
import dev.wildware.udea.net.input.MoveInput
import dev.wildware.udea.net.wire.PacketHeader
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How a correction is absorbed. The difference between "the server disagreed" and "the player
 * saw the character get yanked".
 *
 * A correction is never applied to the *drawn* position outright. The drawn position is the
 * reconciled one plus a residual offset that starts at the whole disagreement and decays
 * geometrically, so a 2-unit correction arrives as roughly 0.4, 0.32, 0.26 ... rather than as a
 * jump. That is the entire difference between a MOBA that feels connected and one players
 * describe as rubber-banding, and it costs two floats.
 */
public data class PredictionSmoothing(

    /**
     * Fraction of the residual error carried into the next tick.
     *
     * `0.8` puts 95% of any correction behind the player inside 13 ticks - a fifth of a second
     * at 60Hz - which is fast enough that the character is not visibly lagging its own input and
     * slow enough that no single frame moves it more than a fifth of the error.
     */
    public val decay: Float = DEFAULT_DECAY,

    /**
     * A disagreement at or below this is not a correction at all.
     *
     * Quantisation alone makes the client's float and the server's float differ in the last
     * places, and counting those as corrections would report a healthy session as thousands of
     * them. This is the floor beneath which the two are the same position.
     */
    public val tolerance: Float = DEFAULT_TOLERANCE,

    /**
     * Past this the server did not correct the character, it **moved** it.
     *
     * A respawn, a blink, a scene load. Smoothing one of those slides the sprite across the map
     * over a fifth of a second, which is worse than the snap: the character is visibly not where
     * the game says it is for that whole time, and anything aimed at it misses. So beyond this
     * distance the offset is dropped and the correction is taken whole.
     */
    public val snapDistance: Float = DEFAULT_SNAP_DISTANCE,

    /** Residual below this is zeroed rather than decayed towards zero forever. */
    public val settle: Float = DEFAULT_SETTLE,
) {

    init {
        require(decay in 0f..1f) { "decay is a fraction of the residual kept, was $decay" }
        require(tolerance >= 0f) { "tolerance must not be negative, was $tolerance" }
        require(snapDistance > tolerance) {
            "snapDistance $snapDistance must exceed tolerance $tolerance, or every correction snaps"
        }
        require(settle >= 0f) { "settle must not be negative, was $settle" }
    }

    public companion object {

        /** @see PredictionSmoothing.decay */
        public const val DEFAULT_DECAY: Float = 0.8f

        /** A thousandth of a world unit: below a pixel at any sane camera zoom. */
        public const val DEFAULT_TOLERANCE: Float = 1e-3f

        /**
         * Sixty-four world units. `moba`'s soldier walks 0.75 a tick, so no amount of ordinary
         * disagreement reaches this; a respawn across the level does.
         */
        public const val DEFAULT_SNAP_DISTANCE: Float = 64f

        /** @see PredictionSmoothing.settle */
        public const val DEFAULT_SETTLE: Float = 1e-4f
    }
}

/**
 * Client-side prediction with server reconciliation for the one entity this client drives.
 *
 * ## The loop, and why each step is where it is
 *
 * ```
 * predict(command)            // on the tick the input is read: the character moves NOW
 * ... datagram crosses the wire, twice ...
 * reconcile(x, y, ackedSeq)   // rewind to what the server says, replay what it has not seen
 * advance()                   // once per client tick: bleed the residual off
 * ```
 *
 * [predict] is the whole of the responsiveness claim. A client that waited for the server would
 * see its own key press take a full round trip - 150ms each way is eighteen ticks at 60Hz, which
 * is not "slightly laggy", it is a different game. Here the command is applied to the local pose
 * on the tick it is minted, so the champion answers on the same tick, and *also* pushed into a
 * history so the server's later answer can be reconciled against it rather than fought with.
 *
 * [reconcile] is the correctness claim. The server's position at the moment it had consumed
 * command `ackedSeq` is authoritative, full stop - so that value is written in, and every
 * command **newer** than `ackedSeq` is replayed on top through the same [MoveModel] that
 * produced the prediction in the first place. Replay is not an approximation of the server's
 * arithmetic; it is the same function over the same values ([PlanarMoveModel] rounds the axis
 * through the wire codec for exactly this reason), so on a link with no loss the replayed pose
 * is bit-identical to the predicted one and the correction is zero. A non-zero correction under
 * loss is real information: it is the input the server never received.
 *
 * ## What it needs from the wire, and what is missing today
 *
 * `ackedSeq` is *the sequence of the last command the server actually consumed*, which is
 * [dev.wildware.udea.net.input.JitterBuffer.lastProcessedInputSeq]. Nothing puts it in a
 * datagram: [PacketHeader.ack] is a *packet* sequence, and "the server has received your packet
 * 12" is not "the server has simulated your command 40" - the jitter buffer sits between them by
 * design, and its depth varies with the link. Reconciling against the packet ack would replay a
 * jitter-buffer's worth of commands too many, every tick, in a fixed direction: a standing error
 * that the smoothing would then hide rather than remove.
 *
 * So this class takes the value as an argument and the caller must obtain it from the server.
 * See the wave report: the field belongs in `PacketHeader`, beside the ack it is not.
 */
public class LocalPrediction(

    /** The step. Must be the one the server's movement system runs (spec 3.4). */
    private val model: MoveModel,

    /** How a correction is absorbed. */
    public val smoothing: PredictionSmoothing = PredictionSmoothing(),

    /**
     * How many unacknowledged commands to keep.
     *
     * The replay depth is `RTT * tickRate` plus the jitter buffer, so 150ms each way at 60Hz is
     * about twenty. 128 is [dev.wildware.udea.net.input.InputRing.DEFAULT_CAPACITY] - over two
     * seconds - which outlasts any link a game is playable on. Past it the oldest is dropped and
     * [overruns] counts it, because silently forgetting a command the server has not yet
     * simulated makes the prediction permanently short and there is no other symptom.
     */
    public val historyCapacity: Int = DEFAULT_HISTORY_CAPACITY,
) {

    init {
        require(historyCapacity > 0) { "historyCapacity must be positive, was $historyCapacity" }
    }

    private val pending = arrayOfNulls<MoveInput>(historyCapacity)
    private var head = 0
    private var count = 0

    private val pose = PredictedPose()
    private val drawnBefore = PredictedPose()
    private val poseBefore = PredictedPose()

    private var offsetX = 0f
    private var offsetY = 0f

    /** False until [start] has been given an authoritative position to predict from. */
    public var started: Boolean = false
        private set

    /** Commands predicted. Also how many times the champion answered on the input's own tick. */
    public var predicted: Long = 0L
        private set

    /** Reconciliations that moved the pose by more than [PredictionSmoothing.tolerance]. */
    public var corrections: Long = 0L
        private set

    /** Commands re-simulated on top of a correction, over the session. */
    public var replayed: Long = 0L
        private set

    /** Corrections taken whole because they exceeded [PredictionSmoothing.snapDistance]. */
    public var snaps: Long = 0L
        private set

    /** Commands dropped because [historyCapacity] filled. Should be zero on a playable link. */
    public var overruns: Long = 0L
        private set

    /** The most recent correction's magnitude, in world units. */
    public var lastCorrection: Float = 0f
        private set

    /** The largest correction of the session. The number a reviewer should ask for. */
    public var maxCorrection: Float = 0f
        private set

    /** Commands predicted but not yet known to have been simulated by the server. */
    public val pendingCount: Int get() = count

    /** Drawn x: the reconciled pose plus whatever of the last correction is still being absorbed. */
    public val x: Float get() = pose.x + offsetX

    /** Drawn y. @see x */
    public val y: Float get() = pose.y + offsetY

    /** Reconciled x, with no smoothing residual. What the client believes is true. */
    public val settledX: Float get() = pose.x

    /** Reconciled y. @see settledX */
    public val settledY: Float get() = pose.y

    /** How far the drawn position still is from the reconciled one. Zero once settled. */
    public val residual: Float get() = sqrt(offsetX * offsetX + offsetY * offsetY)

    /**
     * Begins predicting from an authoritative position, discarding any history.
     *
     * Called when the client first learns where its champion is, and again after anything that
     * invalidates the history - taking over a different champion, a scene change. Not called per
     * correction: that is [reconcile], which keeps the history precisely because the commands in
     * it have not been simulated by the server yet.
     */
    public fun start(x: Float, y: Float) {
        pose.set(x, y)
        offsetX = 0f
        offsetY = 0f
        head = 0
        count = 0
        started = true
    }

    /**
     * Applies [command] to the local pose immediately and remembers it for replay.
     *
     * @throws IllegalStateException before [start]. Predicting from an unknown position would
     *   produce motion relative to the origin and a correction the size of the map on the first
     *   packet, which is a wiring mistake worth failing loudly on.
     */
    public fun predict(command: MoveInput) {
        check(started) { "predict before start: there is no authoritative position to predict from" }
        if (count == historyCapacity) {
            head = (head + 1) % historyCapacity
            count--
            overruns++
        }
        pending[(head + count) % historyCapacity] = command
        count++
        model.step(pose, command)
        predicted++
    }

    /**
     * Takes the server's answer and replays everything it has not yet seen.
     *
     * @param x authoritative x at the moment the server had consumed [ackedSeq].
     * @param y authoritative y at that same moment.
     * @param ackedSeq the last command sequence the server simulated, or
     *   [dev.wildware.udea.net.input.JitterBuffer.NO_SEQ] when it has simulated none - in which
     *   case nothing is dropped from the history and everything is replayed.
     * @return the correction magnitude in world units.
     */
    public fun reconcile(x: Float, y: Float, ackedSeq: Int): Float {
        check(started) { "reconcile before start" }
        dropAcknowledged(ackedSeq)
        // Two "befores", and conflating them is a real defect rather than a tidiness point.
        //
        //  - the *drawn* position is what must not jump, so the new residual is measured from
        //    there; measuring it from the pose would re-add the part already absorbed.
        //  - the *reconciled* position is what the disagreement is measured from. Measuring the
        //    error from the drawn position instead reports the residual of the previous
        //    correction as a fresh correction on every tick until it has bled off, which turns
        //    one disagreement into a dozen and makes both counters meaningless.
        drawnBefore.set(this.x, this.y)
        poseBefore.set(pose)
        pose.set(x, y)
        var index = 0
        while (index < count) {
            model.step(pose, pending[(head + index) % historyCapacity]!!)
            index++
        }
        replayed += count.toLong()

        val errorX = poseBefore.x - pose.x
        val errorY = poseBefore.y - pose.y
        val error = sqrt(errorX * errorX + errorY * errorY)
        lastCorrection = error
        if (error > maxCorrection) maxCorrection = error
        if (error >= smoothing.snapDistance) {
            // The server moved the champion rather than correcting it. Take it whole: sliding
            // across the map over a fifth of a second is worse than the jump.
            offsetX = 0f
            offsetY = 0f
            snaps++
            corrections++
            return error
        }
        if (error > smoothing.tolerance) corrections++
        // Continuity, whatever the error was: the drawn position stays exactly where it is this
        // tick and walks onto the reconciled one over the following few.
        offsetX = drawnBefore.x - pose.x
        offsetY = drawnBefore.y - pose.y
        if (abs(offsetX) < smoothing.settle) offsetX = 0f
        if (abs(offsetY) < smoothing.settle) offsetY = 0f
        return error
    }

    /** Bleeds one tick's worth of the residual off. Call once per client tick, after [predict]. */
    public fun advance() {
        if (offsetX == 0f && offsetY == 0f) return
        offsetX *= smoothing.decay
        offsetY *= smoothing.decay
        if (abs(offsetX) < smoothing.settle) offsetX = 0f
        if (abs(offsetY) < smoothing.settle) offsetY = 0f
    }

    /**
     * Drops every held command at or older than [ackedSeq].
     *
     * Under [PacketHeader.isNewer] rather than `<=`, because command sequences wrap at 16 bits
     * and a naive comparison discards the entire history once per eighteen minutes of play - a
     * bug that would present as one enormous correction and never be reproduced.
     */
    private fun dropAcknowledged(ackedSeq: Int) {
        if (ackedSeq < 0) return
        while (count > 0 && !PacketHeader.isNewer(pending[head]!!.seq, ackedSeq)) {
            pending[head] = null
            head = (head + 1) % historyCapacity
            count--
        }
    }

    override fun toString(): String =
        "LocalPrediction(pending=$count, corrections=$corrections, max=$maxCorrection)"

    public companion object {

        /** @see LocalPrediction.historyCapacity */
        public const val DEFAULT_HISTORY_CAPACITY: Int = 128
    }
}

/**
 * Where a predicting client learns *which of its commands the server has actually simulated*.
 *
 * ## Why this is an interface instead of a field read off the packet
 *
 * It should be a field read off the packet, and it is not one yet. The value is
 * [dev.wildware.udea.net.input.JitterBuffer.lastProcessedInputSeq] and it belongs in
 * [dev.wildware.udea.net.wire.PacketHeader] next to the ack it is not - see [LocalPrediction]'s
 * KDoc for why the packet ack cannot stand in for it. Until that field exists, a game that wants
 * reconciliation has to supply the value some other way, and an explicit seam is the honest
 * shape for that: a default that quietly guessed would produce a prediction that is wrong in a
 * way nobody could see.
 *
 * The parameter is the **snapshot's** server tick and not "now", because the ack and the position
 * have to be paired. Reconciling a fresh ack against a stale position drops commands the server
 * has not simulated, and the prediction then runs permanently short of the truth.
 */
public fun interface InputAckSource {

    /**
     * The last command sequence the server had simulated as of [serverTick], or
     * [dev.wildware.udea.net.input.JitterBuffer.NO_SEQ] when that is not known.
     */
    public fun ackAt(serverTick: Tick): Int

    public companion object {

        /**
         * Knows nothing, and says so.
         *
         * A client wired to this cannot reconcile - every snapshot would replay the whole
         * history - so [LocalPrediction] must not be run against it. It exists so that "no ack
         * source" is a value rather than a null check at every call site, and so that a session
         * built without one fails by predicting nothing rather than by predicting wrongly.
         */
        public val NONE: InputAckSource = InputAckSource { JitterBuffer.NO_SEQ }
    }
}
