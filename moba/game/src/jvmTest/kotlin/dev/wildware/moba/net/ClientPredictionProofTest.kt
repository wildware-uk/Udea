package dev.wildware.moba.net

import dev.wildware.moba.level.UnitKind
import dev.wildware.udea.core.Tick
import dev.wildware.udea.net.input.JitterBuffer
import dev.wildware.udea.net.wire.PacketHeader
import dev.wildware.udea.net.prediction.InterpolationClock
import dev.wildware.udea.net.prediction.PlanarMoveModel
import dev.wildware.udea.net.prediction.PredictedPose
import dev.wildware.udea.net.replication.BandwidthBudget
import dev.wildware.udea.net.transport.NetConditions
import dev.wildware.udea.net.transport.NetEndpoint
import dev.wildware.udea.net.transport.NetHarness
import dev.wildware.udea.net.transport.PeerId
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #112 in the game itself: two `moba` clients, a real server, and a 150ms link.
 *
 * The engine-level proof lives in `udea-net`'s `PredictionProofTest`, which drives the same
 * `LocalPrediction` and `EntityInterpolator` over the real replication stack with a synthetic
 * world. This one is the wiring check the standing instruction asks for: that
 * [MobaClientSession] actually predicts *this game's* champion, through this game's
 * `Player.owner`, at this game's `UnitKind.moveSpeed`, and interpolates the other player.
 *
 * ## Nothing is stubbed
 *
 * This test used to hand [MobaClientSession] an [InputAckSource] backed by a table, because the
 * sequence of the last command the server simulated was not on the wire and reconciliation has
 * nowhere else to anchor. It is on the wire now - `PacketHeader.inputAck`, written from the
 * server's own [JitterBuffer] and read back by `ReplicationClient` - so the session is built with
 * the default source, which reads it off the packet. Every number this test asserts on now
 * crossed the simulated link.
 */
class ClientPredictionProofTest {

    private var session: Fixture? = null

    @AfterTest
    fun tearDown() {
        session?.close()
    }

    @Test
    fun `the local champion moves on the tick its input is read, at 150ms`() {
        val fixture = Fixture(NetConditions(latencyTicks = LATENCY_TICKS)).also { session = it }
        fixture.step(WARMUP_TICKS)

        val prediction = assertNotNull(fixture.clients[0].prediction, "the client must have found its champion")
        assertTrue(prediction.started, "and started predicting from the server's position")

        val drawnBefore = PredictedPose()
        assertTrue(fixture.view(0, drawnBefore), "the champion must be drawable before the walk starts")

        fixture.walking = true
        fixture.step(1)

        val drawnAfter = PredictedPose()
        fixture.view(0, drawnAfter)
        assertTrue(
            drawnAfter.x > drawnBefore.x,
            "the drawn champion must move on the tick the input is read; it moved " +
                "${drawnAfter.x - drawnBefore.x}",
        )

        fixture.step(LEAD_TICKS)
        val lead = prediction.settledX - fixture.authoritativeX(0)
        println(
            "moba prediction: lead=$lead step=$STEP pending=${prediction.pendingCount} " +
                "corrections=${prediction.corrections} max=${prediction.maxCorrection} " +
                "replayed=${prediction.replayed}",
        )

        // The server is a round trip away, stated exactly: this many of the client's commands
        // have been sent and not yet acknowledged as simulated. Nine ticks each way plus the
        // jitter buffer's own depth. A client without prediction has nothing here at all.
        assertTrue(
            prediction.pendingCount >= MINIMUM_ROUND_TRIP,
            "at $LATENCY_TICKS ticks each way there must be at least $MINIMUM_ROUND_TRIP " +
                "commands in flight; there were ${prediction.pendingCount}",
        )

        // And the point of prediction as a distance rather than a feeling: the champion on this
        // screen is most of a round trip ahead of the newest position the server has managed to
        // tell this client about. For a client that waited for the server this is zero.
        assertTrue(
            lead >= MINIMUM_LEAD,
            "the prediction must lead the server's answer by the input still in flight; it led " +
                "by $lead, which is under $MINIMUM_LEAD",
        )
        assertEquals(0L, prediction.snaps, "nothing here should have snapped")
    }

