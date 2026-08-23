package dev.wildware.udea.agent

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The hand-off, under the conditions it actually runs in.
 *
 * The bridge is the one class in the module that two threads touch, so the tests that matter
 * are the concurrent ones: many submitters against one drainer, and a confirmation counter that
 * must never retract. Everything else here exists because the reference implementation got it
 * wrong in a way that cost sessions - an unbounded queue, and command answers smuggled through
 * the event ring.
 */
class AgentBridgeTest {

    private val bridge = AgentBridge()

    private fun drainAll(): List<AgentCommand> {
        val out = ArrayList<AgentCommand>()
        bridge.drain(out)
        return out
    }

    @Test
    fun `a submitted command is drained exactly once, in order`() {
        val first = bridge.submit(AgentCommand("pause"))
        val second = bridge.submit(AgentCommand("step", mapOf("ticks" to "7")))

        val drained = drainAll()

        assertEquals(listOf("pause", "step"), drained.map { it.name })
        assertEquals(first.commandId, drained[0].id)
        assertEquals(second.commandId, drained[1].id)
        assertEquals("7", drained[1].args["ticks"])
        assertEquals(0, bridge.pendingCommands)
        assertTrue(drainAll().isEmpty(), "a drained command must not be drained twice")
    }

    @Test
    fun `the queue is capped and the rejection is a typed value`() {
        val small = AgentBridge(queueCapacity = 4)

        repeat(4) { assertIs<AgentSubmission.Accepted>(small.submit(AgentCommand("noop"))) }

        val rejected = small.submit(AgentCommand("noop"))
        assertIs<AgentSubmission.Rejected>(rejected)
        assertEquals(AgentErrorKind.QUEUE_FULL, rejected.error.kind)
        assertEquals(4, small.pendingCommands, "a rejected command must not be queued")
    }

    @Test
    fun `a 257th submission is rejected and the queue never exceeds 256`() {
        // The documented default, asserted at the documented number: an unbounded queue turns
        // one stalled simulation into an OutOfMemoryError that destroys the evidence.
        repeat(AgentBridge.DEFAULT_QUEUE_CAPACITY) {
            assertIs<AgentSubmission.Accepted>(bridge.submit(AgentCommand("noop")))
        }

        val rejected = bridge.submit(AgentCommand("noop"))

        assertIs<AgentSubmission.Rejected>(rejected)
        assertEquals(AgentErrorKind.QUEUE_FULL, rejected.error.kind)
        assertEquals(AgentBridge.DEFAULT_QUEUE_CAPACITY, bridge.pendingCommands)
        assertEquals(AgentBridge.DEFAULT_QUEUE_CAPACITY, drainAll().size)
    }

    @Test
    fun `capacity is restored as commands drain`() {
        val small = AgentBridge(queueCapacity = 2)
        small.submit(AgentCommand("a"))
        small.submit(AgentCommand("b"))
        assertIs<AgentSubmission.Rejected>(small.submit(AgentCommand("c")))

        val out = ArrayList<AgentCommand>()
        small.drain(out)

        assertIs<AgentSubmission.Accepted>(small.submit(AgentCommand("d")))
    }

    @Test
    fun `concurrent submitters lose no command and never exceed the cap`() {
        val submitters = 8
        val perSubmitter = 200
        val bridge = AgentBridge(queueCapacity = 64)
        val start = CountDownLatch(1)
        val done = CountDownLatch(submitters)
        val accepted = ConcurrentLinkedQueue<Long>()
        val rejected = ConcurrentLinkedQueue<Long>()
        val drained = ArrayList<AgentCommand>()
        var overCap = 0

        val threads = List(submitters) {
            thread(name = "submitter-$it") {
                start.await()
                repeat(perSubmitter) {
                    when (val answer = bridge.submit(AgentCommand("noop"))) {
                        is AgentSubmission.Accepted -> accepted.add(answer.commandId)
                        is AgentSubmission.Rejected -> rejected.add(answer.commandId)
                    }
                }
                done.countDown()
            }
        }

        start.countDown()
        // The simulation thread: drains while the submitters are still going, which is the
        // interleaving the real system runs in.
        while (done.count > 0L) {
            val depth = bridge.pendingCommands
            if (depth > 64) overCap++
            bridge.drain(drained)
        }
        assertTrue(done.await(10, TimeUnit.SECONDS), "submitters did not finish")
        threads.forEach { it.join() }
        bridge.drain(drained)

        assertEquals(0, overCap, "the queue exceeded its cap under concurrent submission")
        assertEquals(
            accepted.size,
            drained.size,
            "every accepted command must be drained exactly once",
        )
        assertEquals(
            accepted.toList().sorted(),
            drained.map { it.id }.sorted(),
            "the drained ids must be exactly the accepted ids",
        )
        assertEquals(submitters * perSubmitter, accepted.size + rejected.size)
    }

