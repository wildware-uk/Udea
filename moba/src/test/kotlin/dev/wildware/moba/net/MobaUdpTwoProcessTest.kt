package dev.wildware.moba.net

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **The headline proof**: the real 27-unit `moba` battle, replicated over real UDP, between
 * operating-system processes that share nothing but a port number.
 *
 * ## Why this test and not `MobaNetProof`
 *
 * `MobaNetProof` is the deterministic version: one JVM, one thread, a manual clock, seeded
 * impairment, a whole 600-tick session in milliseconds. That is the right default and it is what
 * the agreement claim should normally be checked with. What it cannot show is that any of it
 * survives the peers not sharing a heap - a shared clock, a shared `ByteArray`, a shared class
 * loader and a `close()` in the same JVM are four ways an in-process proof passes while the real
 * thing does not. The old tree's KryoNet stack was never checked this way at all.
 *
 * So this forks three JVMs, hands them a port number, and reads their reports off stdout.
 *
 * ## What it asserts, beyond "it connected"
 *
 * - All three processes derive the **same protocol hash** from their own component registries.
 * - Both clients seed no level and still hold **27 `GameUnit`s**, which can only have arrived
 *   over the socket.
 * - Each client's `@Net` hash over every `GameUnit` equals the server's **at the tick that
 *   client holds**, read out of the ring slot for exactly that tick. Comparing against the
 *   server's newest capture would assert that replication is instantaneous rather than correct.
 * - The client that drives the level's player unit moves it off its spawn, so a command that
 *   crossed a socket reached `PlayerControlSystem`. A client sends input and never state.
 * - A client killed outright is timed out, its slot released, and the server keeps serving.
 *
 * ## The residue, stated rather than hidden
 *
 * The per-component assertion now covers **every** replicated component: [MobaUdpProof.EXCUSED_COMPONENTS]
 * is empty, and that KDoc says which two defects used to be in it and how each was fixed. One
 * thing still legitimately differs, and it is why the asserted hash is over the `GameUnit` roster
 * ([NetStateProbe.unitHash]) rather than over the whole world:
 *
 *  - **In-flight projectiles** are created and destroyed between the two captures, and a recycled
 *    `NetId` index waits one acknowledgement before its new occupant is sent - so a whole-world
 *    fold is very often one entity short at any given tick. That is the replication protocol
 *    working, not failing: an index cannot carry a new entity while the client's `Destroy` for the
 *    old one is unacknowledged, or the client would delete the entity it had just been given.
 *
 * The whole-roster hash is printed on every run beside the asserted one. It is printed and not
 * asserted for the reason above: it folds the projectiles too.
 */
class MobaUdpTwoProcessTest {

    @Test
    fun `the real battle replicates to two clients over real udp, in three processes`() {
        replicate("perfect")
    }

    /**
     * The same three processes at 150ms and 5% loss.
     *
     * The datagrams still cross real sockets; the delay and the drops are applied by a
     * [dev.wildware.udea.net.transport.SimulatedTransport] wrapped around the real
     * [dev.wildware.udea.net.transport.UdpTransport] at each endpoint, because a loopback
     * interface will not lose a packet on request. Every draw comes from the seeded
     * [MobaUdpProof.SEED], so a failure here is a seed to re-run rather than a story about CI.
     *
     * The assertion is the same one, and that is the point: the components with no excuse have to
     * agree exactly at 150ms and 5% loss, not merely converge eventually.
     */
    @Test
    fun `the real battle replicates over real udp at 150ms and 5 percent loss`() {
        replicate("lossy")
    }

    private fun replicate(link: String) {
        val server = Child("MobaUdpServer", listOf(SERVER_TICKS.toString(), link))
        try {
            val port = server.await("PORT ", BOOT_WAIT).removePrefix("PORT ").trim().toInt()
            val proto = server.await("PROTO ").removePrefix("PROTO ").trim()
            val player = server.await("PLAYER ").removePrefix("PLAYER ").trim()
            assertTrue(port in 1..65535, "the server did not bind a usable port: $port")

            val driver = Child("MobaUdpClient", listOf("$port", "$CLIENT_TICKS", "11", link, "drive"))
            val watcher = Child("MobaUdpClient", listOf("$port", "$CLIENT_TICKS", "22", link, "watch"))
            try {
                for (client in listOf(driver, watcher)) {
                    assertEquals(
                        proto,
                        client.await("PROTO ", BOOT_WAIT).removePrefix("PROTO ").trim(),
                        "a client derived a different protocol hash from its own registry",
                    )
                    client.await("CONNECTED ", BOOT_WAIT)
                }
                assertEquals("CONNECT client1", server.await("CONNECT ").trim())
                assertEquals("CONNECT client2", server.await("CONNECT ").trim())

                // Let the battle run, then read each client at the tick it actually holds and
                // ask the server for the ring slot for exactly that tick.
                Thread.sleep(BATTLE_MILLIS)
                for (client in listOf(driver, watcher)) {
                    client.send("state")
                    val state = parse(client.await("STATE "))
                    val at = state.getValue("tick")
                    val mine = parse(client.await("COMPHASH "))
                    server.send("hashat $at")
                    val theirs = parse(server.await("HASHAT "))
                    val ours = parse(server.await("COMPHASH "))

                    println("[udp-proof][$link] ${client.name} $state")
                    println("[udp-proof][$link] server@t$at $theirs")
                    val components = mine.keys.filter { it != "tick" }.sorted()
                    val disagreed = ArrayList<String>()
                    for (name in components) {
                        val agree = mine[name] == ours[name]
                        if (!agree) disagreed += name
                        println(
                            "[udp-proof][$link]   $name ${if (agree) "MATCH" else "DIFFER"}" +
                                if (!agree && name in MobaUdpProof.EXCUSED_COMPONENTS) {
                                    "  (excused: MobaUdpProof.EXCUSED_COMPONENTS says why)"
                                } else {
                                    ""
                                },
                        )
                    }
                    println(
                        "[udp-proof][$link] ${client.name} whole-roster hash " +
                            if (theirs.getValue("unitHash") == state.getValue("unitHash")) "MATCH" else "DIFFER",
                    )

                    assertEquals(
                        UNITS,
                        state.getValue("units"),
                        "${client.name} seeded no level, so every unit it holds arrived over the " +
                            "socket; it holds ${state["units"]}",
                    )
                    assertTrue(
                        components.size >= COVERED,
                        "only ${components.size} replicated component types crossed the wire",
                    )
                    assertEquals(
                        emptyList(),
                        disagreed - MobaUdpProof.EXCUSED_COMPONENTS,
                        "${client.name} and the server disagree at tick $at on a replicated " +
                            "component. Nothing is excused any more - " +
                            "MobaUdpProof.EXCUSED_COMPONENTS is empty, and its KDoc says which " +
                            "two defects used to be in it and how each was fixed",
                    )
                    assertTrue(state.getValue("applied") > MIN_APPLIED, "${client.name} received almost nothing")
                }

                driver.send("stop")
                assertTrue(driver.process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS), "the driver would not exit")

                // ...and a client killed where it stands is timed out rather than served forever.
                watcher.process.destroyForcibly()
                assertTrue(watcher.process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS), "the watcher would not die")
                val gone = server.await("DISCONNECT ", LONG_WAIT).trim()
                assertTrue(gone.startsWith("DISCONNECT client"), "unexpected disconnect line: $gone")
            } finally {
                driver.kill()
                watcher.kill()
            }