    @Test
    fun `the acknowledged command sequence crosses the link, and bounds the replay`() {
        // THE REGRESSION GUARD for the stub this proof used to carry.
        //
        // Reconciliation anchors on "which of my commands has the server simulated". That number
        // is not the packet ack - a datagram carries whatever was in flight, a command waits a
        // variable number of ticks in the jitter buffer, and a starved buffer *repeats* a command
        // with no datagram involved at all - so `PacketHeader` carries it as its own field.
        //
        // Without it every reconciliation replays the entire history: the pending list never
        // empties, it fills to `historyCapacity`, and `overruns` starts counting commands dropped
        // on the floor - at which point the prediction is permanently short and there is no other
        // symptom. Both halves are asserted, because either alone can pass on a stub.
        val fixture = Fixture(NetConditions(latencyTicks = LATENCY_TICKS)).also { session = it }
        fixture.walking = true
        fixture.step(WARMUP_TICKS + WATCH_TICKS)

        val client = fixture.clients[0]
        assertNotEquals(
            PacketHeader.NO_INPUT_ACK,
            client.replication.inputAck,
            "no datagram carried an input acknowledgement, so the client is reconciling blind",
        )
        // Behind by one one-way latency, and no more: the number the client holds is the one the
        // server stamped, delayed by the trip. Equality would be asserting a link with no delay.
        val lag = fixture.server.replication.jitterOf(client.peer).lastProcessedInputSeq -
            client.replication.inputAck
        assertTrue(
            lag in 0..MAX_IN_FLIGHT,
            "the client believes the server has simulated command ${client.replication.inputAck} " +
                "while the server is on " +
                "${fixture.server.replication.jitterOf(client.peer).lastProcessedInputSeq}: a lag " +
                "of $lag commands is not a link, it is a stalled acknowledgement",
        )

        val prediction = assertNotNull(client.prediction)
        assertEquals(0L, prediction.overruns, "commands were dropped: the history filled up")
        assertTrue(
            prediction.pendingCount <= MAX_IN_FLIGHT,
            "${prediction.pendingCount} commands are unacknowledged after " +
                "${WARMUP_TICKS + WATCH_TICKS} ticks at $LATENCY_TICKS ticks of latency; a bounded " +
                "round trip must bound this, and an unbounded one means nothing is being acked",
        )
        assertTrue(prediction.pendingCount > 0, "nothing is in flight at all, so this proves nothing")
    }

    @Test
    fun `the other player's champion interpolates rather than teleporting`() {
        val fixture = Fixture(NetConditions.TRELLO_8).also { session = it }
        fixture.walking = true
        fixture.step(WARMUP_TICKS + WATCH_TICKS)

        val watcher = fixture.clients[1]
        val driverChampion = fixture.champions[0]
        assertTrue(
            watcher.interpolation.recorded > 0L,
            "the watching client must have buffered the other champion's positions",
        )
        assertEquals(
            0L,
            watcher.interpolation.teleports,
            "a champion jumped further in one server tick than any unit in this game can walk",
        )
        assertTrue(
            fixture.worstRemoteStep > 0f,
            "the watched champion must actually have moved, or this proves nothing",
        )
        println(
            "moba interpolation: worstDrawnStep=${fixture.worstRemoteStep} step=$STEP " +
                "serverStep=${fixture.worstServerStep} " +
                "recorded=${watcher.interpolation.recorded} starved=${watcher.interpolation.starved} " +
                "teleports=${watcher.interpolation.teleports} resyncs=${watcher.renderClock.resyncs}",
        )
        // Bounded by what the server *actually did*, not by walking speed: in `moba` a champion
        // is also shoved by the crowd it is standing in, so the authoritative position can move
        // further in a tick than any unit walks. The claim interpolation makes is that it adds
        // no motion of its own - a drawn frame covers at most one server tick of real movement,
        // times the render clock's own catch-up ceiling.
        val allowed = fixture.worstServerStep * InterpolationClock.DEFAULT_MAX_RATE + TOLERANCE
        assertTrue(
            fixture.worstRemoteStep <= allowed,
            "the watched champion moved ${fixture.worstRemoteStep} in one drawn frame while the " +
                "server never moved it more than ${fixture.worstServerStep} in a tick - the " +
                "interpolator invented that motion",
        )
        assertTrue(
            watcher.champion != driverChampion,
            "the watcher must not have mistaken the driver's champion for its own",
        )
    }

