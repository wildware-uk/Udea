package dev.wildware.udea.gas

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Cooldowns are tick counts read back through a handle that survives a rewind.
 *
 * The old cooldown was a gameplay effect found by a handle from a process-wide counter
 * (`Ability.kt:82`, set at `:134`, read at `:151`). After a rewind the handle resolved to nothing
 * and `canCast()` returned true — the ability came off cooldown early, and nothing said so.
 */
class CooldownTickTest {

    private fun readyUnit(fixture: GasFixture): GasFixture.Unit {
        val unit = fixture.unit()
        unit.abilities.grant(0, fixture.fireball)
        unit.recompute(Tick.ZERO)
        return unit
    }

    @Test
    fun `a nine hundred tick cooldown counts down monotonically and ends on exactly zero`() {
        val fixture = GasFixture()
        val unit = readyUnit(fixture)

        assertEquals(ActivationResult.Activated, unit.activate(0, Tick.ZERO))
        unit.end(0, Tick.ZERO)
        assertEquals(900, unit.cooldownRemaining(0, Tick.ZERO))

        var previous = 900
        for (tick in 1L..899L) {
            unit.recompute(Tick(tick))
            val remaining = unit.cooldownRemaining(0, Tick(tick))
            assertEquals(previous - 1, remaining, "cooldown must fall by exactly one per tick at $tick")
            assertTrue(
                unit.canActivate(0, Tick(tick)) is ActivationResult.OnCooldown,
                "still cooling down at tick $tick",
            )
            previous = remaining
        }

        unit.recompute(Tick(900))
        assertEquals(0, unit.cooldownRemaining(0, Tick(900)))
        assertEquals(
            ActivationResult.Activated,
            unit.canActivate(0, Tick(900)),
            "activatable on exactly the tick the remainder reaches zero",
        )
    }

    @Test
    fun `an on-cooldown refusal carries the remaining ticks`() {
        val fixture = GasFixture()
        val unit = readyUnit(fixture)
        unit.activate(0, Tick.ZERO)
        unit.end(0, Tick.ZERO)
        unit.recompute(Tick(100))

        val result = unit.canActivate(0, Tick(100))
        assertEquals(ActivationResult.OnCooldown(800), result)
    }

    @Test
    fun `a rewind restores the remaining cooldown and the handle still resolves`() {
        val fixture = GasFixture()
        val unit = readyUnit(fixture)
        unit.activate(0, Tick.ZERO)
        unit.end(0, Tick.ZERO)

        // Tick 600: 300 to go.
        for (tick in 1L..600L) unit.recompute(Tick(tick))
        assertEquals(300, unit.cooldownRemaining(0, Tick(600)))

        val captured = GasState.capture(unit, fixture.handles)

        // Run past it, then rewind sixty ticks and fast-forward back.
        for (tick in 601L..900L) unit.recompute(Tick(tick))
        assertEquals(0, unit.cooldownRemaining(0, Tick(900)))

        GasState.restore(captured, unit, fixture.handles)
        for (tick in 541L..600L) unit.recompute(Tick(tick))

        assertEquals(
            300,
            unit.cooldownRemaining(0, Tick(600)),
            "after a rewind the remainder is what it was, not zero",
        )
        assertTrue(
            unit.abilities.instanceAt(0).cooldownHandle in unit.effects,
            "the instance's handle must still resolve to the restored cooldown effect",
        )
    }

    @Test
    fun `cooldown reduction is exact integer arithmetic`() {
        val fixture = GasFixture()
        val unit = readyUnit(fixture)
        unit.attributes.setBase(fixture.cooldownReduction, 20f)
        unit.recompute(Tick.ZERO)

        assertEquals(ActivationResult.Activated, unit.activate(0, Tick.ZERO))
        assertEquals(
            720,
            unit.cooldownRemaining(0, Tick.ZERO),
            "20% off 900 ticks is exactly 720, computed without a float in the result",
        )
    }

    @Test
    fun `cooldown reduction is capped so stacking cannot reach zero`() {
        val fixture = GasFixture()
        val unit = readyUnit(fixture)
        unit.attributes.setBase(fixture.cooldownReduction, 500f)
        unit.recompute(Tick.ZERO)

        unit.activate(0, Tick.ZERO)
        assertEquals(
            180,
            unit.cooldownRemaining(0, Tick.ZERO),
            "reduction is capped at 80%, so 900 ticks never falls below 180",
        )
    }
}

