package dev.wildware.udea.net.transport

import dev.wildware.udea.net.replication.ReplicationClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * A Wasm client on Node replicating from a JVM server over a WebSocket (issue #209, spec D11).
 *
 * The server is not started here and cannot be: a Wasm test has no way to run a JVM. The Gradle
 * task that runs this test starts `WebSocketSnapshotServer`'s `main` as a separate process first,
 * stops it after, and hands its URL over in [WebSocketSnapshotScenario.URL_ENVIRONMENT_VARIABLE].
 * So the two ends share nothing but a port: no heap, no clock, no class loader, not even a
 * language runtime.
 *
 * With no server the test fails rather than skips - an absent URL is an assertion failure, and an
 * unreachable one is a connect failure with [DisconnectReason.Unreachable] - because a check that
 * quietly passes when its other half is missing is the defect it exists to catch.
 */
class WebSocketWasmClientTest {

    @Test
    fun aWasmClientReplicatesSnapshotsFromAJvmServer() = runTest(timeout = DEADLINE * 2) {
        val url = assertNotNull(
            environmentVariable(WebSocketSnapshotScenario.URL_ENVIRONMENT_VARIABLE),
            "${WebSocketSnapshotScenario.URL_ENVIRONMENT_VARIABLE} is not set: this test needs the JVM " +
                "snapshot server the Gradle test task starts",
        )
        val protocol = WebSocketSnapshotScenario.protocol()
        val socket = WebSocketTransport.client(url, protocol.protoHash)
        try {
            val client = ReplicationClient(PeerId.client(1), WebSocketSnapshotScenario.registry(), protocol, socket)
            var mismatch: String? = "never pumped"
            // Real time, not the test dispatcher's virtual time: the bytes are crossing a real socket.
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(DEADLINE) {
                    while (true) {
                        socket.poll { _, buffer, offset, length -> client.onPacket(buffer, offset, length) }
                        if (socket.isConnected) client.sendTick(client.serverTick)
                        mismatch = socket.failure?.let { "the connection failed: $it" }
                            ?: WebSocketSnapshotScenario.mismatch(client)
                        if (mismatch == null || socket.failure != null) break
                        delay(TICK_MILLIS)
                    }
                }
            }

            // Into the test report, so what was exchanged can be read after the run and not only asserted.
            println("${socket.localPeer} applied ${client.applied} snapshot(s) from $url, up to server tick ${client.serverTick}")
            assertNull(mismatch, "the Wasm client's replica never matched the JVM server's snapshots")
            assertEquals(null, socket.failure)
        } finally {
            socket.close()
        }
    }

    private companion object {

        /** A deadline, not a budget: it only turns a hang into a named failure. */
        val DEADLINE = 30.seconds

        const val TICK_MILLIS: Long = 16L
    }
}

/** A Node environment variable, or null when it is unset or this is not running on Node at all. */
@Suppress("UNUSED_PARAMETER")
private fun environmentVariable(name: String): String? =
    js("(typeof process !== 'undefined' && process.env[name]) || null")
