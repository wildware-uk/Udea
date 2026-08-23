package dev.wildware.udea.gas

import dev.wildware.udea.core.Tick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The recompute allocates nothing in steady state, at 5v5 scale and then some.
 *
 * This is the gate, not an aspiration. `AttributeSystem.kt:23` called `.sortedBy { }` once per
 * entity per tick: at 500 entities and 60Hz that is 30 000 lists a second, and the pause it buys
 * is a frame the simulation does not get. Everything in [AttributeRecompute] — the order buffer,
 * the magnitude cursor, the `when` in [ModifierType.apply] instead of a stored
 * `(Float, Float) -> Float` — exists to make this number zero.
 *
 * Excluded from `test` and run by `udeaGasAllocationBudget`, matching how `udea-core` gates its
 * snapshot and tick-loop budgets: a measurement task's numbers belong in the build log, and a
 * normal test run should not pay for it twice.
 */
class AttributeAllocationTest {

    private class Population(val fixture: GasFixture, count: Int, effectsEach: Int) {
        val units: List<GasFixture.Unit> = List(count) { index ->
            fixture.unit(index + 1).also { unit ->
                repeat(effectsEach / 2) { unit.apply(fixture.hasteEffect, Tick.ZERO) }
                repeat(effectsEach - effectsEach / 2) { unit.apply(fixture.slowEffect, Tick.ZERO) }
            }
        }

        fun recompute(now: Tick) {
            var index = 0
            while (index < units.size) {
                units[index].recompute(now)
                index++
            }
        }
    }

    @Test
    fun `five hundred entities with eight effects each allocate zero bytes over six hundred ticks`() {
        assertTrue(AllocationProbe.isSupported, "this JVM has no thread allocation counters")

        // Infinite-duration effects, so the population is steady: nothing expires, nothing is
        // re-applied, and what is left is exactly the per-tick recompute.
        val population = Population(GasFixture(), count = 500, effectsEach = 8)
        var tick = 1L

        val bytes = AllocationProbe.bytesAllocated(warmups = 2, attempts = 3) {
            repeat(600) {
                population.recompute(Tick(tick))
                tick++
            }
        }

        assertEquals(0L, bytes, "the per-tick recompute must not allocate; measured $bytes bytes")
    }

    @Test
    fun `the order buffer is reused across entities and ticks`() {
        assertTrue(AllocationProbe.isSupported, "this JVM has no thread allocation counters")

        val population = Population(GasFixture(), count = 50, effectsEach = 16)
        var tick = 1L
        // Warm up past the buffer's one and only growth, which happens on the first entity that
        // carries more effects than the default capacity.
        repeat(10) {
            population.recompute(Tick(tick))
            tick++
        }

        val bytes = AllocationProbe.bytesAllocated {
            population.recompute(Tick(tick))
            tick++
        }
        assertEquals(0L, bytes, "growing the order buffer must be a one-off, not per tick")
    }

    @Test
    fun `activating an ability allocates nothing on the success path`() {
        assertTrue(AllocationProbe.isSupported, "this JVM has no thread allocation counters")

        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.abilities.grant(0, fixture.blink)
        unit.recompute(Tick.ZERO)
        var tick = 1L

        val bytes = AllocationProbe.bytesAllocated {
            // Activate, then end, so the next iteration can activate again. Blink has no cooldown
            // and no cost, so this is the activation path itself: gating, the exec call and the
            // instance write, with no effect application.
            unit.activate(0, Tick(tick))
            fixture.activation.end(unit.netId, unit.abilities, unit.attributes, unit.effects, 0, Tick(tick))
            tick++
        }

        assertEquals(0L, bytes, "activation must not reflect and must not allocate; measured $bytes")
    }
}
