package dev.wildware.udea.gas

import dev.wildware.udea.core.Tick
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The modifier order is a pure function of `(ModifierType, AttributeId, EffectHandle)`.
 *
 * `AttributeSystem.kt:23` sorted by `modifierType` alone with `sortedBy`, which is stable — so two
 * modifiers of the same type resolved in whatever order the effect *list* happened to hold them,
 * a list that a `removeIf` compacted and a restore rebuilt.
 *
 * Two claims are tested separately, because they are different claims and one of them is easy to
 * overstate. The first: modifiers of different types always resolve type-first, so a set of
 * commuting modifiers gives the same answer whatever order it was applied in. The second: two
 * modifiers of the *same* type on the same attribute resolve in handle order, so the later
 * application wins — which is what the handle component of the key decides.
 */
class AttributeRecomputeOrderTest {

    /**
     * Five modifiers on one attribute, deliberately non-commutative: three additive and two
     * multiplicative, so applying them in a different sequence changes the answer unless the sort
     * imposes a total order first.
     */
    private fun buildFixture(): Pair<GasFixture, List<Int>> {
        val fixture = GasFixture()
        val effects = listOf(
            fixture.hasteEffect,
            fixture.slowEffect,
            fixture.hasteEffect,
            fixture.slowEffect,
            fixture.hasteEffect,
        )
        return fixture to effects
    }

    @Test
    fun `one hundred shuffled insertion orders produce bit-identical results`() {
        val random = Random(20_260_823L)
        var expected: FloatArray? = null

        repeat(100) { attempt ->
            val (fixture, effects) = buildFixture()
            val unit = fixture.unit()
            for (effect in effects.shuffled(random)) unit.apply(effect, Tick.ZERO)
            unit.recompute(Tick(1))

            val current = unit.attributes.current.copyOf()
            if (expected == null) {
                expected = current
            } else {
                assertContentEquals(
                    expected!!.map { it.toRawBits() },
                    current.map { it.toRawBits() },
                    "attempt $attempt disagreed; the modifier order is not insertion-independent",
                )
            }
        }
        // Three +5 additive then two x0.5 multiplicative on a base of 10: (10+15) * 0.25.
        assertEquals(6.25f, expected!![GasFixture().moveSpeed.index])
    }

    @Test
    fun `two overrides on one attribute resolve in handle order, later application winning`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.apply(fixture.rootEffect, Tick.ZERO)
        unit.apply(fixture.petrifyEffect, Tick.ZERO)
        unit.recompute(Tick(1))
        assertEquals(99f, unit.attributes.current(fixture.moveSpeed), "the later handle wins")

        val reversed = GasFixture()
        val other = reversed.unit()
        other.apply(reversed.petrifyEffect, Tick.ZERO)
        other.apply(reversed.rootEffect, Tick.ZERO)
        other.recompute(Tick(1))
        assertEquals(0f, other.attributes.current(reversed.moveSpeed), "and here that is the root")
    }

    @Test
    fun `an override outranks the additive and multiplicative modifiers before it`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.apply(fixture.petrifyEffect, Tick.ZERO)
        unit.apply(fixture.hasteEffect, Tick.ZERO)
        unit.apply(fixture.slowEffect, Tick.ZERO)
        unit.recompute(Tick(1))
        assertEquals(
            99f,
            unit.attributes.current(fixture.moveSpeed),
            "Override has the highest ordinal, so it resolves last whatever order things arrived in",
        )
    }

    @Test
    fun `additive modifiers apply before multiplicative ones`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.apply(fixture.slowEffect, Tick.ZERO)
        unit.apply(fixture.hasteEffect, Tick.ZERO)
        unit.recompute(Tick(1))

        assertEquals(
            7.5f,
            unit.attributes.current(fixture.moveSpeed),
            "the slow was applied first but must still be evaluated second: (10 + 5) * 0.5",
        )
    }
}

/**
 * Recomputing twice on one tick changes nothing the second time.
 *
 * This is what stops a rollback re-simulation double-applying: a rewind replays ticks that have
 * already run once, and an instant effect that fired on both passes would take damage twice.
 */
class AttributeIdempotenceTest {

    @Test
    fun `running the recompute twice on the same tick is a no-op the second time`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.apply(fixture.hasteEffect, Tick.ZERO)
        unit.apply(fixture.regenEffect, Tick.ZERO)

        unit.recompute(Tick(15))
        val baseAfterFirst = unit.attributes.base.copyOf()
        val currentAfterFirst = unit.attributes.current.copyOf()

        unit.recompute(Tick(15))

        assertContentEquals(baseAfterFirst, unit.attributes.base, "a periodic effect fired twice")
        assertContentEquals(currentAfterFirst, unit.attributes.current)
    }

    @Test
    fun `an instant effect applies exactly once even if the recompute runs repeatedly`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.attributes.setBase(fixture.health, 100f)
        unit.apply(fixture.damageEffect, Tick.ZERO, fixture.damageTag to -10f)

        repeat(5) { unit.recompute(Tick.ZERO) }

        assertEquals(90f, unit.attributes.base(fixture.health))
        assertEquals(0, unit.effects.count, "an instant effect is swept the tick it fires")
    }
}

/**
 * A GAS-local snapshot round trip: capture, run on, restore, run again, compare.
 *
 * `udea-core`'s `SnapshotService` captures Fleks components through generated `Replicator`s and
 * knows nothing about the [HandleAllocator]; wiring GAS into the ring needs a `udea-core`-side
 * hook that does not exist yet. So this test captures the same *state* the ring would — base
 * values, the effect columns and the allocator — by hand, and proves the equivalence property the
 * ring will need: a restored world that re-simulates the same ticks reaches the same numbers.
 */
class GasSnapshotEquivalenceTest {

    @Test
    fun `snapshot at one hundred, run to two twenty, restore and run again - identical state`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.attributes.setBase(fixture.health, 0f)
        unit.apply(fixture.regenEffect, Tick.ZERO)

        for (tick in 1L..100L) unit.recompute(Tick(tick))
        val captured = GasState.capture(unit, fixture.handles)

        for (tick in 101L..220L) unit.recompute(Tick(tick))
        val firstRunBase = unit.attributes.base.copyOf()
        val firstRunCurrent = unit.attributes.current.copyOf()
        val firstRunNext = fixture.handles.next

        GasState.restore(captured, unit, fixture.handles)
        for (tick in 101L..220L) unit.recompute(Tick(tick))

        assertContentEquals(firstRunBase, unit.attributes.base, "a re-simulated run must reach the same base")
        assertContentEquals(firstRunCurrent, unit.attributes.current)
        assertEquals(firstRunNext, fixture.handles.next, "and must issue the same handles on the way")
    }

    @Test
    fun `restoring rewinds an effect that had already expired`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.apply(fixture.hasteEffect, Tick.ZERO)
        unit.recompute(Tick(10))
        val captured = GasState.capture(unit, fixture.handles)

        unit.recompute(Tick(40))
        assertEquals(0, unit.effects.count, "the haste has expired by tick 40")

        GasState.restore(captured, unit, fixture.handles)
        unit.recompute(Tick(11))
        assertEquals(1, unit.effects.count, "the restored world is back before the expiry")
        assertEquals(15f, unit.attributes.current(fixture.moveSpeed))
    }
}