/**
 * Activation is refused, with a reason, before anything is mutated.
 *
 * `checkCosts()` at `Ability.kt:145-147` was an empty function body, so an ability fired with zero
 * mana and drove the resource negative; and `canCast()` returned a bare `Boolean`, so neither a HUD
 * nor an agent could say why a button was dead.
 */
class ActivationGatingTest {

    @Test
    fun `too little mana refuses, names the resource, and changes nothing`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.abilities.grant(0, fixture.fireball)
        unit.attributes.setBase(fixture.mana, 10f)
        unit.recompute(Tick.ZERO)

        val result = unit.activate(0, Tick(1))

        assertEquals(ActivationResult.InsufficientResource(fixture.mana, 30f, 10f), result)
        assertEquals(10f, unit.attributes.base(fixture.mana), "a refused activation spends nothing")
        assertEquals(0, unit.effects.count, "no cost effect and no cooldown effect were applied")
        assertEquals(0, unit.cooldownRemaining(0, Tick(1)))
        assertEquals(0, fixture.cues.size, "and no cue was emitted")
        assertEquals(0, fixture.exec.activations, "and the exec never ran")
    }

    @Test
    fun `enough mana activates and spends it`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.abilities.grant(0, fixture.fireball)
        unit.recompute(Tick.ZERO)

        assertEquals(ActivationResult.Activated, unit.activate(0, Tick(1)))
        unit.recompute(Tick(1))

        assertEquals(70f, unit.attributes.base(fixture.mana), "the 30-mana cost was applied once")
        assertEquals(1, fixture.exec.activations)
    }

    @Test
    fun `a stunned entity is refused, and the tag is named`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.abilities.grant(0, fixture.fireball)
        unit.apply(fixture.stunEffect, Tick.ZERO)
        unit.recompute(Tick.ZERO)

        assertEquals(ActivationResult.BlockedByTag(fixture.stunnedTag), unit.canActivate(0, Tick(1)))
    }

    @Test
    fun `a blocking tag applied mid-cast cancels the in-flight ability`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.abilities.grant(0, fixture.blink)
        unit.recompute(Tick.ZERO)
        unit.activate(0, Tick(1))
        assertTrue(unit.abilities.instanceAt(0).isActive)

        unit.tickAbilities(Tick(2))
        assertTrue(unit.abilities.instanceAt(0).isActive, "nothing blocks it yet")

        unit.apply(fixture.stunEffect, Tick(3))
        unit.tickAbilities(Tick(3))

        assertEquals(AbilityPhase.Inactive, unit.abilities.instanceAt(0).phase)
        assertEquals(
            1,
            fixture.channelled.cancellations,
            "the exec is told it was cancelled, not ended cleanly",
        )
    }

    @Test
    fun `an ungranted slot refuses without touching anything`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        assertEquals(ActivationResult.NotGranted, unit.activate(1, Tick.ZERO))
        assertEquals(0, unit.effects.count)
    }

    @Test
    fun `an entity this simulation does not control refuses`() {
        val controlled = NetId.of(1, 0)
        val fixture = GasFixture(authority = AbilityAuthority { it == controlled })
        val other = fixture.unit(index = 2)
        other.abilities.grant(0, fixture.fireball)
        other.recompute(Tick.ZERO)

        assertEquals(ActivationResult.NoAuthority, other.activate(0, Tick.ZERO))

        val mine = fixture.unit(index = 1)
        mine.abilities.grant(0, fixture.fireball)
        mine.recompute(Tick.ZERO)
        assertEquals(ActivationResult.Activated, mine.activate(0, Tick.ZERO))
    }

    @Test
    fun `an already-running ability refuses rather than restarting`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.abilities.grant(0, fixture.blink)
        unit.recompute(Tick.ZERO)
        assertEquals(ActivationResult.Activated, unit.activate(0, Tick.ZERO))
        assertEquals(ActivationResult.AlreadyActive, unit.activate(0, Tick(1)))
    }
}

/**
 * Two entities running one ability keep independent state, because the exec holds none.
 *
 * `AbilitySpec` built an executor per spec with `kotlin.reflect.full.createInstance`
 * (`Ability.kt:76`) and kept the activation's state on it, marked `@Transient`. Here there is one
 * executor object for the whole game and the state is on the [AbilityInstance], which is why a
 * snapshot can carry a half-finished cast.
 */
class AbilityExecStatelessTest {

