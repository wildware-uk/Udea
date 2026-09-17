package dev.wildware.udea.net.prediction

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.net.harness.Mover
import dev.wildware.udea.net.harness.MoverReplicator
import dev.wildware.udea.net.harness.ReplicationSession
import dev.wildware.udea.net.input.JitterBuffer
import dev.wildware.udea.net.input.MoveInput
import dev.wildware.udea.net.replication.ReplicationClient
import dev.wildware.udea.net.transport.NetConditions
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.wire.PacketHeader
import dev.wildware.udea.net.wire.SnapshotApplySink
import dev.wildware.udea.net.wire.ReplicaStore
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The claim of issue #112, driven through the real replication stack over a 150ms link.
 *
 * Real means real: [ReplicationSession] is the same `ReplicationServer`, `ReplicationClient`,
 * `SnapshotRing`, `JitterBuffer` and simulated link every other networking test runs, with
 * `NetConditions.TRELLO_8` - nine ticks of latency, two of jitter, 5% loss, 2% reorder. What
 * this file adds is a client that predicts its own champion and interpolates everyone else's.
 *
 * ## The one thing that is stubbed, and it is not small
 *
 * Reconciliation needs *the sequence of the last command the server actually simulated*. That is
 * [JitterBuffer.lastProcessedInputSeq] and **nothing puts it on the wire** - see
 * [LocalPrediction]'s KDoc for why the packet ack is not a substitute. [Wire] below is that
 * missing header field, standing in as an in-process table keyed by the snapshot tick it would
 * have ridden on. Every other byte in this test crosses the simulated link.
 *
 * A miss in that table would silently degrade the proof into "reconcile against nothing", so
 * [Client.ackMisses] counts them and the test asserts it is zero.
 */
internal class PredictionProofTest {

    @Test
    fun `the local champion answers on the input's own tick while the server is 150ms away`() {
        val run = Run(seed = SEEDS[0], conditions = NetConditions.TRELLO_8).play()

        assertEquals(
            1,
            run.ticksToLocalResponse,
            "the predicted champion must move on the same tick the input is read",
        )
        assertTrue(
            run.ticksToServerResponse >= MINIMUM_ROUND_TRIP,
            "at 150ms each way the server's answer cannot arrive in under $MINIMUM_ROUND_TRIP " +
                "ticks; it took ${run.ticksToServerResponse}, so this link is not what it claims",
        )
    }

    @Test
    fun `on a perfect link replay reproduces the prediction exactly, so nothing is corrected`() {
        // The strongest available statement that the client's arithmetic *is* the server's: with
        // no loss, every command the client predicted is a command the server simulated, so the
        // replayed pose must come back bit-identical and the correction count must be zero.
        val run = Run(seed = SEEDS[0], conditions = NetConditions(latencyTicks = LATENCY_TICKS)).play()
        report("perfect", listOf(run))
        assertEquals(0L, run.ackMisses, "the stubbed ack table must not miss")
        assertEquals(0L, run.corrections, "a lossless link must produce no correction at all")
        assertEquals(0f, run.maxCorrection)
        assertEquals(0L, run.snaps)
    }

    @Test
    fun `prediction converges on the server under loss, over five seeded runs`() {
        val runs = SEEDS.map { Run(seed = it, conditions = NetConditions.TRELLO_8).play() }
        for (run in runs) {
            assertEquals(0L, run.ackMisses, "seed ${run.seed}: the stubbed ack table must not miss")
            assertTrue(
                run.maxCorrection < MAX_TOLERABLE_CORRECTION,
                "seed ${run.seed}: max correction ${run.maxCorrection} is a visible yank",
            )
            assertEquals(0L, run.snaps, "seed ${run.seed}: nothing here should reach the snap distance")
            assertTrue(
                run.residualAtRest <= SETTLED,
                "seed ${run.seed}: the last correction must have been absorbed by the end of the " +
                    "run; ${run.residualAtRest} of it is still on screen",
            )
            // Convergence, stated so it is not a tautology: for every command the server says
            // it simulated, the client is asked where it thought it would be *after that same
            // command*, and the two are compared. A predictor using the wrong axis, dropping a
            // replay, or pairing an ack with the wrong tick diverges here without bound; the
            // only thing that should show up is the input the link actually lost.
            assertTrue(
                run.worstPredictionError <= MAX_PREDICTION_ERROR,
                "seed ${run.seed}: the client's position after a command was " +
                    "${run.worstPredictionError} from the server's, which is more than the " +
                    "lost input can explain",
            )
        }
        report("loss", runs)
    }

