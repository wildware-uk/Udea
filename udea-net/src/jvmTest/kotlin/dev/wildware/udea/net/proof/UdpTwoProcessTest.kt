package dev.wildware.udea.net.proof

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Phase 4 demo, as a test: two operating-system processes replicating over real UDP.
 *
 * ## Why this cannot be done in one process
 *
 * Everything else in this module runs on one thread with a manual clock, and that is the right
 * default — it is what makes replication tests deterministic. But it cannot show that the
 * transport works when the peers do not share a heap: a shared `ManualClock`, a shared
 * `ByteArray`, a shared classloader, and a `close()` that runs in the same JVM are four ways an
 * in-process test can pass while the real thing does not. So this one forks two JVMs, hands them
 * nothing but a port number, and reads their reports off stdout.
 *
 * ## What it asserts, beyond "it connected"
 *
 * - Both processes derive the same protocol hash from their own registries, independently.
 * - The client's replicated `Mover.x` equals the server tick that produced it, every packet.
 *   The server writes the tick into the field, so any disagreement is a real replication defect
 *   rather than a liveness check dressed up as one.
 * - A client that exits cleanly is reported as [dev.wildware.udea.net.transport.DisconnectReason.RemoteClosed];
 *   a client that is killed outright is reported as `Timeout`, and the server keeps running
 *   through both and accepts the next client into the released slot.
 * - The server's refusal counters are all zero for a well-behaved session, which is what makes
 *   the non-zero ones in `UdpHostileTest` meaningful.
 */
class UdpTwoProcessTest {

