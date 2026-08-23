package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.Json
import dev.wildware.udea.net.bits.BitBufferReader
import dev.wildware.udea.net.bits.BitBufferWriter
import dev.wildware.udea.net.transport.DatagramSink
import dev.wildware.udea.net.transport.LoopbackNetwork
import dev.wildware.udea.net.transport.LoopbackTransport
import dev.wildware.udea.net.transport.ManualClock
import dev.wildware.udea.net.transport.PeerId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole point of the issue, driven: **input on a client port, authoritative state on the
 * server port, in one session, over HTTP.**
 *
 * ## What is real here and what is not - read this before trusting the test
 *
 * Real: two `AgentHost`s on two ephemeral loopback ports, one session id, the roles `server` and
 * `client`, every call made over HTTP through the shipped endpoints, the command queue, and the
 * published `/state` document.
 *
 * The transport is real too now, and this is the line that changed. It used to be an in-process
 * double that handed the client's `AgentCommand` object straight to the server's bridge: no
 * serialisation, no MTU, no packet. That version would have passed on the day the wire dropped
 * every datagram, which its own KDoc said out loud. [SessionLink] below is `:udea-net`'s
 * `LoopbackTransport` over a `LoopbackNetwork`: the input is written into a bit stream, handed
 * to `Transport.send`, carried as a pooled datagram, delivered by `poll`, and decoded on the
 * far side. Two ints crossing a real wire format, not an object reference crossing a method
 * call - which is what makes [SessionLink.bytesCarried] a number worth asserting on.
 *
 * **Still not real:** there is no `net.start_host` / `net.spawn_session` tool, so a launcher
 * cannot yet start this pair from the agent surface; and `LoopbackTransport` is in-memory, so no
 * socket is bound and no UDP is spoken. What this proves is that the session shape and the wire
 * fit together - not that a datagram left the machine.
 */
class SessionEndToEndTest {

    private val session = SessionId("s-7f3a")

    @Test
    fun `an agent sends input on the client port and reads the result on the server port`() {
        val serverBridge = AgentBridge()
        val clientBridge = AgentBridge()
        val world = AuthoritativeWorld()
        val link = SessionLink(serverBridge)

        val server = HostHarness(
            bridge = serverBridge,
            session = SessionIdentity(InstanceRole.Server, session),
        )
        val client = HostHarness(
            bridge = clientBridge,
            session = SessionIdentity(InstanceRole.Client, session),
        )

        server.use {
            client.use {
                // One agent, two ports, one session. It orients itself first - which is what
                // `agent_session` exists for - and only then decides where to send input.
                val serverRole = roleOf(server.get("/health").body())
                val clientRole = roleOf(client.get("/health").body())
                assertEquals("server", serverRole)
                assertEquals("client", clientRole)
                assertEquals(
                    sessionOf(server.get("/health").body()),
                    sessionOf(client.get("/health").body()),
                    "the two ports are not in one session, so an agent cannot pair them",
                )

                // Input goes to the *client*, as a player's would.
                val accepted = client.get("/command?cmd=move&dx=3&dy=-1")
                assertEquals(200, accepted.statusCode())
                assertContains(accepted.body(), """"accepted":true""")

                // The client's simulation drains its queue and forwards to the server.
                pumpClient(clientBridge, link)
                // The server's simulation drains what arrived and publishes its state.
                pumpServer(serverBridge, world)

                // The assertion is made on the *server's* port: the authoritative answer.
                val state = server.get("/state").body()
                assertEquals("""{"tick":1,"x":3,"y":-1,"applied":1}""", state)

                // A second input, so this is a stream and not one lucky call.
                client.get("/command?cmd=move&dx=-5&dy=4")
                pumpClient(clientBridge, link)
                pumpServer(serverBridge, world)
                assertEquals("""{"tick":2,"x":-2,"y":3,"applied":2}""", server.get("/state").body())

                // The link carried bytes, not object references. Four bytes an axis,
                // two axes, two inputs. Were this still the in-process double it would
                // read zero and every assertion above would pass regardless - which is
                // exactly what was wrong with the version it replaces.
                assertEquals(16L, link.bytesCarried, "no payload crossed the transport")
            }
        }
    }

