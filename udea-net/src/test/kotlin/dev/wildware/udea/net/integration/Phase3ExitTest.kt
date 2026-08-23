package dev.wildware.udea.net.integration

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.movement.CharacterMover
import dev.wildware.udea.core.movement.MoveIntent
import dev.wildware.udea.core.movement.MoverConfig
import dev.wildware.udea.core.movement.MoverState
import dev.wildware.udea.core.movement.StaticCollision
import dev.wildware.udea.core.snapshot.FieldComparison
import dev.wildware.udea.net.bits.BitBufferReader
import dev.wildware.udea.net.bits.BitBufferWriter
import dev.wildware.udea.net.bits.readVarInt
import dev.wildware.udea.net.harness.ReplicationSession
import dev.wildware.udea.net.input.InputRing
import dev.wildware.udea.net.input.JitterBuffer
import dev.wildware.udea.net.input.MoveInput
import dev.wildware.udea.net.replication.DesyncReport
import dev.wildware.udea.net.transport.DatagramSink
import dev.wildware.udea.net.transport.LoopbackNetwork
import dev.wildware.udea.net.transport.ManualClock
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.wire.ReplicaStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Phase 3 exit criteria, driven rather than asserted about.
 *
 * Two claims live here because they are the two the phase turns on, and because each is only
 * worth anything if the other is also true:
 *
 * 1. a server and two clients, in one JVM, over an in-memory network, converge on identical
 *    state - and, separately, agree with **each other**, which no existing test checked;
 * 2. one scripted input sequence, sent by a client and consumed by a server, drives two
 *    independently constructed [CharacterMover]s to bit-identical state on every tick.
 *
 * Claim 2 is the foundation prediction rests on. If the client's mover and the server's mover
 * disagree by one ULP on tick 3, every predicted frame after it is wrong, and claim 1's
 * convergence only hides it because the authoritative state overwrites the prediction. So the
 * comparison is on `toRawBits`, not on a tolerance: a float epsilon passes on exactly the
 * divergence prediction cannot survive.
 */
class Phase3ExitTest {

    // --- claim 1: one server, two clients, no sockets ------------------------------------------

    @Test
    fun `a server and two clients over one in-memory network agree with the server and with each other`() {
        lateinit var session: ReplicationSession
        val ids = ArrayList<NetId>()
        session = ReplicationSession(
            clientCount = 2,
            mutate = { tick ->
                if (ids.isEmpty()) repeat(8) { ids += session.world.spawn(it.toFloat(), 0f, teamId = it % 2) }
                for ((index, netId) in ids.withIndex()) {
                    val mover = session.world.mover(netId)
                    mover.x = (tick.value + index).toFloat()
                    mover.y = (tick.value * 2 + index).toFloat()
                }
            },
        )

        session.step(40)

        assertEquals(2, session.clients.size)
        for (client in session.clients) {
            assertTrue(client.applied > 30, "${client.peer} applied only ${client.applied} packets")
            val server = session.serverStateAt(client.serverTick).fields
            val report = DesyncReport.compare(session.world.registry, server, client.world)
            assertTrue(report.isEmpty(), "${client.peer} differs from the server:\n" + report.joinToString("\n"))
        }

        // The half no other test makes. Both clients agreeing with the server *at the tick each
        // one happens to hold* does not by itself mean they agree with each other: two clients
        // one tick apart both pass that check while showing different worlds. This compares the
        // two replica stores directly, having first asserted they are on the same tick.
        val a = session.clients[0]
        val b = session.clients[1]
        assertEquals(a.serverTick, b.serverTick, "the two clients are not on the same server tick")
        assertEquals(emptyList(), diff(a.world, b.world), "the two clients disagree with each other")
        assertEquals(8, a.world.liveNetIds().size, "the clients agree on an empty world, which proves nothing")

        // No sockets: the session ran on one LoopbackNetwork, which delivers by copying into a
        // pooled buffer. This asserts the substrate rather than the absence of a socket - an
        // absent socket is not observable from inside the JVM without an agent - but it asserts
        // the load-bearing part: every datagram above went through `LoopbackNetwork`, and the
        // pool and the log prove datagrams were actually carried rather than optimised away.
        assertTrue(session.harness.allocatedDatagrams() > 0, "no datagram was ever carried")
        assertTrue(session.harness.log.events.isNotEmpty(), "the packet log is empty, so nothing was sent")
    }