    @Test
    fun `remote champions interpolate without teleporting, over five seeded runs`() {
        val runs = SEEDS.map { Run(seed = it, conditions = NetConditions.TRELLO_8).play() }
        for (run in runs) {
            assertEquals(
                0L,
                run.teleports,
                "seed ${run.seed}: a remote unit jumped further in one tick than it can walk",
            )
            assertTrue(
                run.worstRemoteStep <= WORST_REMOTE_STEP,
                "seed ${run.seed}: a remote unit moved ${run.worstRemoteStep} in one drawn frame, " +
                    "against a walking speed of $SPEED - that is a teleport, not interpolation",
            )
            assertTrue(
                run.remoteSamples > SAMPLE_FLOOR,
                "seed ${run.seed}: only ${run.remoteSamples} interpolated samples were taken",
            )
        }
        report("interpolation", runs)
    }

    /** The report the brief asks for. Printed rather than asserted: it is evidence, not a gate. */
    private fun report(label: String, runs: List<Run.Result>) {
        val lines = runs.joinToString("\n") { run ->
            "  seed=%d corrections=%d max=%.5f err=%.5f replayed=%d ackMisses=%d starved=%d teleports=%d worstStep=%.4f jitter[%s]"
                .format(
                    run.seed,
                    run.corrections,
                    run.maxCorrection,
                    run.worstPredictionError,
                    run.replayed,
                    run.ackMisses,
                    run.starved,
                    run.teleports,
                    run.worstRemoteStep,
                    run.jitter,
                )
        }
        println("prediction proof [$label] over ${runs.size} runs at 150ms/5% loss:\n$lines")
    }

    /**
     * The header field that does not exist: `lastProcessedInputSeq`, per server tick.
     *
     * Keyed by the tick of the snapshot it would have ridden on, so the client pairs the ack with
     * the position it belongs to. Pairing them is not a detail - reconciling a fresh ack against
     * a stale position drops commands the server has not simulated, and the prediction then runs
     * permanently short.
     */
    private class Wire {

        private val acks = HashMap<Long, Int>()

        fun publish(tick: Tick, seq: Int) {
            acks[tick.value] = seq
        }

        fun ackAt(tick: Tick): Int? = acks[tick.value]
    }

    /** One seeded session, played to the end. */
    private class Run(val seed: Long, private val conditions: NetConditions) {

        private val model = PlanarMoveModel(SPEED)
        private val wire = Wire()
        private lateinit var session: ReplicationSession
        private lateinit var client: Client

        /** The server's copy of the champion this client drives. */
        private var owned: NetId = NetId.NONE

        /** Units the client does not drive. They walk a scripted path so they are worth watching. */
        private val remotes = ArrayList<NetId>()

        private val serverPose = PredictedPose()

        fun play(): Result {
            session = ReplicationSession(
                clientCount = 1,
                seed = seed,
                conditions = conditions,
                mutate = ::serverTick,
                clientTick = { replication, tick -> client.onTick(replication, tick) },
            )
            client = Client(model, wire, session.clients[0])
            session.step(TOTAL_TICKS)
            val jitter = session.server.jitterOf(PeerId.client(1))
            client.jitterStats = "accepted=${jitter.accepted} dup=${jitter.duplicates} " +
                "stale=${jitter.stale} starve=${jitter.starvations} over=${jitter.overflows}"
            return client.result(seed)
        }