    @Test
    fun `completedCommandId advances for a success and for a failure alike`() {
        val ok = AgentCommand("query")
        val bad = AgentCommand("explode")

        bridge.complete(ok.id, AgentResult.ok { put("total", 3) })
        assertEquals(ok.id, bridge.completedCommandId())

        bridge.complete(bad.id, AgentResult.failed(AgentErrorKind.TOOL_THREW, "boom"))

        // The whole point: a caller polling for its answer is released by the command
        // finishing, not by it succeeding. Otherwise a bad argument reads as a frozen game.
        assertEquals(bad.id, bridge.completedCommandId())
    }

    @Test
    fun `both a success value and a typed error reach commandResults`() {
        bridge.complete(18L, AgentResult.ok { put("netId", 412) })
        bridge.complete(19L, AgentResult.failed(AgentErrorKind.NO_SUCH_ENTITY, "no entity 9"))

        val results = bridge.commandResults()

        assertEquals(listOf(18L, 19L), results.map { it.id })
        val success = assertIs<AgentResult.Ok>(results[0].result)
        assertEquals("""{"netId":412}""", success.json)
        val failure = assertIs<AgentResult.Failed>(results[1].result)
        assertEquals(AgentErrorKind.NO_SUCH_ENTITY, failure.error.kind)
        assertEquals("no entity 9", failure.error.message)
    }

    @Test
    fun `command results render in the shape the bridge contract documents`() {
        bridge.complete(18L, AgentResult.ok { put("total", 3) })
        bridge.complete(19L, AgentResult.failed(AgentErrorKind.NO_SUCH_ENTITY, "gone"))

        val json = Json()
        json.beginObject()
        bridge.renderCommandResults(json, "commandResults", 8)
        json.endObject()

        assertEquals(
            """{"commandResults":[{"id":18,"ok":true,"result":{"total":3}},""" +
                """{"id":19,"ok":false,"error":{"kind":"no_such_entity","message":"gone"}}]}""",
            json.toString(),
        )
    }

    @Test
    fun `completedCommandId never retracts when answers arrive out of order`() {
        bridge.complete(5L, AgentResult.EMPTY)
        bridge.complete(3L, AgentResult.EMPTY)

        // A plain store would let the older id un-confirm a command the caller has already been
        // told finished.
        assertEquals(5L, bridge.completedCommandId())
    }

    @Test
    fun `completedCommandId is the high-water mark under concurrent completion`() {
        val completers = 4
        val perThread = 500
        val start = CountDownLatch(1)

        val threads = List(completers) { index ->
            thread(name = "completer-$index") {
                start.await()
                for (id in 1..perThread) {
                    bridge.complete((index * perThread + id).toLong(), AgentResult.EMPTY)
                }
            }
        }
        start.countDown()
        threads.forEach { it.join() }

        assertEquals((completers * perThread).toLong(), bridge.completedCommandId())
    }

    @Test
    fun `the event ring is bounded at 200 and keeps the newest entries`() {
        repeat(250) { bridge.event("event-$it") }

        val events = bridge.events.toList()

        assertEquals(AgentEventRing.DEFAULT_CAPACITY, events.size)
        assertEquals("event-50", events.first())
        assertEquals("event-249", events.last())
        assertEquals(250L, bridge.events.totalRecorded)
    }

    @Test
    fun `reading events does not consume them`() {
        bridge.event("merge:cherry")

        val first = bridge.events.toList()
        val second = bridge.events.toList()

        // The bridge polls /state in a loop while it waits for completedCommandId. A read that
        // consumed would delete the events the agent was waiting to see, timed by the polling
        // loop rather than by anything in the game.
        assertEquals(first, second)
        assertEquals(listOf("merge:cherry"), second)
    }

    @Test
    fun `the published document starts as not-ready and is replaced by publish`() {
        assertEquals(AgentBridge.NOT_READY, bridge.snapshot())

        bridge.publish("""{"ready":true}""")

        assertEquals("""{"ready":true}""", bridge.snapshot())
    }

    @Test
    fun `the read flag is set by a reader and cleared by a publish`() {
        bridge.publish("{}")
        assertFalse(bridge.readSinceLastPublish(), "publishing must clear the flag")

        bridge.snapshot()

        assertTrue(bridge.readSinceLastPublish(), "reading must set the flag")
    }

    @Test
    fun `frame only ever increases`() {
        val before = bridge.frame
        repeat(3) { bridge.advanceFrame() }

        assertEquals(before + 3, bridge.frame)
    }

    @Test
    fun `tick follows the simulation and may move backwards on a rewind`() {
        bridge.publishTick(200)
        assertEquals(200L, bridge.tick)

        bridge.publishTick(100)

        // Deliberate: a rewind moves the tick back, which is exactly why a harness must confirm
        // a command against completedCommandId and not against this.
        assertEquals(100L, bridge.tick)
        assertEquals(0L, bridge.frame, "a rewind must not touch the frame counter")
    }
}