    /** Every replicated field on which two replica stores disagree. */
    private fun diff(left: ReplicaStore, right: ReplicaStore): List<String> {
        val registry = left.registry
        val problems = ArrayList<String>()
        val leftIds = left.liveNetIds().sortedBy { it.raw }
        val rightIds = right.liveNetIds().sortedBy { it.raw }
        if (leftIds != rightIds) return listOf("entity sets differ: $leftIds vs $rightIds")
        for (netId in leftIds) {
            val leftRow = left.rowOf(netId)
            val rightRow = right.rowOf(netId)
            for (component in 0 until registry.size) {
                val leftSlot = left.slotOf(leftRow, component)
                val rightSlot = right.slotOf(rightRow, component)
                if (leftSlot == ReplicaStore.ABSENT && rightSlot == ReplicaStore.ABSENT) continue
                val schema = registry.schemaAt(component)
                if (leftSlot == ReplicaStore.ABSENT || rightSlot == ReplicaStore.ABSENT) {
                    problems += "$netId.${schema.typeName}: present on one client only"
                    continue
                }
                val leftStore = left.storeAt(component)
                val rightStore = right.storeAt(component)
                for (field in 0 until schema.fieldCount) {
                    if (leftStore.fieldEquals(leftSlot, rightStore, rightSlot, field, FieldComparison.Bitwise)) {
                        continue
                    }
                    problems += "$netId.${schema.typeName}.${schema.nameOf(field)}: " +
                        "${leftStore.valueAt(leftSlot, field)} vs ${rightStore.valueAt(rightSlot, field)}"
                }
            }
        }
        return problems
    }

    // --- claim 2: the mover is bit-identical across the wire -----------------------------------

    /**
     * One side's movement: its own mover, its own state, its own geometry.
     *
     * Built twice from the same numbers rather than shared, because a shared `StaticCollision`
     * or a shared `MoverState` would make the parity assertion tautological - it would be
     * comparing an object with itself.
     */
    private class MoverPeer {
        val mover: CharacterMover = CharacterMover()
        val state: MoverState = MoverState(x = 0.5f, y = 2f)
        val config: MoverConfig = MoverConfig()
        val intent: MoveIntent = MoveIntent()
        val geometry: StaticCollision = StaticCollision.Builder(initialCapacity = 16)
            .box(-4f, -1f, 12f, 0f)
            .box(3f, 0f, 4f, 0.25f)
            .box(7.5f, 0f, 8f, 1.5f)
            .build()

        fun step(command: MoveInput, dt: Float) {
            intent.move = command.moveX
            intent.jump = command.buttons and 1 != 0
            mover.move(state, intent, config, geometry, dt)
        }

        fun bits(): List<Int> = listOf(
            state.x.toRawBits(),
            state.y.toRawBits(),
            state.velocityX.toRawBits(),
            state.velocityY.toRawBits(),
            if (state.grounded) 1 else 0,
            state.groundNormalX.toRawBits(),
            state.groundNormalY.toRawBits(),
        )
    }