        /**
         * The server's tick: consume exactly one command, move what it says, walk the AI units.
         *
         * This is `MobaHostSession.drainInput` plus `PlayerMovementSystem`, reduced to the two
         * lines that matter. The command reaches it through the real [JitterBuffer], filled by
         * the real `ReplicationServer.onPacket` off real datagrams.
         */
        fun serverTick(tick: Tick) {
            if (owned == NetId.NONE) {
                owned = session.world.spawn(0f, 0f)
                repeat(REMOTE_COUNT) { remotes += session.world.spawn(REMOTE_SPACING * (it + 1), 0f) }
            }
            val jitter = session.server.jitterOf(PeerId.client(1))
            val command = jitter.consume(tick)
            if (command != null) {
                val mover = session.world.mover(owned)
                serverPose.set(mover.x, mover.y)
                model.step(serverPose, command)
                mover.x = serverPose.x
                mover.y = serverPose.y
            }
            for ((index, id) in remotes.withIndex()) walk(session.world.mover(id), tick, index)
            // Keyed by the tick of the snapshot this movement will be captured into, which is
            // one past the world clock as it stands *before* the capture - not off the harness
            // tick, which is a different clock that only happens to agree today. Getting this one
            // apart is a silent, catastrophic mispairing: the ack then names one command fewer
            // than the position includes, the client keeps that command and replays it on top,
            // and the prediction runs exactly one step long on every single tick.
            wire.publish(Tick(session.world.ctx.clock.tick.value + 1L), jitter.lastProcessedInputSeq)
        }

        /** A remote unit's scripted path: a slow walk that reverses, so interpolation has work. */
        private fun walk(mover: Mover, tick: Tick, index: Int) {
            val phase = (tick.value + index * REMOTE_SPACING.toLong()) % REVERSE_PERIOD
            val direction = if (phase < REVERSE_PERIOD / 2) 1f else -1f
            mover.y += direction * SPEED
        }

        /** Everything the brief asks to be reported, plus what each assertion needs. */
        class Result(
            val seed: Long,
            val ticksToLocalResponse: Int,
            val ticksToServerResponse: Int,
            val corrections: Long,
            val maxCorrection: Float,
            val replayed: Long,
            val snaps: Long,
            val ackMisses: Long,
            val worstPredictionError: Float,
            val residualAtRest: Float,
            val jitter: String,
            val teleports: Long,
            val starved: Long,
            val remoteSamples: Long,
            val worstRemoteStep: Float,
        )
    }

