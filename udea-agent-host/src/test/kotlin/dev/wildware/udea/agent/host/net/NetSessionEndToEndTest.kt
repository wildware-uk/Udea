package dev.wildware.udea.agent.host.net

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentThreads
import dev.wildware.udea.agent.AgentTimings
import dev.wildware.udea.agent.dispatch.AgentRuntime
import dev.wildware.udea.agent.dispatch.ToolIndex
import dev.wildware.udea.agent.harness.SimHarness
import dev.wildware.udea.agent.state.ArchetypeVisitor
import dev.wildware.udea.agent.state.DigestSources
import dev.wildware.udea.agent.state.EntityCensus
import dev.wildware.udea.agent.state.StateDigest
import dev.wildware.udea.agent.host.AgentGameLoop
import dev.wildware.udea.agent.host.AgentHost
import dev.wildware.udea.agent.host.AgentHostConfig
import dev.wildware.udea.agent.host.GameIdentity
import dev.wildware.udea.agent.host.HostHarness
import dev.wildware.udea.agent.host.InstanceRole
import dev.wildware.udea.agent.host.SessionId
import dev.wildware.udea.agent.host.SessionIdentity
import dev.wildware.udea.agent.host.ToolManifest
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The proof.** Through HTTP alone, an agent stands up a server plus two clients, sends
 * movement input on client 1, and reads the moved position back from the **server**.
 *
 * ## What is real here
 *
 * Everything below the HTTP client. A real [AgentHost] on an ephemeral loopback port; a real
 * [AgentGameLoop] on its own thread; the shipped `/command` queue, `SimBarrier` drain,
 * [ToolIndex] dispatch and `/state` digest. The session the tools stand up is `:udea-net`'s:
 * `ReplicationServer` reading baselines out of the same `SnapshotRing` a rewind reads,
 * `ReplicationClient` applying real deltas out of the real self-describing packet format,
 * carried by `SimulatedTransport` whose loss and jitter draws come from the session seed.
 *
 * The test never touches a `NetSession`, a `Transport` or a component. It sends query strings
 * and reads JSON, which is exactly what an agent behind `game-bridge-mcp` can do and nothing
 * more - so a claim this test makes is a claim about the agent surface rather than about a
 * parallel path that resembles it.
 *
 * ## What is not real
 *
 * No socket is bound for the game traffic: the session runs in one JVM against a `ManualClock`.
 * That is a deliberate property rather than a shortfall - it is what makes a 300-tick run at
 * 200ms and 10% loss finish in milliseconds and produce the same bytes every time - but it means
 * this proves the wire format and the authority model, not that a datagram left the machine.
 */
class NetSessionEndToEndTest {

