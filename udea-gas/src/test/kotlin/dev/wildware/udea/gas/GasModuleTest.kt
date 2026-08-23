package dev.wildware.udea.gas

import dev.wildware.udea.core.Cue
import dev.wildware.udea.core.CueQueue
import dev.wildware.udea.core.EngineConfig
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.module.systemManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The module actually runs inside a real world, driven by the real `WorldSimulation`.
 *
 * Every other test in this module calls [AttributeRecompute] and [AbilityActivation] directly,
 * which is right — those are where the rules live — but it would leave the *wiring* untested, and
 * an unregistered system is the exact failure mode `@UdeaSystem(runIn = [...])` used to produce: a
 * system that had quietly stopped running, with nothing saying so.
 *
 * So this builds a `UdeaGameDef` with `CoreModule` and [GasModule], spawns entities into the built
 * world and calls `simulation.step()`. Nothing is stubbed: real Fleks families, the real phase
 * order, the real tick loop.
 */
class GasModuleTest {

    private class Harness(entities: Int = 3) {
        val fixture = GasFixture()

        val module = GasModule(
            attributes = fixture.attributeTable,
            effects = fixture.effectTable,
            abilities = fixture.abilityTable,
            execs = fixture.execs,
        )

        val def = UdeaGameDef(modules = listOf(module), config = EngineConfig(seed = 20_260_823L))

        val game = def.build()

        val netIds: List<NetId> = List(entities) { index ->
            val entity = game.world.entity {
                it += Attributes(fixture.attributeTable)
                it += GameplayEffects()
                it += Abilities(2)
            }
            def.core.netIds.allocate(entity).also { _ ->
                with(game.world) {
                    entity[Abilities].grant(0, fixture.blink)
                    entity[Attributes].setBase(fixture.health, 0f)
                }
            }
        }

        fun entityAt(index: Int) = def.core.netIds.resolveOrNull(netIds[index])!!

        fun attributes(index: Int): Attributes = with(game.world) { entityAt(index)[Attributes] }

        fun effects(index: Int): GameplayEffects = with(game.world) { entityAt(index)[GameplayEffects] }

        fun abilities(index: Int): Abilities = with(game.world) { entityAt(index)[Abilities] }
    }

    @Test
    fun `the module registers both systems, in the phases the tick order expects`() {
        val harness = Harness()
        val manifest = harness.game.world.systemManifest()

        val ability = manifest.inPhase(SimPhase.Ability).map { it.name }
        val attribute = manifest.inPhase(SimPhase.Attribute).map { it.name }

        assertEquals(listOf(AbilitySystem::class.java.name), ability)
        assertEquals(listOf(AttributeSystem::class.java.name), attribute)
        assertEquals(
            listOf(GasCueForwardSystem::class.java.name),
            manifest.inPhase(SimPhase.Cleanup).map { it.name },
            "the cue forwarder is what connects GAS cues to GameContext.cues",
        )
        assertTrue(
            manifest.entries.indexOfFirst { it.name == AbilitySystem::class.java.name } <
                manifest.entries.indexOfFirst { it.name == AttributeSystem::class.java.name },
            "abilities apply this tick's effects before attributes aggregate them",
        )
    }

    @Test
    fun `the services are reachable through the context, not through a global`() {
        val first = Harness()
        val second = Harness()

        assertEquals(first.module.services, first.game.ctx[GasServices.KEY])
        assertTrue(
            first.game.ctx[GasServices.KEY] !== second.game.ctx[GasServices.KEY],
            "two games in one JVM must not share GAS state",
        )
    }

    @Test
    fun `stepping the world recomputes attributes for every entity`() {
        val harness = Harness()
        for (index in 0 until 3) {
            harness.module.applier.begin(harness.fixture.hasteEffect)
                .applyTo(harness.effects(index), harness.attributes(index), Tick.ZERO)
        }

        harness.game.simulation.step()

        for (index in 0 until 3) {
            assertEquals(
                15f,
                harness.attributes(index).current(harness.fixture.moveSpeed),
                "entity $index was not recomputed by the registered system",
            )
        }
    }

    @Test
    fun `stepping the world ticks an in-flight ability and expires an effect on its tick`() {
        val harness = Harness()
        val self = harness.netIds[0]
        harness.module.activation.activate(
            self,
            harness.abilities(0),
            harness.attributes(0),
            harness.effects(0),
            slot = 0,
            now = harness.game.ctx.tick,
        )
        assertTrue(harness.abilities(0).instanceAt(0).isActive)

        harness.module.applier.begin(harness.fixture.hasteEffect)
            .applyTo(harness.effects(0), harness.attributes(0), harness.game.ctx.tick)

        // `step()` runs the systems for the tick the clock currently reads and *then* advances, so
        // after N steps ticks 0 until N have been simulated and the clock reads N.
        repeat(5) { harness.game.simulation.step() }
        assertEquals(
            AbilityPhase.Inactive,
            harness.abilities(0).instanceAt(0).phase,
            "the registered AbilitySystem must be ticking the instance",
        )
        assertEquals(1, harness.effects(0).count, "the haste is still running after five ticks")

        repeat(25) { harness.game.simulation.step() }
        assertEquals(Tick(30), harness.game.ctx.tick, "ticks 0 until 30 have run; tick 30 has not")
        assertEquals(1, harness.effects(0).count, "applied at tick 0, it lives through tick 29")

        harness.game.simulation.step()
        assertEquals(0, harness.effects(0).count, "and is swept on the tick it expires, tick 30")
        assertEquals(0, harness.module.handles.liveCount, "its handle went back to the allocator")
    }

    @Test
    fun `a GAS cue reaches the kernel cue sink that presentation drains`() {
        // The seam, driven. GAS emits into its own GasCueQueue; udea-render and audio drain
        // GameContext.cues. Before GasCueForwardSystem those were two unconnected queues, so an
        // assembled game played no ability cue at all - and every test in CueTest passed, because
        // each one reads the GAS queue directly.
        val harness = Harness()
        val kernel = harness.game.ctx.cues as CueQueue
        assertEquals(0, kernel.size, "the kernel queue is not empty before the tick")

        harness.module.applier.begin(harness.fixture.damageEffect)
            .applyTo(harness.effects(0), harness.attributes(0), harness.game.ctx.tick, source = harness.netIds[0])
        assertEquals(1, harness.module.cues.size, "the effect emitted no GAS cue to forward")

        harness.game.simulation.step()

        val drained = ArrayList<Cue>()
        kernel.drain { drained += it }
        assertEquals(1, drained.size, "no GAS cue reached GameContext.cues")
        assertEquals(GasFixture.DAMAGE_CUE, drained.single().id.raw)
        assertEquals(harness.netIds[0], drained.single().source)
        assertEquals(
            0,
            harness.module.cues.size,
            "the GAS queue was not emptied, so it grows without bound when nobody drains it",
        )
    }

    @Test
    fun `a periodic effect fires on the loop's own cadence`() {
        val harness = Harness()
        harness.module.applier.begin(harness.fixture.regenEffect)
            .applyTo(harness.effects(0), harness.attributes(0), Tick.ZERO)

        // Ticks 0 until 60 run, so periods land on 15, 30 and 45 — tick 60 has not been simulated.
        repeat(60) { harness.game.simulation.step() }
        assertEquals(15f, harness.attributes(0).base(harness.fixture.health))

        harness.game.simulation.step()
        assertEquals(
            20f,
            harness.attributes(0).base(harness.fixture.health),
            "the fourth period lands on tick 60, the sixty-first tick simulated",
        )
    }
}