    @Test
    fun `the client's own state is not the authoritative one`() {
        // The reason an agent needs both ports rather than either. The client publishes what it
        // predicted; only the server publishes what happened. A test that read the client's
        // `/state` and called it authoritative would pass while proving nothing about a desync.
        val serverBridge = AgentBridge()
        val clientBridge = AgentBridge()
        val world = AuthoritativeWorld()
        val link = SessionLink(serverBridge)

        HostHarness(bridge = serverBridge, session = SessionIdentity(InstanceRole.Server, session)).use { server ->
            HostHarness(bridge = clientBridge, session = SessionIdentity(InstanceRole.Client, session)).use { client ->
                client.get("/command?cmd=move&dx=3&dy=0")
                // The client predicts a *different* answer - a mispredicting client is the case
                // the whole session-grouping feature exists to let an agent debug.
                clientBridge.publish("""{"tick":1,"x":99,"y":0,"predicted":true}""")
                pumpClient(clientBridge, link)
                pumpServer(serverBridge, world)

                assertContains(client.get("/state").body(), """"x":99""")
                assertContains(server.get("/state").body(), """"x":3""")
                assertTrue(
                    client.get("/state").body() != server.get("/state").body(),
                    "the two ports published the same document, so the test double is not " +
                        "modelling two ends at all",
                )
            }
        }
    }

    /** Drains the client's queue and hands every command to the link, as a client tick would. */
    private fun pumpClient(bridge: AgentBridge, link: SessionLink) {
        val drained = ArrayList<AgentCommand>()
        bridge.drain(drained)
        for (command in drained) {
            link.send(command)
            bridge.complete(command.id, dev.wildware.udea.agent.AgentResult.EMPTY)
        }
        link.deliver()
    }

    /** Drains what arrived on the server, applies it, and publishes the authoritative digest. */
    private fun pumpServer(bridge: AgentBridge, world: AuthoritativeWorld) {
        val drained = ArrayList<AgentCommand>()
        bridge.drain(drained)
        for (command in drained) {
            world.apply(command)
            bridge.complete(command.id, dev.wildware.udea.agent.AgentResult.EMPTY)
        }
        bridge.publishTick(world.tick)
        bridge.publish(world.digest())
    }

    private fun roleOf(health: String): String =
        health.substringAfter(""""role":"""").substringBefore('"')

    private fun sessionOf(health: String): String =
        health.substringAfter(""""sessionId":"""").substringBefore('"')

    /**
     * The client-to-server link, over `:udea-net`'s real transport.
     *
     * [send] writes the two axes into a bit stream and hands the bytes to `Transport.send`;
     * [deliver] polls the server's endpoint and rebuilds an `AgentCommand` from the payload. The
     * client's `AgentCommand` object never reaches the server - only its bytes do, through a
     * pooled datagram bounded by the same MTU a UDP socket would be.
     *
     * A fixed 32-bit pair rather than a `MoveInput`: this session carries integer displacements
     * (`dx=3&dy=-1`), and `MoveInput`'s axes are eight-bit quantised over -1..1, so routing them
     * through it would round every input to zero and the test would end up asserting on the
     * rounding instead of on the link.
     */
    private class SessionLink(private val server: AgentBridge) {

        private val clock = ManualClock()
        private val network = LoopbackNetwork(clock)
        private val clientLink: LoopbackTransport = network.transportFor(PeerId.client(1))
        private val serverLink: LoopbackTransport = network.transportFor(PeerId.SERVER)
        private val scratch = ByteArray(LoopbackNetwork.DEFAULT_MTU)

        /** Payload bytes this link has actually carried. Zero means nothing was serialised. */
        var bytesCarried: Long = 0L
            private set

        private val sink = DatagramSink { _, buffer, offset, length ->
            val reader = BitBufferReader(buffer, offset, length)
            val dx = reader.readInt()
            val dy = reader.readInt()
            bytesCarried += length.toLong()
            server.submit(AgentCommand("move", mapOf("dx" to dx.toString(), "dy" to dy.toString())))
        }

        /** Serialises [command] and sends it. Nothing arrives until [deliver]. */
        fun send(command: AgentCommand) {
            require(command.name == "move") { "the client tried to send ${command.name}" }
            val writer = BitBufferWriter(scratch)
            writer.writeInt(command.args.getValue("dx").toInt())
            writer.writeInt(command.args.getValue("dy").toInt())
            clientLink.send(PeerId.SERVER, scratch, 0, writer.byteLength)
        }

        /** Delivers everything in flight to the server's queue, as a server tick would. */
        fun deliver() {
            serverLink.poll(sink)
            clock.advance()
        }
    }

    /** The server's authoritative state: a position and a count of the inputs that moved it. */
    private class AuthoritativeWorld {

        var x: Int = 0
            private set

        var y: Int = 0
            private set

        var tick: Long = 0L
            private set

        var applied: Int = 0
            private set

        fun apply(command: AgentCommand) {
            require(command.name == "move") { "the server received ${command.name}" }
            x += command.args.getValue("dx").toInt()
            y += command.args.getValue("dy").toInt()
            applied++
            tick++
        }

        fun digest(): String = Json.render {
            put("tick", tick)
            put("x", x.toLong())
            put("y", y.toLong())
            put("applied", applied.toLong())
        }
    }
}
