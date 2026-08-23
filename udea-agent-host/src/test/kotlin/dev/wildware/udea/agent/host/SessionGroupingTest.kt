package dev.wildware.udea.agent.host

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * One server and two clients, discovered as **one group**: the acceptance criterion of issue #80.
 *
 * ## Three real hosts, three real ports, one shared registry directory
 *
 * Not three mocked configs. Each host binds its own ephemeral port, serves its own `/health`, and
 * writes its own entry into the directory a bridge would read - which is the arrangement a bridge
 * sees and the only one in which "three ports of one match are indistinguishable from three
 * unrelated games" is a claim that can be tested.
 *
 * They are three hosts in this JVM rather than three child processes, and the pids are injected
 * for the same reason: a child process per client would make this a slow, flaky test of
 * `ProcessBuilder`, and the thing under test is the two fields and their ordering, not the JVM
 * launcher. That the properties survive a real `-D` is `SessionIdentityTest`'s round trip, and
 * `SessionPropertyProcessTest` drives it through an actual JVM argument.
 */
class SessionGroupingTest {

    @TempDir
    lateinit var temp: Path

    private val session = SessionId("s-7f3a")

    /** A registry writing into [directory] under a chosen pid, so three hosts get three entries. */
    private fun registry(directory: Path, pid: Long): AgentRegistry = AgentRegistry(
        properties = { key ->
            if (key == AgentRegistry.DIRECTORY_PROPERTY) directory.toString() else null
        },
        environment = { null },
        pid = pid,
    )

    @Test
    fun `a server and two clients answer with one session id and their own roles`() {
        val entries = temp.resolve("instances")
        val server = HostHarness(
            registry = registry(entries, 4001L),
            session = SessionIdentity(InstanceRole.Server, session),
        )
        val firstClient = HostHarness(
            registry = registry(entries, 4002L),
            session = SessionIdentity(InstanceRole.Client, session),
        )
        val secondClient = HostHarness(
            registry = registry(entries, 4003L),
            session = SessionIdentity(InstanceRole.Client, session),
        )

        server.use {
            firstClient.use {
                secondClient.use {
                    val roles = listOf(server to "server", firstClient to "client", secondClient to "client")
                    for ((harness, role) in roles) {
                        val body = harness.get("/health").body()
                        assertContains(body, """"role":"$role"""", message = "wrong role on port ${harness.port}")
                        assertContains(
                            body,
                            """"sessionId":"${session.value}"""",
                            message = "wrong session id on port ${harness.port}",
                        )
                    }

                    // Three distinct ports, or this is one instance answered three times.
                    assertEquals(
                        3,
                        setOf(server.port, firstClient.port, secondClient.port).size,
                        "the three hosts share a port",
                    )
                }
            }
        }
    }

    @Test
    fun `all three registry entries carry the fields, and each names the port it bound`() {
        val entries = temp.resolve("instances")
        val server = HostHarness(
            registry = registry(entries, 4001L),
            session = SessionIdentity(InstanceRole.Server, session),
        )
        val firstClient = HostHarness(
            registry = registry(entries, 4002L),
            session = SessionIdentity(InstanceRole.Client, session),
        )
        val secondClient = HostHarness(
            registry = registry(entries, 4003L),
            session = SessionIdentity(InstanceRole.Client, session),
        )

        server.use {
            firstClient.use {
                secondClient.use {
                    val files = Files.list(entries).use { stream ->
                        stream.toList().associateBy { it.fileName.toString() }
                    }
                    assertEquals(
                        setOf("4001.json", "4002.json", "4003.json"),
                        files.keys,
                        "one entry per instance, named for the pid",
                    )

                    val expected = mapOf(
                        "4001.json" to ("server" to server.port),
                        "4002.json" to ("client" to firstClient.port),
                        "4003.json" to ("client" to secondClient.port),
                    )
                    for ((name, roleAndPort) in expected) {
                        val json = files.getValue(name).readText()
                        assertContains(json, """"role":"${roleAndPort.first}"""", message = name)
                        assertContains(json, """"sessionId":"${session.value}"""", message = name)
                        // The entry names the port the host actually bound - the observable form
                        // of "written after the bind". An entry naming a port nobody claimed is
                        // the failure the contract's write order exists to prevent.
                        assertContains(json, """"port":${roleAndPort.second}""", message = name)
                    }
                }
            }
        }
    }

    @Test
    fun `an entry appears only after its port answers`() {
        // The ordering, driven rather than asserted from the source: for every entry in the
        // directory, the port it names is already serving. `AgentRegistryTest` covers the other
        // half - a bind that throws leaves no entry at all.
        val entries = temp.resolve("instances")
        HostHarness(
            registry = registry(entries, 4001L),
            session = SessionIdentity(InstanceRole.Server, session),
        ).use { harness ->
            val entry = Files.list(entries).use { it.toList() }.single()
            val json = entry.readText()
            assertContains(json, """"port":${harness.port}""")

            // The port named in the entry answers, right now, with the same session and role.
            val live = harness.get("/health").body()
            assertContains(live, """"role":"server"""")
            assertContains(live, """"sessionId":"${session.value}"""")
        }
    }

    @Test
    fun `an instance started with no session property is standalone with a generated id`() {
        // The fourth acceptance criterion. A default `AgentHostConfig` resolves nothing from
        // properties; it takes the same standalone identity a `SessionIdentity.resolve` with no
        // properties produces, which is what the shipped path hands it.
        val entries = temp.resolve("instances")
        val identity = SessionIdentity.resolve({ null }, pid = 4242L)
        HostHarness(registry = registry(entries, 4242L), session = identity).use { harness ->
            val body = harness.get("/health").body()
            assertContains(body, """"role":"standalone"""")
            assertContains(body, """"sessionId":"${identity.sessionId.value}"""")
            assertTrue(
                identity.sessionId.value.startsWith(SessionId.GENERATED_PREFIX),
                "the field must never be missing, so it is generated: got $body",
            )

            val json = Files.list(entries).use { it.toList() }.single().readText()
            assertContains(json, """"role":"standalone"""")
            assertContains(json, """"sessionId":"${identity.sessionId.value}"""")
        }
    }

    @Test
    fun `agent_session reports this instance and the peers it launched`() {
        // What orients an agent that attached mid-session: one call, four answers.
        val peers = SessionPeers()
        peers.record(InstanceRole.Client, 7821, pid = 4002L)
        peers.record(InstanceRole.Client, 7822, pid = 4003L)

        val toolset = AgentSessionToolset(SessionIdentity(InstanceRole.Server, session), 7820, peers)
        val result = toolset.describe()
        val json = (result as dev.wildware.udea.agent.AgentResult.Ok).json

        assertEquals(
            """{"role":"server","sessionId":"s-7f3a","port":7820,""" +
                """"peers":[{"role":"client","port":7821,"pid":4002},""" +
                """{"role":"client","port":7822,"pid":4003}]}""",
            json,
        )
        assertEquals("agent_session", AgentSessionTool.name)
        assertEquals(emptyList(), AgentSessionTool.args, "agent_session takes no arguments")
        assertEquals(AgentSessionToolset::class, AgentSessionTool.owner)
    }

    @Test
    fun `agent_session on an instance that launched nothing reports an empty peer list`() {
        val toolset = AgentSessionToolset(
            SessionIdentity.resolve({ null }, pid = 4242L),
            7830,
            SessionPeers(),
        )
        val json = (toolset.describe() as dev.wildware.udea.agent.AgentResult.Ok).json
        assertContains(json, """"peers":[]""")
        assertContains(json, """"role":"standalone"""")
    }
}
