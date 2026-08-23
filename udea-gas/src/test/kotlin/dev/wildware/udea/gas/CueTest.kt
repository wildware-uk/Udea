package dev.wildware.udea.gas

import dev.wildware.udea.core.Cue
import dev.wildware.udea.core.CueSink
import dev.wildware.udea.core.Tick
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Applying an effect emits flat cue events and touches nothing else.
 *
 * `Abilities.applyGameplayEffect` called cues inline (`common/.../Abilities.kt:61-67`) and the cues
 * reached for `world.system<SoundSystem>()` and `AnimationSetSystem`
 * (`example/.../DamageCue.kt:26-31`), so applying an effect in `RenderMode.Headless` touched audio
 * and animation — which spec 3.5 forbids — and rewinding replayed it.
 *
 * That audio and animation are unreachable from here is enforced structurally rather than by a
 * stub: `udea-gas` depends on `udea-core` alone, and [GasArchitectureTest] fails the build if a
 * source file so much as names LibGDX or `udea-render`. What these tests add is the positive half —
 * that the events carry what presentation needs, and that suppression and de-duplication work.
 */
class HeadlessCueTest {

    @Test
    fun `applying a cued effect emits one flat event carrying its identity`() {
        val fixture = GasFixture()
        val unit = fixture.unit()

        val handle = unit.apply(fixture.damageEffect, Tick(12), fixture.damageTag to -25f)

        assertEquals(1, fixture.cues.size)
        val event = fixture.cues.eventAt(0)
        assertEquals(GasFixture.DAMAGE_CUE, event.cueId)
        assertEquals(Tick(12), event.tick)
        assertEquals(unit.netId, event.source)
        assertEquals(unit.netId, event.target)
        assertEquals(handle, event.effectHandle)
    }

    @Test
    fun `an effect with no cues emits nothing`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.apply(fixture.hasteEffect, Tick.ZERO)
        assertEquals(0, fixture.cues.size)
    }

    @Test
    fun `a cue is also forwarded to the kernel sink as a plain Cue`() {
        val recorded = mutableListOf<Cue>()
        val sink = object : CueSink {
            override fun emit(cue: Cue) {
                recorded += cue
            }
        }
        val cues = GasCueQueue(sink = sink)
        val fixture = GasFixture()
        val applier = EffectApplier(fixture.effectTable, fixture.handles, cues)
        val unit = fixture.unit()

        applier.begin(fixture.damageEffect).applyTo(unit.effects, unit.attributes, Tick(3))

        assertEquals(1, recorded.size)
        assertEquals(GasFixture.DAMAGE_CUE, recorded.single().id.raw)
        assertEquals(Tick(3), recorded.single().tick)
    }

    @Test
    fun `draining empties the queue and hands events over in emission order`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.apply(fixture.damageEffect, Tick(1), fixture.damageTag to -1f)
        unit.apply(fixture.damageEffect, Tick(2), fixture.damageTag to -2f)

        val ticks = mutableListOf<Tick>()
        val drained = fixture.cues.drain { ticks += it.tick }

        assertEquals(2, drained)
        assertContentEquals(listOf(Tick(1), Tick(2)), ticks)
        assertEquals(0, fixture.cues.size)
    }
}

/**
 * Re-applying the same effect emits its cue again.
 *
 * `Abilities.applyGameplayEffect` computed `alreadyApplied` *before* inserting the new spec (`:56`)
 * and consulted it *after* (`:60`) — so the flag was always true by the time it was read and the
 * second application of an effect played no cue at all. Two applications, two cues.
 */
class RepeatedApplicationCueTest {

    @Test
    fun `applying damage twice emits two cue events`() {
        val fixture = GasFixture()
        val unit = fixture.unit()

        unit.apply(fixture.damageEffect, Tick(1), fixture.damageTag to -10f)
        unit.apply(fixture.damageEffect, Tick(2), fixture.damageTag to -10f)

        assertEquals(2, fixture.cues.size, "the second application must still play its cue")
        assertEquals(GasFixture.DAMAGE_CUE, fixture.cues.eventAt(0).cueId)
        assertEquals(GasFixture.DAMAGE_CUE, fixture.cues.eventAt(1).cueId)
        assertTrue(
            fixture.cues.eventAt(0).effectHandle != fixture.cues.eventAt(1).effectHandle,
            "they are distinct applications, so they carry distinct handles",
        )
    }

    @Test
    fun `a server confirmation does not replay a cue the client already predicted`() {
        val cues = GasCueQueue()
        cues.emit(cueId = 3, tick = Tick(10), effectHandle = EffectHandle(5), predictionKey = 42)
        val replayed = cues.emit(cueId = 3, tick = Tick(12), effectHandle = EffectHandle(5), predictionKey = 42)

        assertTrue(!replayed, "the confirmation is a duplicate of the predicted cue")
        assertEquals(1, cues.size)
        assertEquals(1L, cues.deduplicatedCount)
    }
}

