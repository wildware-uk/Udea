package dev.wildware.udea.gas

import dev.wildware.udea.core.Tick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Expiry is a function of two ticks, so it lands on the same tick however the simulation got there.
 *
 * The old rule could not state that: `spec.duration` was a `Float` accumulated from a frame delta
 * (`AttributeSystem.kt:52`) and compared against another `Float`
 * (`GameplayEffectSpec.kt:117`), so a machine that ran thirty small frames disagreed with one that
 * ran thirty large ones, and neither could be rewound.
 */
class EffectDurationTickTest {

    @Test
    fun `a thirty tick effect expires on exactly applied plus thirty`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.apply(fixture.hasteEffect, Tick(10))

        // Step to the tick before expiry: still applied, and still modifying moveSpeed.
        for (tick in 11L..39L) unit.recompute(Tick(tick))
        assertEquals(1, unit.effects.count, "the haste effect expired early")
        assertEquals(15f, unit.attributes.current(fixture.moveSpeed), "base 10 + haste 5")

        unit.recompute(Tick(40))
        assertEquals(0, unit.effects.count, "applied at 10 with a 30-tick duration must expire at 40")
        assertEquals(10f, unit.attributes.current(fixture.moveSpeed), "current returns to base")
    }

    @Test
    fun `stepping one tick at a time and thirty at once expire identically`() {
        val stepped = GasFixture()
        val steppedUnit = stepped.unit()
        steppedUnit.apply(stepped.hasteEffect, Tick.ZERO)
        for (tick in 1L..30L) steppedUnit.recompute(Tick(tick))

        val jumped = GasFixture()
        val jumpedUnit = jumped.unit()
        jumpedUnit.apply(jumped.hasteEffect, Tick.ZERO)
        jumpedUnit.recompute(Tick(30))

        assertEquals(steppedUnit.effects.count, jumpedUnit.effects.count)
        assertEquals(0, jumpedUnit.effects.count, "a 30-tick effect applied at 0 is gone at tick 30")
        assertEquals(
            steppedUnit.attributes.current(stepped.moveSpeed),
            jumpedUnit.attributes.current(jumped.moveSpeed),
        )
    }

    @Test
    fun `an infinite effect never expires`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.apply(fixture.regenEffect, Tick.ZERO)
        for (tick in 1L..1_000L) unit.recompute(Tick(tick))
        assertEquals(1, unit.effects.count)
    }

    @Test
    fun `the expiry rule itself is a pure function of ticks`() {
        assertFalse(hasExpired(Tick(39), Tick(10), 30L))
        assertTrue(hasExpired(Tick(40), Tick(10), 30L))
        assertTrue(hasExpired(Tick(41), Tick(10), 30L))
        assertFalse(
            hasExpired(Tick(Long.MAX_VALUE / 2), Tick.ZERO, GameplayEffectDuration.INFINITE),
            "an infinite duration must never expire, whatever the tick",
        )
    }

    @Test
    fun `a set-by-caller duration is resolved once, at application`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.apply(fixture.cooldownEffect, Tick(100), fixture.cooldownTag to 50f)

        val slot = unit.effects.indexOfHandle(EffectHandle(0))
        assertEquals(50L, unit.effects.durationTicksAt(slot))

        // Changing the magnitude afterwards must not retroactively extend a running effect.
        unit.effects.setMagnitude(slot, fixture.cooldownTag, 5_000f)
        unit.recompute(Tick(150))
        assertEquals(0, unit.effects.count, "the duration was fixed at application, at 50 ticks")
    }
}