    /**
     * The predicting, interpolating client. What `MobaClientSession` now does, without a world.
     *
     * The order inside [onTick] is the contract and it is the same one `MobaClientSession` uses:
     * reconcile against what arrived, advance the render clock, then predict this tick's input.
     * Predicting before reconciling would apply the new command and immediately throw it away.
     */
    private class Client(
        model: MoveModel,
        private val wire: Wire,
        private val replication: ReplicationClient,
    ) {

        private val prediction = LocalPrediction(model)
        private val clock = InterpolationClock()
        private val interpolation = EntityInterpolator()
        private val sampled = PredictedPose()
        private val lastDrawn = HashMap<Int, PredictedPose>()

        private val predictedAfter = HashMap<Int, Float>()
        private var seq = 0
        private var appliedTick = Tick.ZERO
        private var owned: NetId = NetId.NONE
        private var moverComponent = -1

        /**
         * Whether the champion's own fields were in the packets applied since the last tick.
         *
         * `ReplicationServer` packs by priority against a per-entity baseline, so a client's
         * store is legitimately a *mix* of server ticks: the header says tick T while some
         * entity in it was last written at T-1. Reconciling the champion's T-1 position against
         * the ack for T drops one command the server had not simulated, and the prediction is
         * then exactly one step short - which is what this counter exists to prevent.
         */
        private var championTouched = false

        init {
            replication.applySink = SnapshotApplySink { netId, _, _, _ ->
                if (netId == owned) championTouched = true
            }
        }

        var ackMisses = 0L
            private set

        /** @see PredictionProofTest.MAX_PREDICTION_ERROR */
        var worstPredictionError = 0f
            private set

        private var localResponseTick = -1
        private var serverResponseTick = -1
        private var inputStartTick = -1
        private var predictedAtStart = 0f
        private var authoritativeAtStart = 0f
        private var worstRemoteStep = 0f

        fun onTick(client: ReplicationClient, tick: Tick) {
            check(client === replication) { "this client was built over a different session" }
            applyArrivals(tick)
            clock.advance()
            drawRemotes()
            sendInput(tick)
            prediction.advance()
            recordResponses(tick)
        }

        /** Reconciles against the newest snapshot, and feeds every remote unit's track. */
        private fun applyArrivals(tick: Tick) {
            val store = replication.world
            if (moverComponent < 0) moverComponent = moverIndexOf(store)
            if (replication.serverTick <= appliedTick) return
            appliedTick = replication.serverTick
            if (owned == NetId.NONE) {
                owned = lowestLiveId(store) ?: return
                championTouched = true
            }
            val championIsCurrent = championTouched
            championTouched = false
            for (row in 0 until store.rowHighWater) {
                if (!store.isLive(row)) continue
                val netId = store.netIdAt(row)
                val slot = store.slotOf(row, moverComponent)
                if (slot == ReplicaStore.ABSENT) continue
                val field = store.storeAt(moverComponent)
                val x = field.getFloat(slot, MoverReplicator.X)
                val y = field.getFloat(slot, MoverReplicator.Y)
                if (netId == owned) {
                    if (championIsCurrent) reconcile(x, y, tick)
                } else {
                    interpolation.record(netId, appliedTick, x, y)
                }
            }
            interpolation.forgetAllExcept { raw -> NetId.ofRaw(raw) in store }
            clock.onSnapshot(appliedTick)
        }

        private fun reconcile(x: Float, y: Float, tick: Tick) {
            if (!prediction.started) {
                prediction.start(x, y)
                authoritativeAtStart = x
                return
            }
            val acked = wire.ackAt(appliedTick)
            if (acked == null) ackMisses++
            if (acked != null) measurePrediction(acked, x)
            prediction.reconcile(x, y, acked ?: JitterBuffer.NO_SEQ)
            if (inputStartTick >= 0 && serverResponseTick < 0 && abs(x - authoritativeAtStart) > MOVED) {
                serverResponseTick = tick.value.toInt() - inputStartTick
            }
        }

        /**
         * Compares where the client thought it would be after command [acked] with where the
         * server says it was, and forgets everything the server has now answered for.
         */
        private fun measurePrediction(acked: Int, authoritativeX: Float) {
            val believed = predictedAfter.remove(acked)
            if (believed != null) {
                val error = abs(authoritativeX - believed)
                if (error > worstPredictionError) worstPredictionError = error
            }
            predictedAfter.keys.removeAll { held -> !PacketHeader.isNewer(held, acked) }
        }

        /** Samples every tracked remote at the render tick, and measures the worst frame-to-frame step. */
        private fun drawRemotes() {
            if (!clock.started) return
            val store = replication.world
            for (row in 0 until store.rowHighWater) {
                if (!store.isLive(row)) continue
                val netId = store.netIdAt(row)
                if (netId == owned) continue
                if (!interpolation.sample(netId, clock.renderTick, sampled)) continue
                val previous = lastDrawn[netId.raw]
                if (previous != null) {
                    val dx = sampled.x - previous.x
                    val dy = sampled.y - previous.y
                    val step = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (step > worstRemoteStep) worstRemoteStep = step
                    previous.set(sampled)
                } else {
                    lastDrawn[netId.raw] = PredictedPose(sampled.x, sampled.y)
                }
            }
        }

        /** Mints this tick's command, predicts it immediately, and queues it for the wire. */
        private fun sendInput(tick: Tick) {
            if (!prediction.started) return
            val walking = tick.value >= WARMUP_TICKS && tick.value < WARMUP_TICKS + WALK_TICKS
            if (walking && inputStartTick < 0) {
                inputStartTick = tick.value.toInt()
                predictedAtStart = prediction.settledX
                authoritativeAtStart = authoritativeX()
            }
            val axis = if (walking) 1f else 0f
            val command = MoveInput(seq and SEQ_MASK, tick, axis, 0f, 0f, 0)
            seq++
            prediction.predict(command)
            predictedAfter[command.seq] = prediction.settledX
            replication.pushInput(command)
        }

        private fun recordResponses(tick: Tick) {
            if (inputStartTick < 0 || localResponseTick >= 0) return
            if (abs(prediction.settledX - predictedAtStart) > MOVED) {
                localResponseTick = tick.value.toInt() - inputStartTick + 1
            }
        }

        private fun authoritativeX(): Float {
            val store = replication.world
            val row = store.rowOf(owned)
            if (row == ReplicaStore.ABSENT) return 0f
            val slot = store.slotOf(row, moverComponent)
            if (slot == ReplicaStore.ABSENT) return 0f
            return store.storeAt(moverComponent).getFloat(slot, MoverReplicator.X)
        }

        private fun lowestLiveId(store: ReplicaStore): NetId? {
            var best: NetId? = null
            for (row in 0 until store.rowHighWater) {
                if (!store.isLive(row)) continue
                val id = store.netIdAt(row)
                if (best == null || id.raw < best.raw) best = id
            }
            return best
        }

        private fun moverIndexOf(store: ReplicaStore): Int {
            val registry = store.registry
            for (index in 0 until registry.size) {
                if (registry.typeAt(index).componentClass == Mover::class) return index
            }
            error("this registry has no Mover")
        }

        var jitterStats: String = ""

        fun result(seed: Long) = Run.Result(
            seed = seed,
            ticksToLocalResponse = localResponseTick,
            ticksToServerResponse = serverResponseTick,
            corrections = prediction.corrections,
            maxCorrection = prediction.maxCorrection,
            replayed = prediction.replayed,
            snaps = prediction.snaps,
            ackMisses = ackMisses,
            worstPredictionError = worstPredictionError,
            residualAtRest = prediction.residual,
            jitter = jitterStats,
            teleports = interpolation.teleports,
            starved = interpolation.starved,
            remoteSamples = interpolation.sampled,
            worstRemoteStep = worstRemoteStep,
        )
    }

