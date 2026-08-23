package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.core.host.RenderMode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/**
 * A host on an OS-assigned port, and a tiny HTTP client for it.
 *
 * Port `0` throughout: a fixed port in a test suite collides with a developer's running game and
 * with the next test, and both failures look like the server being broken.
 */
internal class HostHarness(
    val bridge: AgentBridge = AgentBridge(),
    mode: RenderMode = RenderMode.Headless,
    manifest: ToolManifest? = null,
    artifacts: AgentArtifacts? = null,
    registry: AgentRegistry = noRegistry(),
    paused: () -> Boolean = { false },
    workingDirectory: Path = Path.of("").toAbsolutePath(),
    /**
     * A fixed identity, so tests that assert the exact `/health` document are not asserting this
     * JVM's pid. A real instance generates its own from the pid when nothing supplied one.
     */
    val session: SessionIdentity = SessionIdentity(InstanceRole.Standalone, SessionId("s-test")),
    val peers: SessionPeers = SessionPeers(),
) : AutoCloseable {

    val host: AgentHost = AgentHost.start(
        bridge,
        AgentHostConfig(
            port = 0,
            identity = GameIdentity("Test Game", "1.2.3"),
            renderMode = mode,
            manifest = manifest,
            artifacts = artifacts,
            paused = paused,
            registry = registry,
            workingDirectory = workingDirectory,
            session = session,
            peers = peers,
        ),
    )

    val port: Int get() = host.port

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    /** `GET path` against this host, as text. */
    fun get(path: String): HttpResponse<String> = get(AgentHost.LOOPBACK, path)

    /** `GET path` against [address], which is how the loopback-only claim is tested. */
    fun get(address: String, path: String): HttpResponse<String> =
        client.send(request(address, path), HttpResponse.BodyHandlers.ofString())

    /** `GET path` as bytes, for `/artifact`. */
    fun bytes(path: String): HttpResponse<ByteArray> =
        client.send(request(AgentHost.LOOPBACK, path), HttpResponse.BodyHandlers.ofByteArray())

    private fun request(address: String, path: String): HttpRequest = HttpRequest.newBuilder()
        .uri(URI.create("http://$address:$port$path"))
        .timeout(Duration.ofSeconds(10))
        .GET()
        .build()

    override fun close() {
        host.stop()
    }

    companion object {
        /**
         * A registry pointed at a scratch directory this JVM owns.
         *
         * The tests that are not about the registry must not write into a developer's real
         * `~/.game-bridge/instances`: an entry left there would be reported by `list_instances` as
         * a running game, which is exactly the false positive the contract's reader rules exist to
         * survive, and there is no reason to manufacture more of them.
         */
        fun noRegistry(): AgentRegistry {
            val scratch = java.nio.file.Files.createTempDirectory("udea-agent-host-test")
            scratch.toFile().deleteOnExit()
            return AgentRegistry(
                properties = { key ->
                    if (key == AgentRegistry.DIRECTORY_PROPERTY) scratch.toString() else null
                },
                environment = { null },
            )
        }
    }
}
