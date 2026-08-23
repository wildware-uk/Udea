package dev.wildware.udea.agent.host

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The launcher seam, through a real JVM: the `-D` arguments `:udea-net` will pass produce an
 * instance whose registry entry and `/health` already carry the right role and session.
 *
 * ## Why this needs a child process
 *
 * Every other session test injects the property lookup, which is right for testing precedence and
 * wrong for testing the thing that actually goes wrong in a launcher: a property name that does
 * not match, a value that never reaches `System.getProperty`, or an ordering in which the entry is
 * written before the identity is known. None of those are visible with an injected map. So this
 * one spawns a JVM with the exact list [SessionIdentity.jvmArguments] returns, and reads the
 * entry the child wrote.
 *
 * The child writes its entry into a temp directory of the test's, via
 * `-Dudea.agent.instances`, so this cannot touch a developer's real `~/.game-bridge/instances`.
 */
class SessionPropertyProcessTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun `a JVM launched with the session arguments advertises the role and session it was given`() {
        val entries = temp.resolve("instances")
        val arguments = SessionIdentity.jvmArguments(SessionId("s-7f3a"), InstanceRole.Client)
        val log = temp.resolve("probe.log")
        // The child copies its live entry here before shutting down. It has to: `AgentHost.stop`
        // withdraws the entry, and a shutdown hook does the same on exit, so by the time the
        // parent could look, a well-behaved instance has correctly cleaned up after itself.
        // Copying while the host is still serving is what keeps this an assertion about the file
        // the real `AgentRegistry` wrote rather than about a string the probe printed.
        val copy = temp.resolve("entry-copy.json")

        val javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command = buildList {
            add(javaBinary)
            add("-cp")
            add(System.getProperty("java.class.path"))
            addAll(arguments)
            add("-D${AgentRegistry.DIRECTORY_PROPERTY}=$entries")
            add("-D${SessionProbe.ENTRY_COPY_PROPERTY}=$copy")
            add(SessionProbe::class.java.name)
        }
        val process = ProbeProcess(command, log)

        try {
            val exited = process.waitFor(TIMEOUT_SECONDS)
            val output = if (Files.exists(log)) log.readText().trim() else ""
            assertTrue(exited, "the probe JVM never exited; output:\n$output")
            assertEquals(0, process.exitValue, "probe output:\n$output")

            assertTrue(
                Files.exists(copy),
                "the child wrote no registry entry to copy; output:\n$output",
            )
            val json = copy.readText()
            assertContains(json, """"role":"client"""", message = "the entry the child wrote:\n$json")
            assertContains(json, """"sessionId":"s-7f3a"""", message = "the entry the child wrote:\n$json")

            // The child printed the `/health` it served itself, from its own port, before exiting.
            // Both documents come from one `SessionIdentity`, so they cannot disagree - and this
            // is where that would show up if they ever could.
            assertContains(output, """"role":"client"""")
            assertContains(output, """"sessionId":"s-7f3a"""")

            val port = json.substringAfter(""""port":""").substringBefore(',').toInt()
            assertTrue(port > 0, "the entry names port $port, which was never bound")
        } finally {
            process.destroy()
        }
    }

    /** A `ProcessBuilder` run with its output on a file, so a full pipe cannot wedge the child. */
    private class ProbeProcess(command: List<String>, log: Path) {

        private val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start()

        fun waitFor(seconds: Long): Boolean = process.waitFor(seconds, TimeUnit.SECONDS)

        val exitValue: Int get() = process.exitValue()

        fun destroy() {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 60L
    }
}

/**
 * Starts a host the way a launched peer does - identity resolved from real system properties,
 * before the port binds - prints its own `/health`, and returns.
 *
 * It resolves through [SessionIdentity.resolve] with **no injected lookup**, which is the whole
 * point: this is the path a peer spawned by `net.start_client` takes.
 */
internal object SessionProbe {

    /** Where to copy the live registry entry, so the parent can read it after this JVM is gone. */
    const val ENTRY_COPY_PROPERTY: String = "udea.probe.entryCopy"

    @JvmStatic
    fun main(args: Array<String>) {
        val registry = AgentRegistry()
        val host = AgentHost.start(
            dev.wildware.udea.agent.AgentBridge(),
            AgentHostConfig(port = 0, session = SessionIdentity.resolve(), registry = registry),
        )
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://${AgentHost.LOOPBACK}:${host.port}/health"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        println(response.body())

        // Copied while the host is still serving: `stop` withdraws the entry, which is correct
        // behaviour and is exactly what leaves the parent nothing to read.
        val entry = checkNotNull(registry.entry) { "the probe advertised nothing" }
        val destination = Path.of(checkNotNull(System.getProperty(ENTRY_COPY_PROPERTY)))
        Files.copy(entry, destination)

        host.stop()
    }
}
