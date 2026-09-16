package dev.wildware.udea.gas

import dev.wildware.udea.core.Tick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Handles are per world and inside the snapshot, so two worlds cannot collide and a rewind
 * reproduces the same sequence.
 *
 * `EffectHandle.next()` read `private var nextId` on a companion object
 * (`common/ability/GameplayEffectSpec.kt:66-68`). A server and a client in one JVM — which is what
 * `LoopbackTransport` and the whole Phase 3 demo are — drew from the same counter, and a rewind
 * left the counter ahead of the world it had been rolled back with.
 */
class HandleAllocatorTest {

    @Test
    fun `two worlds in one JVM allocate independent sequences`() {
        val worldA = GasFixture()
        val worldB = GasFixture()
        val unitA = worldA.unit()
        val unitB = worldB.unit()

        val firstA = unitA.apply(worldA.hasteEffect, Tick.ZERO)
        val firstB = unitB.apply(worldB.hasteEffect, Tick.ZERO)
        val secondA = unitA.apply(worldA.slowEffect, Tick.ZERO)

        assertEquals(EffectHandle(0), firstA)
        assertEquals(EffectHandle(0), firstB, "world B's sequence starts at zero, independently")
        assertEquals(EffectHandle(1), secondA)
        assertEquals(2, worldA.handles.next)
        assertEquals(1, worldB.handles.next, "world A's allocations must not advance world B")
    }

    @Test
    fun `a captured then restored allocator resumes from the restored value`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.apply(fixture.hasteEffect, Tick.ZERO)
        unit.apply(fixture.slowEffect, Tick.ZERO)

        val captured = HandleAllocatorState()
        fixture.handles.saveInto(captured)
        assertEquals(2, captured.next)

        // Simulate forward, then rewind.
        repeat(5) { fixture.handles.allocate() }
        assertEquals(7, fixture.handles.next)

        fixture.handles.restoreFrom(captured)
        assertEquals(2, fixture.handles.next)
        assertEquals(EffectHandle(2), fixture.handles.allocate(), "the sequence resumes, it does not restart")
    }

    @Test
    fun `re-simulating after a restore reproduces the same handles`() {
        val fixture = GasFixture()
        val unit = fixture.unit()

        val captured = HandleAllocatorState()
        fixture.handles.saveInto(captured)

        val firstRun = List(4) { unit.apply(fixture.hasteEffect, Tick(it.toLong())) }
        repeat(4) { unit.effects.removeAt(unit.effects.count - 1) }

        fixture.handles.restoreFrom(captured)
        val secondRun = List(4) { unit.apply(fixture.hasteEffect, Tick(it.toLong())) }

        assertEquals(firstRun, secondRun, "a rewound world must issue the same handle sequence again")
    }

    @Test
    fun `a released handle is never reissued`() {
        val allocator = HandleAllocator()
        val first = allocator.allocate()
        allocator.release(first)
        val second = allocator.allocate()
        assertNotEquals(first, second, "recycling would alias a stale reference to a live effect")
        assertEquals(EffectHandle(1), second, "the counter is monotonic; the released id is gone for good")
        assertEquals(1, allocator.liveCount, "one released, one outstanding")
    }

    @Test
    fun `releasing a handle this allocator never issued fails loudly`() {
        val allocator = HandleAllocator()
        val failure = assertFailsWith<IllegalArgumentException> { allocator.release(EffectHandle(9)) }
        assertTrue(failure.message!!.contains("never issued"), failure.message!!)
    }

    @Test
    fun `an expired effect returns its handle to the allocator`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.apply(fixture.hasteEffect, Tick.ZERO)
        assertEquals(1, fixture.handles.liveCount)

        unit.recompute(Tick(30))
        assertEquals(0, unit.effects.count)
        assertEquals(0, fixture.handles.liveCount, "the expiry sweep releases the handle")
    }
}