    @Test
    fun `one scripted input sequence drives the server and client movers to bit-identical state`() {
        val clock = ManualClock()
        val network = LoopbackNetwork(clock)
        val clientLink = network.transportFor(PeerId.client(1))
        val serverLink = network.transportFor(PeerId.SERVER)

        val client = MoverPeer()
        val server = MoverPeer()
        val ring = InputRing()
        val jitter = JitterBuffer()
        val scratch = ByteArray(LoopbackNetwork.DEFAULT_MTU)
        val dt = 1f / 60f

        // The script is deliberately not smooth: it reverses, it jumps into the low step, it
        // walks off a ledge and it holds still. Each of those is a branch in CharacterMover -
        // the substep cap, the depenetration loop, the step-down probe, the grounded latch - and
        // a parity test over a straight line would exercise none of them.
        fun scripted(tick: Int): Pair<Float, Int> = when {
            tick < 20 -> 1f to 0
            tick < 30 -> 1f to 1
            tick < 55 -> 0.6f to 0
            tick < 70 -> -1f to 0
            tick < 85 -> 0f to 0
            else -> 0.85f to (if (tick % 17 == 0) 1 else 0)
        }

        // The client's mover state after each command it applied, indexed by sequence number.
        // The server is behind the client by the jitter buffer's target depth - which is what a
        // jitter buffer *is* - so parity is per command, not per tick. Comparing "both at tick
        // 40" would be comparing different amounts of simulation and would fail for a correct
        // implementation, which is the sort of test that gets its assertion loosened.
        val clientBits = ArrayList<List<Int>>()
        val received = ArrayList<MoveInput>()
        var appliedByServer = 0
        val sink = DatagramSink { _, buffer, offset, length ->
            val reader = BitBufferReader(buffer, offset, length)
            repeat(reader.readVarInt()) { jitter.offer(MoveInput.read(reader)) }
        }

        for (tickIndex in 0 until 120) {
            val tick = Tick(tickIndex.toLong())
            val scriptedInput = scripted(tickIndex)

            // The client quantises *before* it predicts. That is the whole discipline: a client
            // must simulate the number the server will decode, not the number the stick
            // produced. Predicting on the raw float while sending the quantised one is a desync
            // no reconciliation removes, because it recurs on every tick.
            val raw = MoveInput(tickIndex, tick, scriptedInput.first, 0f, 0f, scriptedInput.second)
            val writer = BitBufferWriter(ByteArray(32))
            raw.write(writer)
            val sent = MoveInput.read(writer.toReader())

            ring.push(sent)
            val packet = BitBufferWriter(scratch)
            ring.write(packet)
            clientLink.send(PeerId.SERVER, scratch, 0, packet.byteLength)

            client.step(sent, dt)
            clientBits += client.bits()

            serverLink.poll(sink)
            val consumed = jitter.consume(tick)
            if (consumed != null) {
                received += consumed
                // Checked, not assumed. The client predicted on `sent`; this is the object the
                // server actually simulates. Were quantisation not idempotent the two would
                // differ and every assertion below would be measuring the wrong thing.
                assertEquals(
                    appliedByServer,
                    consumed.seq,
                    "the server consumed commands out of order at $tick",
                )
                server.step(consumed, dt)
                assertEquals(
                    clientBits[appliedByServer],
                    server.bits(),
                    // The client's *live* state is further ahead than the compared one - the
                    // server is behind by the buffer depth - so naming it here would print two
                    // numbers that are allowed to differ and send the reader hunting a
                    // non-existent bug. The server's state is the one being compared.
                    "the movers diverged after command ${consumed.seq} (raw float bits); " +
                        "server is ${server.state}",
                )
                appliedByServer++
            }
            clock.advance()
        }

        // The buffer holds `targetDepth` commands back and never starved, so the server is
        // exactly that far behind and every command it took was a real one rather than a repeat.
        assertEquals(0L, jitter.starvations, "the server repeated an input, so parity is not 1:1")
        assertEquals(120 - jitter.targetDepth + 1, appliedByServer, "the server fell further behind than the buffer")
        assertEquals(appliedByServer, received.size)
        // The script has to have moved something, or bit-identical is bit-identical nothing.
        assertTrue(server.state.x > 1f, "the mover never travelled; x is ${server.state.x}")
        assertTrue(received.any { it.buttons and 1 != 0 }, "the jump branch was never taken")
    }
}