/**
 * Suppression is what makes rollback re-simulation and fast-forward usable.
 *
 * Without it, rewinding sixty seconds and re-simulating replays sixty seconds of sound. With it, a
 * re-simulation produces identical *state* and zero cues — which is exactly the property both
 * rollback and the agent's `fast_forward` need.
 */
class CueSuppressionTest {

    @Test
    fun `rewinding through an application and re-simulating emits the cue once in total`() {
        val fixture = GasFixture()
        val unit = fixture.unit()

        val captured = GasState.capture(unit, fixture.handles)
        unit.apply(fixture.damageEffect, Tick(50), fixture.damageTag to -10f)
        for (tick in 50L..150L) unit.recompute(Tick(tick))
        assertEquals(1, fixture.cues.size)

        // Rewind: pending cues go, and the re-simulation runs suppressed.
        fixture.cues.rewind()
        GasState.restore(captured, unit, fixture.handles)
        fixture.cues.suppressed {
            unit.apply(fixture.damageEffect, Tick(50), fixture.damageTag to -10f)
            for (tick in 50L..150L) unit.recompute(Tick(tick))
        }

        assertEquals(0, fixture.cues.size, "the replayed application must not play a second time")
        assertEquals(1L, fixture.cues.suppressedCount)
        assertEquals(CueMode.Emit, fixture.cues.mode, "and the mode is restored afterwards")
    }

    @Test
    fun `a fast-forward emits no cues and reaches the same state as an un-suppressed run`() {
        val loud = GasFixture()
        val loudUnit = loud.unit()
        loudUnit.attributes.setBase(loud.health, 0f)
        loudUnit.apply(loud.regenEffect, Tick.ZERO)
        for (tick in 1L..600L) {
            loudUnit.apply(loud.damageEffect, Tick(tick), loud.damageTag to -1f)
            loudUnit.recompute(Tick(tick))
        }

        val quiet = GasFixture()
        val quietUnit = quiet.unit()
        quietUnit.attributes.setBase(quiet.health, 0f)
        quietUnit.apply(quiet.regenEffect, Tick.ZERO)
        quiet.cues.suppressed {
            for (tick in 1L..600L) {
                quietUnit.apply(quiet.damageEffect, Tick(tick), quiet.damageTag to -1f)
                quietUnit.recompute(Tick(tick))
            }
        }

        assertEquals(0, quiet.cues.size, "a fast-forward is silent")
        assertTrue(loud.cues.size > 0, "the un-suppressed run is not silent, or this proves nothing")
        assertContentEquals(
            loudUnit.attributes.base,
            quietUnit.attributes.base,
            "suppressing cues must not change what the simulation computes",
        )
        assertEquals(loud.handles.next, quiet.handles.next)
    }

    @Test
    fun `a full queue drops the newest and counts it rather than growing`() {
        val cues = GasCueQueue(capacity = 4)
        repeat(10) { cues.emit(cueId = it, tick = Tick(it.toLong())) }

        assertEquals(4, cues.size)
        assertEquals(6L, cues.droppedCount)
        assertEquals(0, cues.eventAt(0).cueId, "the oldest cues are the ones kept")
    }
}

/**
 * A cue never enters a snapshot, so capture and restore leave the queue exactly as they found it.
 */
class CueQueueTransienceTest {

    @Test
    fun `capturing and restoring with a non-empty queue changes neither the queue nor the state`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.apply(fixture.damageEffect, Tick(1), fixture.damageTag to -5f)
        unit.recompute(Tick(1))
        assertEquals(1, fixture.cues.size)

        val captured = GasState.capture(unit, fixture.handles)
        val baseAtCapture = unit.attributes.base.copyOf()

        GasState.restore(captured, unit, fixture.handles)

        assertEquals(1, fixture.cues.size, "capture and restore do not touch the queue")
        assertContentEquals(baseAtCapture, unit.attributes.base)
    }

    @Test
    fun `a rewind clears pending cues and the de-duplication window`() {
        val cues = GasCueQueue()
        cues.emit(cueId = 1, tick = Tick.ZERO, effectHandle = EffectHandle(1))
        assertEquals(1, cues.size)

        cues.rewind()
        assertEquals(0, cues.size)

        val reEmitted = cues.emit(cueId = 1, tick = Tick.ZERO, effectHandle = EffectHandle(1))
        assertTrue(reEmitted, "after a rewind the same cue is a new cue, not a duplicate")
    }
}
