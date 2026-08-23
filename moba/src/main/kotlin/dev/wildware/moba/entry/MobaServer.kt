package dev.wildware.moba.entry

import dev.wildware.moba.MobaGame
import dev.wildware.moba.net.MobaLoopbackSession
import dev.wildware.moba.net.NetStateProbe
import dev.wildware.udea.net.transport.NetConditions

/**
 * `moba.server`: the **authoritative server**, with clients connected to it.
 *
 * This file used to say, in its own KDoc, that it bound "no network socket: `udea-net` is not
 * wired into `moba` yet, so what this actually is today is a simulation with nobody connected to
 * it". That is no longer true. It owns the simulation, it accepts clients, and it replicates to
 * them every tick out of the snapshot ring that also backs `time.rewind` - one structure, two
 * features, which is what spec 3.1 requires and what the absence of any second baseline store
 * here makes structural.
 *
 * ## Why the clients are in this process
 *
 * Trello #8's host model is a listen server: every endpoint is an ordinary
 * [dev.wildware.udea.net.transport.Transport] in one JVM on one thread, so the code path that
 * serves a remote client is the *same* one that serves a local one and is therefore exercised by
 * every run rather than only by playing the game. A UDP transport plugs into exactly the same
 * seam - [dev.wildware.moba.net.MobaHostSession] takes a `Transport` and knows nothing else about
 * the network - so nothing in the game or in the replication path changes when a socket lands.
 * It has landed: [dev.wildware.moba.net.MobaUdpServer] and
 * [dev.wildware.moba.net.MobaUdpClient] are this same session over `UdpTransport` in
 * separate operating-system processes, and `MobaUdpTwoProcessTest` runs all three and
 * compares them component by component, on a perfect link and at 150ms with 5% loss.
 *
 * ## Knobs
 *
 * | property | default | what it does |
 * |---|---|---|
 * | `-Dudea.net.clients` | 1 | how many clients to stand up |
 * | `-Dudea.net.ticks` | 0 | stop after this many ticks; 0 runs until killed |
 * | `-Dudea.net.lossy` | false | apply 150ms and 5% loss to every link, both directions |
 *
 * Ticks as fast as the CPU allows, as it always has, so pause, step and rewind still mean what
 * they mean everywhere else - the tick order comes from
 * [dev.wildware.udea.net.transport.NetHarness], which releases due datagrams before it polls and
 * sends last, making the minimum round trip two ticks rather than zero.
 *
 * `./gradlew :moba:runServer`
 */
public object MobaServer {

    /** How many clients to stand up. */
    public const val CLIENTS_PROPERTY: String = "udea.net.clients"

    /** Stop after this many ticks. `0` runs until the process is killed. */
    public const val TICKS_PROPERTY: String = "udea.net.ticks"

    /** Apply [NetConditions.TRELLO_8] to every link instead of a perfect one. */
    public const val LOSSY_PROPERTY: String = "udea.net.lossy"

    /** Ticks between status lines. Two seconds at 60Hz. */
    private const val REPORT_INTERVAL: Int = 120

    /** Boots and blocks. Kill the process to stop it, or bound it with `-Dudea.net.ticks`. */
    @JvmStatic
    public fun main(args: Array<String>) {
        val clients = intProperty(CLIENTS_PROPERTY, default = 1).coerceAtLeast(1)
        val limit = intProperty(TICKS_PROPERTY, default = 0).coerceAtLeast(0)
        val lossy = System.getProperty(LOSSY_PROPERTY)?.toBoolean() ?: false
        val conditions = if (lossy) NetConditions.TRELLO_8 else NetConditions.PERFECT

        val session = MobaLoopbackSession(clientCount = clients, conditions = conditions)
        println(
            "[moba.server] ${MobaGame.NAME} ${MobaGame.VERSION} authoritative; " +
                "$clients client(s); proto ${session.server.protocol.protoHash}; " +
                (if (lossy) "150ms/5% loss" else "perfect links"),
        )
        // `running` is read by the loop below and written by the hook thread, so it is volatile
        // for the reason `GameHost.running` is: without it the JIT may hoist the read out of the
        // loop and a shutdown would never be observed.
        Runtime.getRuntime().addShutdownHook(Thread { running = false })

        var ticks = 0L
        while (running && (limit == 0 || ticks < limit)) {
            session.step(1)
            ticks++
            if (ticks % REPORT_INTERVAL == 0L) report(session, ticks)
        }
        report(session, ticks)
        session.close()
        println("[moba.server] stopped after $ticks tick(s)")
    }

    @Volatile
    private var running: Boolean = true

    private fun report(session: MobaLoopbackSession, ticks: Long) {
        val units = NetStateProbe.unitCount(session.server.host.world)
        val seen = session.clients.joinToString { "${it.peer}=${it.unitCount()}u/${it.applied}p" }
        println(
            "[moba.server] t$ticks sim=${session.server.tick.value} units=$units " +
                "clients[$seen] deferrals=${session.server.replication.budgetDeferrals}",
        )
    }

    private fun intProperty(name: String, default: Int): Int {
        val raw = System.getProperty(name)?.trim().orEmpty()
        if (raw.isEmpty()) return default
        return raw.toIntOrNull()
            ?: throw IllegalArgumentException("-D$name=$raw is not an integer")
    }
}