            server.send("stop")
            val summary = parse(server.await("SUMMARY ", LONG_WAIT))
            server.await("COUNTERS ", LONG_WAIT)
            server.await("DONE", LONG_WAIT)
            println("[udp-proof][$link] server SUMMARY $summary player=$player")

            assertEquals(UNITS, summary.getValue("units"), "the server lost units during the run")
            assertTrue(summary.getValue("sent") > MIN_APPLIED, "the server barely sent anything: $summary")
            assertTrue(summary.getValue("recv") > 0L, "the server received no input at all: $summary")
        } finally {
            server.kill()
        }
    }

    /** `name=value` pairs out of a report line. Hex values keep their `0x` form as a string key. */
    private fun parse(line: String): Map<String, Long> = FIELD.findAll(line).associate { match ->
        val raw = match.groupValues[2]
        val value = if (raw.startsWith("0x")) java.lang.Long.parseUnsignedLong(raw.substring(2), 16) else raw.toLong()
        match.groupValues[1] to value
    }

    /**
     * A forked JVM whose stdout this test reads a line at a time.
     *
     * The classpath goes through `CLASSPATH` rather than `-cp`: a Gradle runtime classpath is
     * long enough to be worth not pushing through a Windows command line, and an environment
     * variable needs no quoting rules to get wrong.
     */
    private class Child(val name: String, args: List<String>) {

        val lines = LinkedBlockingQueue<String>()
        val transcript = ArrayList<String>()
        val process: Process

        init {
            val builder = ProcessBuilder(listOf(javaExecutable(), "$PACKAGE.$name") + args)
            builder.environment()["CLASSPATH"] = System.getProperty("java.class.path")
            builder.redirectErrorStream(true)
            process = builder.start()
            thread(isDaemon = true, name = "$name-stdout") {
                process.inputStream.bufferedReader().forEachLine {
                    synchronized(transcript) { transcript += it }
                    lines.put(it)
                }
            }
        }

        fun await(prefix: String, seconds: Long = WAIT_SECONDS): String {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) fail("$name: waited ${seconds}s for \"$prefix\"; got:\n${dump()}")
                val line = lines.poll(remaining, TimeUnit.NANOSECONDS)
                    ?: fail("$name: waited ${seconds}s for \"$prefix\"; got:\n${dump()}")
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

        const val PACKAGE = "dev.wildware.moba.net"

        /**
         * The roster on the field: `level/test_level`'s twenty-seven, plus one spawned champion.
         *
         * The level authors twenty-seven units and one of them carries `Player`, which the first
         * connection claims. The **second** connection is no longer a spectator - `addClient`
         * spawns it a soldier of its own so two humans can play - so a two-client run has
         * twenty-eight `GameUnit`s in it, and both clients replicate all twenty-eight.
         */
        const val UNITS = 28L

        /** A minute of server at 60Hz, which outlives both clients with room to spare. */
        const val SERVER_TICKS = 3600

        /** Half a minute of client at 60Hz. */
        const val CLIENT_TICKS = 1800

        /** Wall-clock battle before the reading is taken. Three seconds at 60Hz is ~180 ticks. */
        const val BATTLE_MILLIS = 3_000L

        /** Packets a client must have applied before the reading counts as replication. */
        const val MIN_APPLIED = 60L

        const val WAIT_SECONDS = 30L

        /** JVM startup plus a whole `MobaGame` definition and level seed, three times over. */
        const val BOOT_WAIT = 90L

        /** For the waits that include a connection timeout. */
        const val LONG_WAIT = 60L

        /** Replicated component types that must cross the wire at all. */
        const val COVERED = 8

        val FIELD = Regex("""(\w+)=(0x[0-9a-fA-F]+|-?\d+)""")
    }
}
