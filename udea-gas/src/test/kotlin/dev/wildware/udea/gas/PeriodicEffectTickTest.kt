package dev.wildware.udea.gas

import dev.wildware.udea.core.Tick
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Periodic firing is driven by `nextPeriodTick`, so it neither drifts nor depends on step size.
 *
 * The old loop added `gameScreen.delta` to `spec.period` and reset it to zero on every fire
 * (`AttributeSystem.kt:26`, `:44`). Two defects followed: the remainder past the period was thrown
 * away, so a 15-tick period at a 16.7ms delta drifted a little further behind every fire; and a
 * frame that covered two periods fired once, so `step(60)` and sixty single steps disagreed.
 */
class PeriodicEffectTickTest {

    @Test
    fun `a fifteen tick period fires exactly four times over sixty ticks`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.attributes.setBase(fixture.health, 0f)
        unit.apply(fixture.regenEffect, Tick.ZERO)

        for (tick in 1L..60L) unit.recompute(Tick(tick))

        // +5 health per fire, at ticks 15, 30, 45 and 60.
        assertEquals(20f, unit.attributes.base(fixture.health))
    }

    @Test
    fun `ten thousand ticks show zero drift`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.attributes.setBase(fixture.health, 0f)
        unit.apply(fixture.regenEffect, Tick.ZERO)

        for (tick in 1L..10_000L) unit.recompute(Tick(tick))

        // 10000 / 15 = 666 whole periods, and not one fire lost to accumulated remainder.
        assertEquals(666 * 5f, unit.attributes.base(fixture.health))
        assertEquals(
            Tick(667L * 15),
            unit.effects.nextPeriodTickAt(0),
            "the next fire is still on the exact multiple, so nothing has drifted",
        )
    }

    @Test
    fun `stepping sixty ticks at once catches up to sixty single steps`() {
        val stepped = GasFixture()
        val steppedUnit = stepped.unit()
        steppedUnit.attributes.setBase(stepped.health, 0f)
        steppedUnit.apply(stepped.regenEffect, Tick.ZERO)
        for (tick in 1L..60L) steppedUnit.recompute(Tick(tick))

        val jumped = GasFixture()
        val jumpedUnit = jumped.unit()
        jumpedUnit.attributes.setBase(jumped.health, 0f)
        jumpedUnit.apply(jumped.regenEffect, Tick.ZERO)
        jumpedUnit.recompute(Tick(60))

        assertEquals(
            steppedUnit.attributes.base(stepped.health),
            jumpedUnit.attributes.base(jumped.health),
            "a single step over sixty ticks must fire every period it skipped",
        )
        assertEquals(steppedUnit.effects.nextPeriodTickAt(0), jumpedUnit.effects.nextPeriodTickAt(0))
    }

    @Test
    fun `a periodic effect writes base, not current`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.attributes.setBase(fixture.health, 0f)
        unit.apply(fixture.regenEffect, Tick.ZERO)
        unit.recompute(Tick(15))

        assertEquals(5f, unit.attributes.base(fixture.health), "a periodic change is permanent")
        assertEquals(5f, unit.attributes.current(fixture.health), "current derives from the new base")
    }
}