    private companion object {

        /** Five independent loss patterns. A claim run once is not a claim. */
        val SEEDS = longArrayOf(20_260_823L, 7L, 99L, 4242L, 31_337L)

        const val SPEED = 0.75f

        /** One tick of full deflection through the wire codec: what both peers actually move. */
        val STEP: Float = PlanarMoveModel.onWire(1f) * SPEED
        const val REMOTE_COUNT = 6
        const val REMOTE_SPACING = 40f
        const val REVERSE_PERIOD = 90L
        const val SEQ_MASK = 0xFFFF

        /** Nine ticks: 150ms at 60Hz, the figure `NetConditions.TRELLO_8` uses. */
        const val LATENCY_TICKS = 9

        /** Out and back at nine ticks each way, minus the tick the send happens on. */
        const val MINIMUM_ROUND_TRIP = 2 * LATENCY_TICKS - 1

        const val WARMUP_TICKS = 60L
        const val WALK_TICKS = 240L

        /** Long enough after the walk stops for every command to be acknowledged and settle. */
        const val TOTAL_TICKS = 600

        /**
         * A displacement that means "walked", in world units.
         *
         * Half a step. Comfortably above the centred-stick drift the wire's 8-bit axis produces
         * (0.0029 a tick - see `MoveModelTest`), which is why it is not simply "changed at all":
         * with that defect on the wire the champion's position is *never* still.
         */
        val MOVED: Float = STEP * 0.5f

        const val SETTLED = 1e-3f

        /**
         * How far the client's answer for a given command may be from the server's.
         *
         * Six steps. Not zero, because at 5% loss some commands never reach the server at all
         * and a starved jitter buffer repeats its last one - both are the link, not the
         * predictor. It is a bound on how much of that can accumulate before it is visible.
         */
        val MAX_PREDICTION_ERROR: Float = STEP * 6f

        /**
         * The largest correction that is still absorbable rather than a yank, in world units.
         *
         * Five steps. Smoothed at a decay of 0.8 the first drawn frame of a correction that
         * large moves the champion one step - the same distance it walks in an ordinary tick -
         * so the worst case reads as a nudge rather than as being pulled.
         */
        val MAX_TOLERABLE_CORRECTION: Float = STEP * 5f

        /**
         * The most a remote unit may move in one drawn frame, in world units.
         *
         * Four ticks of walking. One tick plus the render clock's catch-up is the steady-state
         * step; the slack is for recovering from a burst of loss, where the buffer starved and
         * the clock has ground to make up. A real teleport in this scene is hundreds of units,
         * so this is a wide bound that still fails loudly on the defect it is aimed at.
         */
        const val WORST_REMOTE_STEP = SPEED * 4f

        /** Six units drawn for most of 600 ticks. Far below it means the buffer never filled. */
        const val SAMPLE_FLOOR = 1000L
    }
}
