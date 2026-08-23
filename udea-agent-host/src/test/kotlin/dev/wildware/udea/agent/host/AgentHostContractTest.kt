package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.core.host.RenderMode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The four contract endpoints, asserted against the shapes `game-bridge-mcp`'s `GameClient`
 * expects (`README.md` §1).
 *
 * Shape assertions are on the raw body rather than through a JSON parser on purpose: this module
 * ships no parser, the bridge reads these documents with `JSON.parse` and field names, and a test
 * that re-serialised through a model of its own would pass on a document the bridge could not
 * read.
 */
class AgentHostContractTest {

    @Test
    fun `health carries ok frame tick paused and renderMode`() {
        HostHarness(mode = RenderMode.Offscreen).use { harness ->
            harness.bridge.advanceFrame()
            harness.bridge.publishTick(412)
            val response = harness.get("/health")

            assertEquals(200, response.statusCode())
            assertEquals(
                """{"ok":true,"frame":1,"tick":412,"paused":false,"renderMode":"Offscreen"}""",
                response.body(),
            )
        }
    }

    /**
     * `Headless` in CI, and the reason `/health` reports the mode at all: an agent has to know
     * whether the render toolset is live *before* calling one of its tools.
     */
    @Test
    fun `health reports the mode the process was started in`() {
        HostHarness(mode = RenderMode.Headless).use { harness ->
            assertContains(harness.get("/health").body(), """"renderMode":"Headless"""")
        }
        HostHarness(mode = RenderMode.Windowed).use { harness ->
            assertContains(harness.get("/health").body(), """"renderMode":"Windowed"""")
        }
    }

    /**
     * The bridge reads a decreasing `frame` as "a different process is answering on this port" and
     * drops its cached manifest. That is only sound if the counter never goes backwards for an
     * honest reason, so 1000 reads across a running counter must be non-decreasing.
     */
    @Test
    fun `frame never decreases across a thousand reads`() {
        HostHarness().use { harness ->
            val ticker = Thread {
                repeat(2_000) { harness.bridge.advanceFrame() }
            }
            ticker.isDaemon = true
            ticker.start()
            var previous = -1L
            repeat(1_000) {
                val body = harness.get("/health").body()
                val frame = body.substringAfter("\"frame\":").substringBefore(',').toLong()
                assertTrue(frame >= previous, "frame went backwards: $previous then $frame")
                previous = frame
            }
            ticker.join()
        }
    }

    @Test
    fun `state serves the published digest verbatim`() {
        HostHarness().use { harness ->
            assertEquals(AgentBridge.NOT_READY, harness.get("/state").body())
            harness.bridge.publish("""{"frame":9,"game":{"score":1280}}""")
            assertEquals("""{"frame":9,"game":{"score":1280}}""", harness.get("/state").body())
        }
    }

    @Test
    fun `command queues and answers with the id to poll`() {
        HostHarness().use { harness ->
            val response = harness.get("/command?cmd=spawn&type=cherry&x=-1.5")

            assertEquals(200, response.statusCode())
            assertContains(response.body(), """"accepted":true""")
            assertContains(response.body(), """"commandId":""")
            assertContains(response.body(), """"frame":""")

            val drained = ArrayList<AgentCommand>()
            assertEquals(1, harness.bridge.drain(drained))
            assertEquals("spawn", drained[0].name)
            assertEquals(mapOf("type" to "cherry", "x" to "-1.5"), drained[0].args)
        }
    }

    /**
     * The whole reason the command is keyed `cmd`: a command that takes a `name` argument of its
     * own must still be routable. With the contract's rejected spelling this test is unwritable.
     */
    @Test
    fun `a name argument does not overwrite the command`() {
        HostHarness().use { harness ->
            harness.get("/command?cmd=follow_entity&name=hero")

            val drained = ArrayList<AgentCommand>()
            harness.bridge.drain(drained)
            assertEquals("follow_entity", drained.single().name)
            assertEquals("hero", drained.single().args["name"])
        }
    }

    @Test
    fun `command without cmd is a 400 with a structured body`() {
        HostHarness().use { harness ->
            val response = harness.get("/command?type=cherry")

            assertEquals(400, response.statusCode())
            assertContains(response.body(), """"accepted":false""")
            assertContains(response.body(), """"error":"missing_cmd"""")
            // Naming what did arrive is what turns this from "wrong" into "you sent `type`".
            assertContains(response.body(), "type")
            assertEquals(0, harness.bridge.pendingCommands)
        }
    }

    /**
     * A full queue is HTTP **200** with `accepted:false`. A 5xx would be read by the bridge as a
     * sick port and reported to the agent as an offline game; the port is healthy and the
     * simulation is behind, which is a different problem with a different fix.
     */
    @Test
    fun `an overflowing queue answers queue_full with status 200`() {
        HostHarness(bridge = AgentBridge(queueCapacity = 256)).use { harness ->
            var rejections = 0
            repeat(300) {
                val response = harness.get("/command?cmd=noop")
                assertEquals(200, response.statusCode(), "a rejection must not be an HTTP error")
                if (response.body().contains(""""error":"queue_full"""")) {
                    rejections++
                    assertContains(response.body(), """"accepted":false""")
                }
            }
            assertEquals(44, rejections, "300 submissions into a queue of 256, nothing draining")
            assertEquals(256, harness.bridge.pendingCommands)
        }
    }

    @Test
    fun `tools 404s with a JSON body when no manifest is wired`() {
        HostHarness().use { harness ->
            val response = harness.get("/tools")

            // A 404 here is survivable by contract: the bridge falls back to its built-in manifest
            // and reports the instance as live-no-manifest.
            assertEquals(404, response.statusCode())
            assertContains(response.body(), """"error":"no_manifest"""")
        }
    }

    @Test
    fun `tools serves the manifest when one is wired`() {
        val manifest = ToolManifest.of(GameIdentity("Test Game", "1.2.3"), AgentHostTools.tools)
        HostHarness(manifest = manifest).use { harness ->
            val response = harness.get("/tools")

            assertEquals(200, response.statusCode())
            assertContains(response.body(), """"name":"Test Game"""")
            assertContains(response.body(), """"protocol":1""")
            assertContains(response.body(), """"name":"render.compare_artifacts"""")
        }
    }

    @Test
    fun `paused is read on every health rather than captured at start`() {
        var paused = false
        HostHarness(paused = { paused }).use { harness ->
            assertContains(harness.get("/health").body(), """"paused":false""")
            paused = true
            assertContains(harness.get("/health").body(), """"paused":true""")
        }
    }

    @Test
    fun `a non-GET request is refused rather than silently treated as a GET`() {
        HostHarness().use { harness ->
            val response = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://${AgentHost.LOOPBACK}:${harness.port}/command?cmd=noop"))
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString(),
            )

            assertEquals(405, response.statusCode())
            assertEquals(0, harness.bridge.pendingCommands, "a POST must not queue a command")
        }
    }

    @Test
    fun `stop is idempotent and leaves the port closed`() {
        val harness = HostHarness()
        val port = harness.port
        harness.host.stop()
        harness.host.stop()

        assertFalse(harness.host.isRunning)
        java.net.ServerSocket().use { socket ->
            // Binds only if the host genuinely let the port go.
            socket.reuseAddress = true
            socket.bind(java.net.InetSocketAddress(AgentHost.LOOPBACK, port))
        }
    }
}
