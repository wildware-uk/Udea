package dev.wildware.udea.gas

import dev.wildware.udea.core.identity.NetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * An ability's target is flat, snapshot-safe values — never a Fleks `Entity`.
 *
 * `AbilityTarget` was a sealed class carrying `Entity` and `List<Entity>`
 * (`common/ability/Ability.kt:167-170`). A Fleks entity is a slot index into one world in one
 * process, so a target shipped across the wire or through a snapshot named whatever entity now
 * occupies that slot. Spec 5 says [NetId], never `Entity`, and these are the accessors that make
 * the flat form usable without reintroducing an object per activation.
 */
class AbilityInstanceTest {

    @Test
    fun `a single target is a NetId`() {
        val abilities = Abilities(2)
        abilities.grant(0, 0)
        val instance = abilities.instanceAt(0)

        instance.targetSingle(NetId.of(7, 2))

        assertEquals(AbilityTargetKind.Single, instance.targetKind)
        assertEquals(NetId.of(7, 2), instance.targetId)
    }

    @Test
    fun `a location target is two floats`() {
        val instance = Abilities(1).instanceAt(0)
        instance.targetLocation(3.5f, -2f)

        assertEquals(AbilityTargetKind.Location, instance.targetKind)
        assertEquals(3.5f, instance.targetX)
        assertEquals(-2f, instance.targetY)
    }

    @Test
    fun `a multi target holds ids up to its fixed ceiling and refuses beyond it`() {
        val instance = Abilities(1).instanceAt(0)
        repeat(AbilityInstance.MAX_MULTI_TARGETS) { instance.addMultiTarget(NetId.of(it, 0)) }

        assertEquals(AbilityTargetKind.Multi, instance.targetKind)
        assertEquals(AbilityInstance.MAX_MULTI_TARGETS, instance.multiTargetCount)
        assertEquals(NetId.of(3, 0), instance.multiTargetAt(3))

        val failure = assertFailsWith<IllegalStateException> { instance.addMultiTarget(NetId.of(99, 0)) }
        assertTrue(failure.message!!.contains("at most"), failure.message!!)
    }

    @Test
    fun `clearing a target resets every one of its fields`() {
        val instance = Abilities(1).instanceAt(0)
        instance.targetSingle(NetId.of(4, 0))
        instance.addMultiTarget(NetId.of(5, 0))
        instance.targetLocation(1f, 1f)

        instance.clearTarget()

        assertEquals(AbilityTargetKind.None, instance.targetKind)
        assertEquals(NetId.NONE, instance.targetId)
        assertEquals(0f, instance.targetX)
        assertEquals(0f, instance.targetY)
        assertEquals(0, instance.multiTargetCount)
    }

    @Test
    fun `an ungranted slot is an instance with no ability, not a null`() {
        val abilities = Abilities(3)
        assertTrue(!abilities.instanceAt(2).isGranted)

        abilities.grant(2, 1)
        assertTrue(abilities.instanceAt(2).isGranted)

        abilities.revoke(2)
        assertTrue(!abilities.instanceAt(2).isGranted)
    }

    @Test
    fun `a slot lookup by tag finds the granted ability and is O of one by slot`() {
        val fixture = GasFixture()
        val abilities = Abilities(4)
        abilities.grant(2, fixture.fireball)

        assertEquals(2, abilities.findSlotByTag(fixture.abilityTable, fixture.fireballTag))
        assertEquals(-1, abilities.findSlotByTag(fixture.abilityTable, fixture.stunnedTag))
        assertEquals(fixture.fireball, abilities.instanceAt(2).abilityIndex)
    }

    @Test
    fun `a slot outside the entity's range fails loudly rather than silently`() {
        val abilities = Abilities(2)
        val failure = assertFailsWith<IllegalArgumentException> { abilities.instanceAt(5) }
        assertTrue(failure.message!!.contains("this entity has 2"), failure.message!!)
    }
}
