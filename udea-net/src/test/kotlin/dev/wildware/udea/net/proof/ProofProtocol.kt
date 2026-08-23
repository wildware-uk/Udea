package dev.wildware.udea.net.proof

import dev.wildware.udea.core.replication.ComponentTypeId
import dev.wildware.udea.net.transport.ConnectionSecret
import dev.wildware.udea.net.transport.UdpConfig
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * What the two proof processes agree on without talking to each other first.
 *
 * The point of the two-process demo is that the *only* channel between the processes is the
 * socket. So everything else — the component registry, the protocol hash derived from it, the
 * connect key, the tick rate — has to be something both sides compute or hold independently,
 * exactly as a shipped client and a shipped server would.
 */
internal object ProofProtocol {

    /**
     * The server's connect key.
     *
     * Fixed bytes in a test harness, and it must not be anywhere near a shipped server: the
     * whole scheme rests on this being unguessable. `ConnectionSecret` takes the key from its
     * caller rather than generating one precisely so the decision about where a real key comes
     * from is made by the deployment and not buried in a transport.
     */
    fun secret(): ConnectionSecret =
        ConnectionSecret(ByteArray(ConnectionSecret.MIN_KEY_BYTES) { (it * 7 + 3).toByte() })

    /** The type id `MoverReplicator` declares, which is how a client finds the column. */
    val MOVER_TYPE_ID: ComponentTypeId = ComponentTypeId(1)

    /** 60Hz, the engine's simulation rate. */
    const val TICK_NANOS: Long = 16_666_667L

    /**
     * Two seconds of silence at 60Hz.
     *
     * Far shorter than the ten-second default, so that "kill one end and watch the other notice"
     * is a two-second wait rather than a ten-second one. The mechanism under test is identical.
     */
    const val TIMEOUT_TICKS: Long = 120L

    fun config(maxClients: Int = 4): UdpConfig = UdpConfig(
        maxClients = maxClients,
        timeoutTicks = TIMEOUT_TICKS,
        keepAliveIntervalTicks = 6L,
        connectTimeoutTicks = 300L,
    )

    fun loopback(port: Int): InetSocketAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), port)

    /**
     * Writes one line of the process's report and flushes it.
     *
     * The flush is not optional. A child process's `System.out` is a pipe rather than a console,
     * so it buffers, and a parent waiting on a line that is sitting in a buffer waits forever.
     */
    fun say(line: String) {
        println(line)
        System.out.flush()
    }
}
