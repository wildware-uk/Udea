package dev.wildware.udea.agent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The daemon-thread policy.
 *
 * It looks like a one-line test of a one-line factory. It is not: the default it replaces
 * (`HttpServer` with `executor = null`, giving a non-daemon dispatch thread) held ports on this
 * project until a human logged in, six times. The absence of that is worth a test that fails
 * the moment somebody writes `Thread(runnable, name)` without the `isDaemon` line.
 */
class AgentThreadsTest {

    @Test
    fun `every thread from the factory is a daemon`() {
        val factory = AgentThreads.daemonFactory("udea-agent-http")

        repeat(3) {
            val thread = factory.newThread { }
            assertTrue(thread.isDaemon, "an agent thread must not outlive the game it observes")
        }
    }

    @Test
    fun `threads carry the name they were given`() {
        val thread = AgentThreads.daemonFactory("udea-agent-http").newThread { }

        // A stack dump of "Thread-7" holding a port tells nobody which subsystem to look at.
        assertEquals("udea-agent-http", thread.name)
    }

    @Test
    fun `the thread still runs the work it was given`() {
        val ran = CountDownLatch(1)

        AgentThreads.daemonFactory("udea-agent-test").newThread { ran.countDown() }.start()

        assertTrue(ran.await(5, TimeUnit.SECONDS), "the factory produced a thread that never ran")
    }

    @Test
    fun `an unnamed thread is refused`() {
        assertFailsWith<IllegalArgumentException> { AgentThreads.daemonFactory("  ") }
    }
}
