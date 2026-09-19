package dev.wildware.hollow.desktop

import dev.wildware.hollow.net.HollowNet

/**
 * `sh gradlew :hollow:desktop:runServer [--args="<port>"]`: the dedicated server. Headless - no
 * render context, no window - and authoritative, on UDP port 27025 unless another is named; players
 * join with `runClient --args="join <host[:port]>"` (issue #249).
 *
 * `-Dhollow.server.ticks=N` stops it after N ticks, for a scripted run; unset, it runs until killed.
 * It ticks at 60Hz against the wall clock, which paces the loop and never enters the simulation:
 * the server's time is its tick count.
 */
public object HollowServerMain {

    /** Stops the server after this many ticks. Unset or 0 runs for ever. */
    public const val TICKS_PROPERTY: String = "hollow.server.ticks"

    @JvmStatic
    public fun main(args: Array<String>) {
        val port = args.firstOrNull()?.trim()?.toIntOrNull() ?: HollowNet.DEFAULT_PORT
        val limit = System.getProperty(TICKS_PROPERTY)?.trim()?.toLongOrNull() ?: 0L
        UdpServing(port, HollowLaunch.levelBytes()).use { serving ->
            println(
                "[hollow.server] listening on ${serving.address}; proto ${serving.server.protocol.protoHash}; " +
                    "${serving.server.host.world.numEntities} entities in the clearing",
            )
            var ticks = 0L
            var deadline = System.nanoTime()
            while (limit == 0L || ticks < limit) {
                serving.pump()
                ticks++
                if (ticks % REPORT_TICKS == 0L) {
                    println("[hollow.server] tick ${serving.server.tick.value}; ${serving.server.clients().size} client(s)")
                }
                deadline += TICK_NANOS
                val remaining = deadline - System.nanoTime()
                if (remaining > 0L) Thread.sleep(remaining / NANOS_PER_MILLI, (remaining % NANOS_PER_MILLI).toInt())
            }
            println("[hollow.server] stopped after $ticks tick(s)")
        }
    }

    private const val TICK_NANOS: Long = 1_000_000_000L / 60
    private const val NANOS_PER_MILLI: Long = 1_000_000L
    private const val REPORT_TICKS: Long = 600L
}