    @Test
    fun `presentView puts the drawn position on the components the renderer reads`() {
        // The opt-in that makes any of this visible. Off, this world stays a faithful copy of the
        // server's and `state()` is comparable with it; on, `Position` carries the *drawn* pose,
        // which is what a window wants and what a hash comparison must never be handed.
        val fixture = Fixture(NetConditions(latencyTicks = LATENCY_TICKS)).also { session = it }
        fixture.walking = true
        fixture.step(WARMUP_TICKS + LEAD_TICKS)

        val driver = fixture.clients[0]
        val drawn = PredictedPose()
        assertTrue(driver.sampleView(fixture.champions[0], drawn))
        assertTrue(
            abs(fixture.componentX(0) - drawn.x) > MOVED,
            "with presentation off the component must still hold the server's position, not the " +
                "predicted one",
        )

        driver.presentView = true
        fixture.step(1)
        driver.sampleView(fixture.champions[0], drawn)
        assertEquals(
            drawn.x,
            fixture.componentX(0),
            TOLERANCE,
            "with presentation on the component the renderer reads must be the drawn position",
        )
    }

    /**
     * Server, two clients, one in-memory link, one thread.
     *
     * Built here rather than on `MobaLoopbackSession` because that class mints every client's
     * command for it and has nowhere to hand an [InputAckSource] through - and neither belongs to
     * this wave. See the report: once the acknowledged sequence is on the wire, this fixture
     * collapses into `MobaLoopbackSession` plus two lines.
     */
    private class Fixture(conditions: NetConditions) : AutoCloseable {

        val harness: NetHarness = NetHarness(CLIENTS, initialConditions = conditions, mtu = MTU)
        val server: MobaHostSession =
            MobaHostSession(harness.transport(PeerId.SERVER), BandwidthBudget(MTU), MTU)

        val champions = ArrayList<dev.wildware.udea.core.identity.NetId>()
        val clients: List<MobaClientSession>

        /** Whether client 0 is holding a direction this tick. */
        var walking: Boolean = false

        /** The largest single-frame move any drawn remote champion made on client 1. */
        var worstRemoteStep: Float = 0f
            private set

        /**
         * The largest single-**tick** move the server itself made champion 0 make.
         *
         * The yardstick the drawn motion is judged against. `moba` champions are shoved apart by
         * the crowd as well as walked by their player, so "further than `moveSpeed`" is not the
         * same claim as "teleported".
         */
        var worstServerStep: Float = 0f
            private set

        private var haveServerPose = false
        private var serverX = 0f
        private var serverY = 0f

        private val sampled = PredictedPose()
        private val lastDrawn = PredictedPose()
        private var haveLastDrawn = false

        init {
            clients = (1..CLIENTS).map { index ->
                val peer = PeerId.client(index)
                champions += server.addClient(peer)
                MobaClientSession(
                    peer = peer,
                    transport = harness.transport(peer),
                    mtu = MTU,
                    // No `inputAck`: the default reads `PacketHeader.inputAck` off the datagram.
                )
            }
            harness.register(
                object : NetEndpoint {
                    override val peer: PeerId = PeerId.SERVER

                    override fun onReceive(from: PeerId, buffer: ByteArray, offset: Int, length: Int) {
                        server.onPacket(from, buffer, offset, length)
                    }

                    override fun onTick(tick: Tick) {
                        server.tick()
                        measureServer()
                    }
                },
            )
            for ((index, client) in clients.withIndex()) {
                harness.register(
                    object : NetEndpoint {
                        override val peer: PeerId = client.peer

                        override fun onReceive(from: PeerId, buffer: ByteArray, offset: Int, length: Int) {
                            client.onPacket(buffer, offset, length)
                        }

                        override fun onTick(tick: Tick) {
                            val axis = if (index == 0 && walking) 1f else 0f
                            client.tick(tick, client.command(tick, moveX = axis))
                            if (index == 1) measureRemote()
                        }
                    },
                )
            }
        }

        /** How far the server moved champion 0 on the tick it has just run. */
        private fun measureServer() {
            val position = server.host.ctx[dev.wildware.udea.core.module.CoreModule.NET_IDS]
                .resolveOrNull(champions[0]) ?: return
            val point = with(server.host.world) { position.getOrNull(dev.wildware.moba.Position) } ?: return
            if (haveServerPose) {
                val dx = point.x - serverX
                val dy = point.y - serverY
                val step = kotlin.math.sqrt(dx * dx + dy * dy)
                if (step > worstServerStep) worstServerStep = step
            }
            serverX = point.x
            serverY = point.y
            haveServerPose = true
        }