    @Test
    fun `an agent drives a server and two clients over HTTP and reads authority on the server`() {
        NetLiveInstance().use { instance ->
            // The instance says what it is before anything is driven. One call, four answers.
            val health = instance.get("/health").body()
            assertContains(health, """"ok":true""")
            assertContains(health, """"role":"server"""")

            // The tools are discoverable, which is how an agent that has never seen this game
            // learns the session tools exist at all.
            val manifest = instance.get("/tools").body()
            assertContains(manifest, """"name":"net.spawn_session"""")
            assertContains(manifest, """"name":"net.desync_report"""")

            // 1. A server and two clients, in one call, on a fixed seed.
            val spawned = instance.call("net.spawn_session", "clients" to "2", "seed" to "424242")
            assertContains(spawned, """"clients":2""")
            assertContains(spawned, """"peer":"server"""")
            assertContains(spawned, """"peer":"client1"""")
            assertContains(spawned, """"peer":"client2"""")

            // 2. Input on client 1, and nothing at all on client 2.
            assertContains(instance.call("net.input", "client" to "1", "move_x" to "1"), """"ok":true""")

            // 3. One second of simulation. Both ends and both links.
            assertContains(instance.call("net.step", "ticks" to "60"), """"tick":60""")

            // 4. The assertion that is the whole point: read it on the SERVER.
            val serverOne = instance.call("net.server_state", "client" to "1")
            val movedX = numberIn(serverOne, "x")
            assertTrue(
                movedX > MOVED_ENOUGH,
                "client 1 held +x for a second and the server's authoritative x is $movedX; the " +
                    "input did not reach the server, or the server did not act on it",
            )
            assertTrue(
                numberIn(serverOne, "inputsApplied") > 0.0,
                "the server applied no input commands at all",
            )

            // Authority is per client, not per session: client 1's input moved client 1's body
            // and did not move client 2's.
            //
            // "Did not move" and not "is exactly zero", and the difference is a real property of
            // the wire rather than slack in the assertion. `MoveInput.AXIS` is 8 bits over -1..1,
            // which is 255 levels over an even span, so **neutral is not on a level**: a client
            // holding nothing sends the nearest one, and the server applies a genuine but
            // sub-thousandth displacement per tick. That is what a quantised stick does, it is
            // visible here rather than hidden by a deadzone the arena does not have, and it is
            // three orders of magnitude below what a held axis produces - which is the claim.
            val serverTwo = instance.call("net.server_state", "client" to "2")
            val idleX = numberIn(serverTwo, "x")
            assertTrue(
                kotlin.math.abs(idleX) < MOVED_ENOUGH,
                "client 2 held nothing and its body moved to $idleX anyway, so client 1's input " +
                    "is moving somebody else's body",
            )
            assertTrue(
                movedX > kotlin.math.abs(idleX) * SEPARATION,
                "the body of the client that held +x ($movedX) is not clearly apart from the " +
                    "body of the client that held nothing ($idleX)",
            )

            // The client sees the server's answer, which is what makes it an answer and not a
            // prediction: the client never wrote this value, the server did.
            val clientOne = instance.call("net.client_state", "client" to "1")
            assertTrue(
                numberIn(clientOne, "x") > MOVED_ENOUGH,
                "client 1 never received the position the server computed from its own input",
            )

            // 5. Now make the link bad, on purpose, and reproducibly.
            val conditions = instance.call("net.set_conditions", "latency_ms" to "200", "loss" to "0.1")
            assertContains(conditions, """"latencyTicks":12""")
            assertContains(conditions, """"loss":0.1""")

            // 6. Five seconds under 200ms and 10% loss.
            instance.call("net.step", "ticks" to "300")

            // 7. And the desync report, from `:udea-net`'s own DesyncReport.
            val report = instance.call("net.desync_report", "client" to "1")
            assertContains(report, """"converged":true""")
            assertEquals(
                0.0,
                numberIn(report, "fieldCount"),
                "the client did not converge under 200ms and 10% loss; the report is: $report",
            )

            // Loss was actually simulated rather than configured and ignored - otherwise
            // "converged under loss" would be a claim about a perfect link.
            val trafficAfter = instance.call("net.client_state", "client" to "1")
            assertTrue(
                numberIn(trafficAfter, "packetsDropped") > 0.0,
                "no datagram was dropped in 300 ticks at 10% loss, so the link is not lossy " +
                    "and the convergence above proves nothing about loss: $trafficAfter",
            )
        }
    }

    /**
     * The same tools, driven by a **test** through [SimHarness] instead of by an agent through
     * HTTP - and the same answers.
     *
     * This is what "MCP surface + test harness, same code path" (spec 4) has to mean to be worth
     * anything. [SimHarness] holds a bridge and an [AgentRuntime] and has no reference to the
     * tool index at all, so it cannot reach a tool except the way `/command` does: submit,
     * drain onto the barrier, dispatch, answer. A scenario an agent found over HTTP is therefore
     * a test that can be checked in, and this test is the evidence for that rather than the
     * assertion of it.
     */
    @Test
    fun `a test drives the net tools through the same dispatch path an agent does`() {
        val bridge = AgentBridge()
        val host = GameHost(RenderMode.Headless, UdeaGameDef(modules = emptyList()))
        val toolset = NetToolset()
        val tools = ToolIndex.builder().module(NetToolModule).toolset(toolset).build()
        val harness = SimHarness(host, bridge, tools) { }

        assertContains(json(harness.call("net.spawn_session", "clients" to "2", "seed" to "424242")), """"clients":2""")
        harness.call("net.input", "client" to "1", "move_x" to "1")
        harness.call("net.step", "ticks" to "60")

        val viaHarness = json(harness.call("net.server_state", "client" to "1"))
        assertTrue(numberIn(viaHarness, "x") > MOVED_ENOUGH, "the harness path moved nothing: $viaHarness")

        // Byte-identical to what the HTTP path produced from the same seed and the same calls.
        // Not "close enough": the session is seeded and the clock is manual, so two runs of the
        // same script are the same run, and a difference here would mean one of the two paths
        // has a step the other does not.
        NetLiveInstance().use { instance ->
            instance.call("net.spawn_session", "clients" to "2", "seed" to "424242")
            instance.call("net.input", "client" to "1", "move_x" to "1")
            instance.call("net.step", "ticks" to "60")
            val viaHttp = instance.call("net.server_state", "client" to "1")
            assertEquals(
                numberIn(viaHarness, "x"),
                numberIn(viaHttp, "x"),
                "the same script produced different authoritative positions through the test " +
                    "harness and through HTTP, so they are not one code path",
            )
        }
    }

    @Test
    fun `a tool called before a session exists says which call to make instead`() {
        val bridge = AgentBridge()
        val host = GameHost(RenderMode.Headless, UdeaGameDef(modules = emptyList()))
        val tools = ToolIndex.builder().module(NetToolModule).toolset(NetToolset()).build()
        val harness = SimHarness(host, bridge, tools) { }

        val refused = assertIs<AgentResult.Failed>(
            harness.call("net.step", "ticks" to "10"),
            "a process with no session answered ok",
        )
        assertEquals("no_net_session", refused.error.kind.id)
        assertContains(refused.error.message, "net.spawn_session")
    }

    @Test
    fun `a client the session does not have is refused rather than defaulted`() {
        val bridge = AgentBridge()
        val host = GameHost(RenderMode.Headless, UdeaGameDef(modules = emptyList()))
        val tools = ToolIndex.builder().module(NetToolModule).toolset(NetToolset()).build()
        val harness = SimHarness(host, bridge, tools) { }

        harness.call("net.spawn_session", "clients" to "2")
        val refused = assertIs<AgentResult.Failed>(
            harness.call("net.server_state", "client" to "7"),
            "a session of two clients answered for client 7",
        )
        assertEquals("no_such_peer", refused.error.kind.id)
    }

    private fun json(result: AgentResult): String = when (result) {
        is AgentResult.Ok -> result.json
        is AgentResult.Failed -> error("expected a successful result, got $result")
    }

    /**
     * The value of [key] in [document], as a `Double`.
     *
     * A scan rather than a parser because this module ships no JSON reader and pulling one in
     * for four assertions would put a dependency on the test classpath to save four lines. It is
     * deliberately strict: a missing key fails the test naming the document, rather than
     * defaulting to zero and turning "the server never moved" into a pass.
     */
    private fun numberIn(document: String, key: String): Double {
        val at = document.indexOf(""""$key":""")
        check(at >= 0) { "no key '$key' in $document" }
        val from = at + key.length + 3
        val end = document.indexOfFirst(from) { it == ',' || it == '}' }
        return document.substring(from, end).trim().toDoubleOrNull()
            ?: error("'$key' in $document is not a number")
    }

    private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        for (index in from until length) if (predicate(this[index])) return index
        return length
    }

    private companion object {

        /**
         * A second of held input at [NetArena.UNITS_PER_TICK] is six units, less whatever the
         * jitter buffer held back while it filled. Half a unit is comfortably above the noise
         * and comfortably below the answer, so the assertion fails on "nothing moved" and does
         * not fail on "the buffer took three ticks to start draining".
         */
        const val MOVED_ENOUGH: Double = 0.5

        /**
         * How far apart a held axis and a neutral one must land.
         *
         * Fifty times, against a measured ratio nearer three hundred. It is here so that "client
         * 1 moved and client 2 did not" fails if the two ever become comparable - which is what
         * a tool that applied one client's input to every body would look like.
         */
        const val SEPARATION: Double = 50.0
    }
}

/**
 * A whole instance with the `net.*` toolset wired: a real loop thread, a real HTTP surface, and
 * a small client that submits a command and waits for that command's own answer.
 *
 * Its own class rather than a parameter on `LiveInstance`, because the two exist to prove
 * different things and share nothing but their shape: that one is about a process ending itself
 * through `close`, this one is about a session being driven through `/command` and `/state`.
 */
private class NetLiveInstance : AutoCloseable {

    private val bridge = AgentBridge()

    private val host = GameHost(RenderMode.Headless, UdeaGameDef(modules = emptyList()))

    private val toolset = NetToolset()

    private val tools: ToolIndex = ToolIndex.builder()
        .module(NetToolModule)
        .toolset(toolset)
        .build()

    private val digest = StateDigest(
        bridge = bridge,
        sources = DigestSources(entities = EmptyCensus),
        timings = AgentTimings(),
    )

    private val loop = AgentGameLoop(host, AgentRuntime(bridge, tools, host.world, host.ctx, digest))

    private val identity = GameIdentity("udea-net-session", "0.0.1")

    private val agentHost: AgentHost = AgentHost.start(
        bridge,
        AgentHostConfig(
            port = 0,
            identity = identity,
            renderMode = RenderMode.Headless,
            manifest = ToolManifest.of(identity, tools.tools),
            registry = HostHarness.noRegistry(),
            // A server, because that is what an instance holding the authoritative session is,
            // and because `agent_session` and `/health` are how an agent tells this port from a
            // client's without trying something and inferring.
            session = SessionIdentity(InstanceRole.Server, SessionId("s-net")),
        ),
    )

    private val loopThread: Thread =
        AgentThreads.daemonFactory("udea-net-session-instance").newThread { loop.run() }

    init {
        digest.publish()
        loopThread.start()
    }

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_SECONDS))
        .build()

    fun get(path: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://${AgentHost.LOOPBACK}:${agentHost.port}$path"))
            .timeout(Duration.ofSeconds(REQUEST_SECONDS))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    /**
     * Submits a tool call and returns **that call's** answer, read out of `/state`.
     *
     * Exactly the confirmation loop `game-bridge-mcp` runs: `/command` answers the moment the
     * command is queued, the caller polls `/state` until `completedCommandId` covers the id it
     * was given, and then reads that id out of `commandResults`. Doing it any other way here
     * would be testing a path the bridge does not take.
     */
    fun call(tool: String, vararg args: Pair<String, String>): String {
        val query = buildString {
            append("/command?cmd=").append(tool)
            for ((key, value) in args) append('&').append(key).append('=').append(value)
        }
        val accepted = get(query).body()
        check(""""accepted":true""" in accepted) { "$tool was not accepted: $accepted" }
        val commandId = accepted.substringAfter(""""commandId":""").substringBefore(',').trim()

        val deadline = System.nanoTime() + TIMEOUT_MILLIS * NANOS_PER_MILLI
        var last = ""
        while (System.nanoTime() < deadline) {
            last = get("/state").body()
            val entry = last.indexOf("""{"id":$commandId,""")
            if (entry >= 0) return last.substring(entry, closingBrace(last, entry))
            Thread.sleep(POLL_MILLIS)
        }
        error("$tool (command #$commandId) never appeared in commandResults; last /state was $last")
    }

    /** The index just past the object starting at [from], counting nesting. */
    private fun closingBrace(document: String, from: Int): Int {
        var depth = 0
        for (index in from until document.length) {
            when (document[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return index + 1
                else -> Unit
            }
        }
        return document.length
    }

    override fun close() {
        loop.stop()
        agentHost.stop()
        loopThread.join(JOIN_MILLIS)
    }

    private object EmptyCensus : EntityCensus {
        override val entityCount: Int = 0

        override fun forEachArchetype(visitor: ArchetypeVisitor) = Unit
    }

    private companion object {
        const val CONNECT_SECONDS: Long = 2L
        const val REQUEST_SECONDS: Long = 10L
        const val POLL_MILLIS: Long = 5L

        /** Generous: `net.step(300)` runs 300 ticks of two ends inside one barrier drain. */
        const val TIMEOUT_MILLIS: Long = 30_000L
        const val NANOS_PER_MILLI: Long = 1_000_000L
        const val JOIN_MILLIS: Long = 2_000L
    }
}
