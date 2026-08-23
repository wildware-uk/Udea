package dev.wildware.udea.agent.host

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every thread the HTTP executor produces is a daemon, and a JVM with the server running still
 * exits when the main thread returns.
 *
 * Not stylistic. `HttpServer` with `executor = null` gets its own **non-daemon** dispatch thread,
 * and that default wedged six automated sessions in the reference implementation: the game died,
 * the main thread returned, and the JVM stayed alive holding the port behind a `/health` that
 * cheerfully answered `ok:true` for a world that no longer existed. An agent forbidden from
 * killing processes then has no way out at all, and the port is held until a human logs in.
 */
class AgentHostThreadsTest {

    @org.junit.jupiter.api.io.TempDir
    lateinit var temp: java.nio.file.Path

    private val log: java.nio.file.Path get() = temp.resolve("probe.log")

    @Test
    fun `every http thread is a daemon`() {
        HostHarness().use { harness ->
            // Several concurrent requests, so the cached pool actually creates threads.
            val callers = (1..8).map {
                Thread { repeat(4) { harness.get("/health") } }.apply { start() }
            }
            callers.forEach { it.join() }

            val httpThreads = Thread.getAllStackTraces().keys.filter { it.name == "udea-agent-http" }
            assertTrue(httpThreads.isNotEmpty(), "the executor never created a named thread")
            httpThreads.forEach {
                assertTrue(it.isDaemon, "${it.name} is not a daemon; it can outlive the game")
            }
        }
    }

    /**
     * The dispatcher too - the half a daemon executor does not reach.
     *
     * `ServerImpl.start()` creates `HTTP-Dispatcher` itself, so it inherits its daemon flag from
     * whoever called `start()`. This assertion is the one that fails if somebody ever "simplifies"
     * `AgentHost.startOnADaemonThread` back into a plain `server.start()`.
     */
    @Test
    fun `the servers own dispatcher thread is a daemon`() {
        HostHarness().use { harness ->
            harness.get("/health")

            val dispatchers = Thread.getAllStackTraces().keys.filter { it.name.contains("HTTP-Dispatcher") }
            assertTrue(dispatchers.isNotEmpty(), "HttpServer always runs a dispatcher thread")
            dispatchers.forEach {
                assertTrue(
                    it.isDaemon,
                    "${it.name} is not a daemon: it will hold the JVM and the port open after the " +
                        "game dies, which is exactly the wedge AgentThreads documents",
                )
            }
        }
    }

    /**
     * The consequence, proved end to end: a child JVM that starts a host and returns from `main`
     * exits on its own.
     *
     * A thread-property assertion alone would pass on a server whose *dispatch* thread came from
     * somewhere else, which is exactly the shape of the original defect. So this one starts a real
     * JVM, serves a real request from it, and waits for it to die of its own accord.
     */
    @Test
    fun `a JVM running the server exits when main returns`() {
        val javaBinary = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(
            javaBinary,
            "-cp",
            System.getProperty("java.class.path"),
            DaemonExitProbe::class.java.name,
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start()

        // Redirected to a file rather than read after `waitFor`: a child that filled the pipe
        // buffer would block on its own `println` and never exit, and the test would report a
        // daemon-thread failure that was really a plumbing one.
        val exited = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val output = java.nio.file.Files.readString(log).trim()
        if (!exited) {
            process.destroyForcibly()
            throw AssertionError(
                "the probe JVM was still alive ${PROBE_TIMEOUT_SECONDS}s after main returned: a " +
                    "non-daemon thread is holding it open. Output:\n$output",
            )
        }
        assertEquals(0, process.exitValue(), "probe output:\n$output")
        assertTrue(output.contains("served"), "the probe never served a request; output:\n$output")
    }

    private companion object {
        const val PROBE_TIMEOUT_SECONDS = 30L
    }
}

/**
 * Starts a host, proves it answers, and returns from `main` without stopping it.
 *
 * The point is what happens next: nothing. If any thread this module creates were non-daemon, this
 * process would hang here forever, which is precisely the failure being guarded against.
 */
internal object DaemonExitProbe {

    @JvmStatic
    fun main(args: Array<String>) {
        val host = AgentHost.start(
            dev.wildware.udea.agent.AgentBridge(),
            AgentHostConfig(port = 0, registry = HostHarness.noRegistry()),
        )
        // A raw socket rather than `HttpClient`, deliberately: `HttpClient` runs threads of its own
        // whose lifetime has varied between JDK releases, and a probe about *this* module's threads
        // must not be able to fail because of somebody else's.
        val body = java.net.Socket(AgentHost.LOOPBACK, host.port).use { socket ->
            socket.getOutputStream().write(
                "GET /health HTTP/1.0\r\nHost: ${AgentHost.LOOPBACK}\r\n\r\n".toByteArray(),
            )
            socket.getInputStream().readAllBytes().decodeToString()
        }
        check(body.contains("\"ok\":true")) { "unexpected health response: $body" }
        println("served")
        // Deliberately no host.stop(): the daemon threads have to be what lets this JVM exit.
    }
}
