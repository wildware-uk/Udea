package dev.wildware.udea.gas

import dev.wildware.udea.core.Tick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Slots in one [CooldownGroup] wait out one cooldown; slots outside it are untouched.
 *
 * ## Why the group is a property of the slot
 *
 * The obvious place for it is [AbilityDef], and that is wrong: one definition can be both a
 * champion's own ability and the active an item grants. `moba`'s `item/aegis` grants
 * `ability/priest_heal`, which is also the priest's slot-one ability, so a group carried on the
 * definition would put those two in one group and cool them down together - destroying the
 * independence an item active exists to have.
 *
 * [slots granted the same definition inside and outside a group do not share] is the test that
 * says so. It grants **one** ability index into three slots, two of them in a group, and asserts
 * that firing one of the pair leaves the third alone. Under a group-on-the-definition design that
 * assertion is unsatisfiable.
 *
 * ## What sharing is made of
 *
 * Nothing new is stored. `AbilityActivation.activate` already applies a cooldown effect and keeps
 * its [EffectHandle] on the [AbilityInstance]; sharing points every slot in the group at that same
 * handle, so `cooldownRemaining` resolves the same application for all of them and the whole thing
 * survives a rewind for the reason a single-slot cooldown already does.
 */
class CooldownGroupTest {

    /** Slots 1 and 2 are one group; slot 0 is on its own. */
    private val pairedSharing = CooldownSharing { slot ->
        if (slot == 1 || slot == 2) GROUP else CooldownGroup.NONE
    }

    private fun fixture() = GasFixture(sharing = pairedSharing)

    @Test
    fun `firing one slot in a group cools down every slot in it`() {
        val gas = fixture()
        val unit = gas.unit(abilitySlots = 3)
        unit.abilities.grant(1, gas.fireball)
        unit.abilities.grant(2, gas.blink)
        unit.recompute(Tick.ZERO)

        assertEquals(ActivationResult.Activated, unit.activate(1, Tick.ZERO))
        unit.end(1, Tick.ZERO)

        assertTrue(unit.cooldownRemaining(1, Tick.ZERO) > 0, "the slot that fired is cooling down")
        assertEquals(
            unit.cooldownRemaining(1, Tick.ZERO),
            unit.cooldownRemaining(2, Tick.ZERO),
            "its group partner waits out the same cooldown, to the tick",
        )
        assertIs<ActivationResult.OnCooldown>(
            unit.canActivate(2, Tick.ZERO),
            "and is refused while it runs",
        )
    }

    @Test
    fun `a slot outside the group is untouched in both directions`() {
        val gas = fixture()
        val unit = gas.unit(abilitySlots = 3)
        unit.abilities.grant(0, gas.blink)
        unit.abilities.grant(1, gas.fireball)
        unit.recompute(Tick.ZERO)

        assertEquals(ActivationResult.Activated, unit.activate(1, Tick.ZERO))
        unit.end(1, Tick.ZERO)
        assertEquals(0, unit.cooldownRemaining(0, Tick.ZERO), "the ungrouped slot is still ready")

        assertEquals(
            ActivationResult.Activated,
            unit.activate(0, Tick.ZERO),
            "and it can fire while the group is cooling",
        )
        unit.end(0, Tick.ZERO)
        assertTrue(unit.cooldownRemaining(1, Tick.ZERO) > 0, "the group is still cooling after it")
    }

    /**
     * The case a group on the [AbilityDef] could not express: one definition, three slots, and the
     * grouping decided by which slot it was granted into.
     */
    @Test
    fun `slots granted the same definition inside and outside a group do not share`() {
        val gas = fixture()
        val unit = gas.unit(abilitySlots = 3)
        unit.abilities.grant(0, gas.fireball)
        unit.abilities.grant(1, gas.fireball)
        unit.abilities.grant(2, gas.fireball)
        unit.recompute(Tick.ZERO)

        assertEquals(ActivationResult.Activated, unit.activate(1, Tick.ZERO))
        unit.end(1, Tick.ZERO)

        assertTrue(unit.cooldownRemaining(2, Tick.ZERO) > 0, "slot 2 is in the group and waits")
        assertEquals(
            0,
            unit.cooldownRemaining(0, Tick.ZERO),
            "slot 0 holds the very same ability and is not in the group, so it is ready",
        )
    }