    @Test
    fun `two entities running the same ability produce independent outcomes`() {
        val fixture = GasFixture()
        val first = fixture.unit(index = 1)
        val second = fixture.unit(index = 2)
        for (unit in listOf(first, second)) {
            unit.abilities.grant(0, fixture.blink)
            unit.recompute(Tick.ZERO)
        }

        first.activate(0, Tick.ZERO)
        for (tick in 1L..3L) first.tickAbilities(Tick(tick))

        second.activate(0, Tick(4))
        second.tickAbilities(Tick(5))

        assertNotEquals(
            first.abilities.instanceAt(0).scratchInts[0],
            second.abilities.instanceAt(0).scratchInts[0],
            "the two casts are at different points, so the shared exec must hold no state",
        )
        // The activation counter is per entity, not global — an activation is identified by
        // (entity, slot, instanceId) — so both first casts are #1, and a *second* cast on one
        // entity is what has to differ.
        assertEquals(1, first.abilities.instanceAt(0).instanceId)
        assertEquals(1, second.abilities.instanceAt(0).instanceId)

        first.end(0, Tick(6))
        first.activate(0, Tick(6))
        assertEquals(2, first.abilities.instanceAt(0).instanceId)
        assertEquals(1, second.abilities.instanceAt(0).instanceId, "the other entity is untouched")
    }

    @Test
    fun `the registry hands back one shared instance per exec class`() {
        val fixture = GasFixture()
        val id = fixture.execs.idOf(fixture.exec)
        assertSame(fixture.execs.execAt(id), fixture.execs.execAt(id))
        assertSame(fixture.exec, fixture.execs.execAt(id))
    }

    @Test
    fun `exec ids come from sorted class names, so two builds agree`() {
        val forwards = AbilityExecRegistry.of(listOf(RecordingExec(), ChannelledExec()))
        val backwards = AbilityExecRegistry.of(listOf(ChannelledExec(), RecordingExec()))
        assertEquals(
            forwards.idOf(RecordingExec::class.java.name),
            backwards.idOf(RecordingExec::class.java.name),
        )
    }
}

/**
 * A cast interrupted by a snapshot resumes where it left off.
 *
 * Everything an activation knows is a primitive on the instance, so capturing it is copying
 * numbers. The old spec kept the same state in `@Transient` fields and a closure, so a restore
 * produced an entity that had never cast anything.
 */
class AbilityMidCastSnapshotTest {

    @Test
    fun `a mid-cast instance restored into a fresh component finishes identically`() {
        val fixture = GasFixture()
        val original = fixture.unit()
        original.abilities.grant(0, fixture.blink)
        original.recompute(Tick.ZERO)
        original.activate(0, Tick.ZERO)
        original.tickAbilities(Tick(1))

        val instance = original.abilities.instanceAt(0)
        assertEquals(AbilityPhase.AwaitingTarget, instance.phase, "the cast is mid-flight")

        val restored = fixture.unit()
        GasState.captureInstance(instance).restoreInto(restored.abilities.instanceAt(0))
        restored.abilities.instanceAt(0).abilityIndex = instance.abilityIndex

        for (tick in 2L..6L) {
            original.tickAbilities(Tick(tick))
            restored.tickAbilities(Tick(tick))
        }

        assertEquals(original.abilities.instanceAt(0).phase, restored.abilities.instanceAt(0).phase)
        assertEquals(
            original.abilities.instanceAt(0).scratchInts[0],
            restored.abilities.instanceAt(0).scratchInts[0],
            "the restored cast must be at the same point as the uninterrupted one",
        )
        assertEquals(AbilityPhase.Inactive, restored.abilities.instanceAt(0).phase, "and must finish")
    }

    @Test
    fun `a client is granted the same abilities as the server`() {
        // The old code returned early from grantAbility unless gameScreen.isServer, so a client
        // held no specs at all and prediction was structurally impossible. Granting is now a plain
        // write with no authority check; only *activation* consults authority.
        val server = GasFixture()
        val client = GasFixture(authority = AbilityAuthority { false })

        val serverUnit = server.unit().also { it.abilities.grant(0, server.fireball) }
        val clientUnit = client.unit().also { it.abilities.grant(0, client.fireball) }

        assertEquals(
            serverUnit.abilities.instanceAt(0).abilityIndex,
            clientUnit.abilities.instanceAt(0).abilityIndex,
        )
        assertTrue(clientUnit.abilities.instanceAt(0).isGranted, "a client holds the instance")
        assertEquals(
            ActivationResult.NoAuthority,
            clientUnit.activate(0, Tick.ZERO),
            "it just may not activate it on its own authority",
        )
    }
}
