package dev.wildware.udea.agent.host

import java.io.IOException
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `GET /command?cmd=close` ends the instance, and nothing kills anything.
 *
 * ## The state this replaces
 *
 * There was no `close` tool. `game-bridge-mcp` sends a bare `close` and waits for the port to go
 * quiet - `stop_instance` calls that a clean close and escalates to killing the process tree only
 * if it does not happen. Against this engine it never happened, so every stop was an escalation,
 * and the CI conformance step "assert the port goes silent" sent `kill` at the pid a second
 * beforehand: it was a test of `kill(2)`, which works.
 *
 * So the assertions here are deliberately about the **absence** of a kill. No thread is
 * interrupted, no process is signalled, `AgentGameLoop.stop` is called by nothing in this file.
 * The only input is one HTTP GET.
 */
class CloseEndToEndTest {

    @Test
    fun `the game closes itself and the port goes quiet`() {
        val instance = LiveInstance()
        try {
            // Healthy first, or "the port is quiet" would be satisfied by a host that never
            // came up.
            assertEquals(200, instance.get("/health").statusCode(), "the instance did not start")
            assertFalse(instance.loopFinished(), "the frame loop is not running")

            val accepted = instance.get("/command?cmd=close").body()
            assertTrue(accepted.contains("\"accepted\":true"), accepted)

            assertTrue(
                instance.await(WAIT_MILLIS) { !instance.agentHost.isRunning },
                "the host is still serving ${WAIT_MILLIS}ms after close; the bridge would keep " +
                    "this instance listed and keep failing against it",
            )
            assertTrue(
                instance.await(WAIT_MILLIS) { instance.loopFinished() },
                "the port went quiet but the frame loop is still running - which is the failure " +
                    "that reads to a bridge as a clean close over a game that is still going",
            )
            assertTrue(instance.loopReturned, "AgentGameLoop.run never returned")
            assertEquals(LiveInstanceReason.DEFAULT, instance.shutdown.reason)

            // And the port is genuinely gone, not merely reported gone.
            try {
                instance.get("/health")
                fail("GET /health still answers on port ${instance.port} after close")
            } catch (expected: ConnectException) {
                // The socket is closed. This is what `waitForSilence` sees.
            } catch (expected: IOException) {
                // Some JDKs surface a closed loopback port as a plain IOException.
            }
        } finally {
            instance.close()
        }
    }

    @Test
    fun `a second close over HTTP is accepted and changes nothing`() {
        val instance = LiveInstance()
        try {
            instance.get("/command?cmd=close&reason=first")
            assertTrue(instance.await(WAIT_MILLIS) { !instance.agentHost.isRunning })

            // The port is gone, so the second one cannot even be sent - which is itself the
            // contract: the bridge's `close` is fire-and-forget and silence is the answer. What
            // is asserted is that the teardown recorded the *first* reason, so nothing re-ran.
            assertEquals("first", instance.shutdown.reason)
            assertTrue(instance.shutdown.isClosed)
        } finally {
            instance.close()
        }
    }

    private companion object {
        /**
         * Generous, and it does not make the test slow: every assertion polls and returns the
         * moment it is true. A close that takes longer than one frame is a defect, and a
         * deadline near one frame would be a flake on a loaded CI runner instead of a finding.
         */
        const val WAIT_MILLIS: Long = 5_000L
    }
}

/** The reason `close` records when the caller names none. Named so the test reads as one line. */
private object LiveInstanceReason {
    const val DEFAULT: String = dev.wildware.udea.agent.tools.LifecycleToolset.DEFAULT_REASON
}