    /**
     * A grant through [AbilityActivation.grant] adopts whatever the group is already serving.
     *
     * `Abilities.grant` resets the instance, which clears its cooldown handle - so without this a
     * player fires the group's first slot and then grants something into the second and has the
     * cooldown back. In `moba` that is "buy a second item active", which is a hole a player finds
     * in one match.
     */
    @Test
    fun `granting into a cooling group adopts the cooldown that is already running`() {
        val gas = fixture()
        val unit = gas.unit(abilitySlots = 3)
        unit.abilities.grant(1, gas.fireball)
        unit.recompute(Tick.ZERO)
        assertEquals(ActivationResult.Activated, unit.activate(1, Tick.ZERO))
        unit.end(1, Tick.ZERO)
        for (tick in 1L..100L) unit.recompute(Tick(tick))
        val remaining = unit.cooldownRemaining(1, Tick(100))
        assertTrue(remaining > 0, "the group is mid-cooldown")

        unit.grant(2, gas.blink, Tick(100))

        assertEquals(
            remaining,
            unit.cooldownRemaining(2, Tick(100)),
            "the newly granted slot waits out what is left of the group's cooldown",
        )
        assertIs<ActivationResult.OnCooldown>(unit.canActivate(2, Tick(100)))
    }

    /**
     * The control for the test above: [Abilities.grant] on its own does **not** adopt.
     *
     * Stated as a test rather than left implicit, because it is the difference the group-aware
     * grant exists to make. If `Abilities.grant` ever started adopting, the previous test would go
     * on passing and would stop meaning anything.
     */
    @Test
    fun `the plain grant does not adopt, which is what the group-aware one is for`() {
        val gas = fixture()
        val unit = gas.unit(abilitySlots = 3)
        unit.abilities.grant(1, gas.fireball)
        unit.recompute(Tick.ZERO)
        unit.activate(1, Tick.ZERO)
        unit.end(1, Tick.ZERO)
        for (tick in 1L..100L) unit.recompute(Tick(tick))
        assertTrue(unit.cooldownRemaining(1, Tick(100)) > 0, "the group is mid-cooldown")

        unit.abilities.grant(2, gas.blink)

        assertEquals(
            0,
            unit.cooldownRemaining(2, Tick(100)),
            "`Abilities.grant` resets the instance and clears its handle - that is the hole",
        )
    }

    /** With no sharing declared, which is every game that does not ask for it, slots are alone. */
    @Test
    fun `the default sharing puts every slot in no group at all`() {
        val gas = GasFixture()
        val unit = gas.unit(abilitySlots = 3)
        unit.abilities.grant(1, gas.fireball)
        unit.abilities.grant(2, gas.blink)
        unit.recompute(Tick.ZERO)

        unit.activate(1, Tick.ZERO)
        unit.end(1, Tick.ZERO)

        assertTrue(unit.cooldownRemaining(1, Tick.ZERO) > 0)
        assertEquals(
            0,
            unit.cooldownRemaining(2, Tick.ZERO),
            "CooldownSharing.None shares nothing, so slot 2 is untouched",
        )
    }

    @Test
    fun `CooldownGroup NONE is not equal to any real group`() {
        assertNotEquals(CooldownGroup.NONE, GROUP)
        assertEquals(CooldownGroup.NONE, CooldownGroup(-1))
        assertEquals("CooldownGroup.NONE", CooldownGroup.NONE.toString())
        assertEquals("CooldownGroup#0", CooldownGroup(0).toString())
    }

    private companion object {
        val GROUP = CooldownGroup(0)
    }
}