        /** Watches client 1 draw client 0's champion, frame by frame. */
        private fun measureRemote() {
            val watcher = clients[1]
            if (!watcher.sampleView(champions[0], sampled)) return
            if (haveLastDrawn) {
                val dx = sampled.x - lastDrawn.x
                val dy = sampled.y - lastDrawn.y
                val step = kotlin.math.sqrt(dx * dx + dy * dy)
                if (step > worstRemoteStep) worstRemoteStep = step
            }
            lastDrawn.set(sampled)
            haveLastDrawn = true
        }

        fun step(ticks: Int): Tick = harness.step(ticks)

        /** Where [seat]'s client believes its champion is, drawn. */
        fun view(seat: Int, into: PredictedPose): Boolean =
            clients[seat].sampleView(champions[seat], into)

        /** What [seat]'s client's live `Position` component holds - what `MobaScene` draws. */
        fun componentX(seat: Int): Float {
            val entity = clients[seat].host.ctx[dev.wildware.udea.core.module.CoreModule.NET_IDS]
                .resolveOrNull(champions[seat]) ?: return Float.NaN
            return with(clients[seat].world) { entity.getOrNull(dev.wildware.moba.Position)?.x } ?: Float.NaN
        }

        /** The server's position for [seat]'s champion, as that client currently holds it. */
        fun authoritativeX(seat: Int): Float {
            val store = clients[seat].replication.world
            val registry = store.registry
            val component = (0 until registry.size).first {
                registry.typeAt(it).componentClass == dev.wildware.moba.Position::class
            }
            val row = store.rowOf(champions[seat])
            if (row == dev.wildware.udea.net.wire.ReplicaStore.ABSENT) return 0f
            val slot = store.slotOf(row, component)
            if (slot == dev.wildware.udea.net.wire.ReplicaStore.ABSENT) return 0f
            return store.storeAt(component)
                .getFloat(slot, dev.wildware.moba.PositionReplicator.FIELD_X)
        }

        override fun close() {
            for (client in clients) client.close()
            server.close()
            harness.close()
        }
    }

    private companion object {

        /** `UnitKind.Soldier.moveSpeed`, read off the enum so the two cannot drift apart. */
        val SOLDIER_SPEED: Float = UnitKind.Soldier.moveSpeed

        /** One tick of full deflection, through the axis codec: what both peers actually move. */
        val STEP: Float = PlanarMoveModel.onWire(1f) * SOLDIER_SPEED

        const val CLIENTS = 2

        /** The agreement proofs raise the MTU for the same reason: no budget deferral. */
        const val MTU = 8192

        /** Nine ticks: 150ms at 60Hz. */
        const val LATENCY_TICKS = 9

        /** Out and back, minus the tick the send happens on. */
        const val MINIMUM_ROUND_TRIP = 2 * LATENCY_TICKS - 1

        /** Enough for the champions to be created, replicated and identified. */
        const val WARMUP_TICKS = 40

        /** Half a step: above the centred-stick drift the 8-bit axis produces, below one walk. */
        val MOVED: Float = STEP * 0.5f

        /** Ticks of held input to build a lead with. Comfortably inside one round trip. */
        const val LEAD_TICKS = 20

        /**
         * How far ahead of the server's last known answer the champion must be, in world units.
         *
         * Ten steps. The round trip is eighteen ticks and the client walks for twenty-one before
         * this is measured, so the honest expectation is around eighteen steps; ten is the bound
         * that fails on a client which is merely *slightly* ahead rather than predicting.
         */
        val MINIMUM_LEAD: Float = STEP * 10f

        const val WATCH_TICKS = 120

        /** Float slack for a single accumulated step. */
        const val TOLERANCE: Float = 1e-4f

        /**
         * The most commands that may be unacknowledged at once.
         *
         * A round trip is `2 * LATENCY_TICKS` ticks and a client sends on every other tick
         * (30Hz input against a 60Hz simulation), so the floor is about nine commands. Doubled
         * and rounded up for the jitter buffer's own depth: the assertion is hunting an
         * *unbounded* list, which is 128, not a list that is one or two long.
         */
        const val MAX_IN_FLIGHT: Int = 24
    }
}