    @Test
    fun `two processes replicate over real udp, and the server survives both ways of losing one`() {
        val server = Child("UdpProofServer", listOf(SERVER_TICKS.toString()))
        try {
            val port = server.await("PORT ").removePrefix("PORT ").trim().toInt()
            val serverProto = server.await("PROTO ").removePrefix("PROTO ").trim()
            assertTrue(port in 1..65535, "the server did not bind a usable port: $port")

            // --- a client that plays for a while and then leaves politely ---
            val first = Child("UdpProofClient", listOf(port.toString(), CLIENT_TICKS.toString()))
            val result: String
            try {
                assertEquals(
                    serverProto,
                    first.await("PROTO ").removePrefix("PROTO ").trim(),
                    "the two builds derived different protocol hashes from their own registries",
                )
                val connect = server.await("CONNECT ")
                assertEquals("CONNECT client1", connect.trim())
                first.await("CONNECTED ")
                result = first.await("RESULT ", LONG_WAIT_SECONDS)
                first.await("DONE")
                assertEquals(0, first.process.waitFor(), "the client did not exit cleanly")
            } finally {
                first.kill()
            }

            val fields = parse(result)
            assertEquals(0L, fields.getValue("mismatched"), "replicated state disagreed with the tick that produced it")
            assertTrue(
                fields.getValue("matched") >= MIN_MATCHED,
                "only ${fields["matched"]} packets carried checkable state; result was: $result",
            )
            assertTrue(fields.getValue("sent") > 0L, "the client sent nothing, so it uploaded no input")
            assertTrue(fields.getValue("recv") >= MIN_MATCHED, "the client received almost nothing: $result")

            assertEquals("DISCONNECT client1 RemoteClosed", server.await("DISCONNECT ").trim())

            // --- a client that is killed where it stands ---
            val second = Child("UdpProofClient", listOf(port.toString(), SERVER_TICKS.toString()))
            try {
                second.await("CONNECTED ")
                assertEquals("CONNECT client1", server.await("CONNECT ").trim(), "the slot was not reused")
                second.process.destroyForcibly()
                assertTrue(second.process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS), "the client would not die")

                assertEquals(
                    "DISCONNECT client1 Timeout",
                    server.await("DISCONNECT ", LONG_WAIT_SECONDS).trim(),
                    "the server did not notice a client that stopped answering",
                )
            } finally {
                second.kill()
            }

            server.send("stop")
            val summary = parse(server.await("SUMMARY ", LONG_WAIT_SECONDS))
            val counters = parse(server.await("COUNTERS ", LONG_WAIT_SECONDS))
            server.await("DONE")
            assertEquals(0, server.process.waitFor(), "the server did not exit cleanly")

            assertTrue(summary.getValue("sent") >= MIN_MATCHED, "the server barely sent anything: $summary")
            assertTrue(summary.getValue("recv") > 0L, "the server received no input at all: $summary")
            assertEquals(2L, counters.getValue("completed"), "two handshakes should have completed")
            for (name in CLEAN_SESSION_COUNTERS) {
                assertEquals(0L, counters.getValue(name), "$name moved during a well-behaved session: $counters")
            }
        } finally {
            server.kill()
        }
    }

    /** `name=value` pairs out of a report line, ignoring everything else on it. */
    private fun parse(line: String): Map<String, Long> =
        FIELD.findAll(line).associate { it.groupValues[1] to it.groupValues[2].toLong() }

    /**
     * A forked JVM whose stdout this test reads a line at a time.
     *
     * The classpath goes through the `CLASSPATH` environment variable rather than `-cp`: a
     * Gradle test classpath is long enough to be worth not pushing through a Windows command
     * line, and an environment variable needs no quoting rules to get wrong.
     */
    private class Child(mainClass: String, args: List<String>) {

        val lines = LinkedBlockingQueue<String>()
        val transcript = ArrayList<String>()
        val process: Process

        init {
            val builder = ProcessBuilder(listOf(javaExecutable(), "$PACKAGE.$mainClass") + args)
            builder.environment()["CLASSPATH"] = System.getProperty("java.class.path")
            builder.redirectErrorStream(true)
            process = builder.start()
            thread(isDaemon = true, name = "$mainClass-stdout") {
                process.inputStream.bufferedReader().forEachLine {
                    synchronized(transcript) { transcript += it }
                    lines.put(it)
                }
            }
        }

        /** The next line starting with [prefix], failing with the whole transcript if it never comes. */
        fun await(prefix: String, seconds: Long = WAIT_SECONDS): String {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) {
                    fail("waited ${seconds}s for a line starting \"$prefix\"; got:\n${dump()}")
                }
                val line = lines.poll(remaining, TimeUnit.NANOSECONDS)
                    ?: fail("waited ${seconds}s for a line starting \"$prefix\"; got:\n${dump()}")
                if (line.startsWith(prefix)) return line
            }
        }

        fun send(line: String) {
            process.outputStream.write((line + System.lineSeparator()).toByteArray())
            process.outputStream.flush()
        }

        fun kill() {
            process.destroyForcibly()
        }

        private fun dump(): String = synchronized(transcript) { transcript.joinToString("\n") }

        private fun javaExecutable(): String {
            val bin = File(System.getProperty("java.home"), "bin")
            val windows = File(bin, "java.exe")
            return if (windows.isFile) windows.absolutePath else File(bin, "java").absolutePath
        }
    }

    private companion object {

        const val PACKAGE = "dev.wildware.udea.net.proof"

        /** 30 seconds of server at 60Hz, which outlives both clients with room to spare. */
        const val SERVER_TICKS = 1800

        /** Four seconds of client at 60Hz. */
        const val CLIENT_TICKS = 240

        /**
         * Packets the client must have checked before the run counts.
         *
         * Comfortably under [CLIENT_TICKS] so that JVM startup and the handshake eat into it
         * without making the test flaky, and comfortably over zero so that a client which
         * connected and then received nothing cannot pass.
         */
        const val MIN_MATCHED = 120L

        const val WAIT_SECONDS = 20L

        /** For the waits that include a two-second connection timeout or a whole client run. */
        const val LONG_WAIT_SECONDS = 45L

        /** Every refusal counter that must stay at zero when nothing hostile happened. */
        val CLEAN_SESSION_COUNTERS = listOf(
            "malformed",
            "oversized",
            "unknownConnection",
            "replayed",
            "rateLimited",
            "amplificationBlocked",
            "tokenRejected",
            "tokenExpired",
            "denied",
            "fragmentsTimedOut",
            "fragmentsRefused",
        )

        val FIELD = Regex("""(\w+)=(-?\d+)""")
    }
}
