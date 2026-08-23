package dev.wildware.udea.core.module

import dev.wildware.udea.core.EngineConfig
import dev.wildware.udea.core.physics.PhysicsStepSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Registration is explicit code, and the order it produces is a stated property.
 *
 * Three claims, each of which the old `@UdeaSystem` + `createInstance()` arrangement could not
 * make: a system is built by a call the compiler checked, so it may take real collaborators; a
 * game module can insert a system *between* two engine systems rather than only after them;
 * and the resolved order does not depend on the order the modules happened to declare things.
 */
class SystemOrderTest {

    /** An engine module: one ability system and one attribute system, in their own phases. */
    private class EngineModule : UdeaModule {
        override fun simulation(registry: SimRegistry) {
            registry.add(SimPhase.Ability, { EngineAbilitySystem() })
            registry.add(SimPhase.Attribute, { EngineAttributeSystem() })
        }
    }

    @Test
    fun `a system is constructed with the collaborators its constructor names`() {
        val scoreboard = Scoreboard()
        val module = object : UdeaModule {
            override fun simulation(registry: SimRegistry) {
                // A bare constructor reference: the system takes the context and nothing else.
                registry.add(SimPhase.Gameplay, ::ContextOnlySystem)
                // A constructor taking the context *and* a second collaborator. Neither of
                // these has a no-arg constructor, so neither could exist under createInstance().
                registry.add(SimPhase.Gameplay, { ctx -> ScoringSystem(ctx, scoreboard) })
                // And a system that names the service it needs rather than the whole context.
                registry.add(SimPhase.PostPhysics, { ctx -> SensorSystem(ctx.physics) })
            }
        }

        val game = UdeaGameDef(listOf(module)).build()

        val contextOnly = game.world.system<ContextOnlySystem>()
        val scoring = game.world.system<ScoringSystem>()
        val sensor = game.world.system<SensorSystem>()

        assertSame(game.ctx, contextOnly.context, "the context handed to the factory is the built one")
        assertSame(game.ctx, scoring.context)
        assertSame(scoreboard, scoring.scoreboard, "the collaborator reached the constructor")
        assertSame(game.ctx.physics, sensor.physics, "a system may name a service, not the context")

        game.simulation.step()
        assertEquals(1, contextOnly.ticks)
        assertEquals(1, scoreboard.recorded, "the constructed system actually runs")
    }

    @Test
    fun `a game system resolves after an engine Ability system and before the engine Physics system`() {
        val gameModule = object : UdeaModule {
            override fun simulation(registry: SimRegistry) {
                registry.add(SimPhase.Attribute, { GameBuffSystem() }) {
                    after<EngineAbilitySystem>()
                    before<PhysicsStepSystem>()
                }
            }
        }

        val manifest = UdeaGameDef(listOf(EngineModule(), gameModule)).build().manifest
        val order = manifest.entries.map { it.name }

        val ability = order.indexOf(EngineAbilitySystem::class.java.name)
        val buff = order.indexOf(GameBuffSystem::class.java.name)
        val physics = order.indexOf(PhysicsStepSystem::class.java.name)

        assertTrue(ability >= 0 && buff >= 0 && physics >= 0, "all three systems are in $order")
        assertTrue(ability < buff, "the game system runs after the engine ability system")
        assertTrue(buff < physics, "and before the engine physics step")
    }

    @Test
    fun `three declaration orders yield the identical manifest`() {
        fun manifestFor(order: List<Int>): String {
            val module = object : UdeaModule {
                override fun simulation(registry: SimRegistry) {
                    // The same three systems, the same phase, the same constraints — declared
                    // in whatever sequence `order` names.
                    val declare = listOf<(SimRegistry) -> Unit>(
                        { r -> r.add(SimPhase.Gameplay, { FirstSystem() }) },
                        { r -> r.add(SimPhase.Gameplay, { SecondSystem() }) { after<FirstSystem>() } },
                        { r -> r.add(SimPhase.Gameplay, { ThirdSystem() }) { after<SecondSystem>() } },
                    )
                    for (index in order) declare[index](registry)
                }
            }
            return UdeaGameDef(listOf(module)).build().manifest.render()
        }

        val forwards = manifestFor(listOf(0, 1, 2))
        val backwards = manifestFor(listOf(2, 1, 0))
        val shuffled = manifestFor(listOf(1, 2, 0))

        assertEquals(forwards, backwards, "declaration order must not change the resolved order")
        assertEquals(forwards, shuffled)
        assertTrue(
            forwards.indexOf(FirstSystem::class.java.name) <
                forwards.indexOf(SecondSystem::class.java.name),
            "and the constraints are what decided it:\n$forwards",
        )
    }

    @Test
    fun `registration index breaks the tie between two unconstrained systems`() {
        val module = object : UdeaModule {
            override fun simulation(registry: SimRegistry) {
                registry.add(SimPhase.Gameplay, { SecondSystem() })
                registry.add(SimPhase.Gameplay, { FirstSystem() })
            }
        }

        val order = UdeaGameDef(listOf(module)).build().manifest.entries.map { it.name }
        assertEquals(
            listOf(SecondSystem::class.java.name, FirstSystem::class.java.name),
            order.filter { it.endsWith("SecondSystem") || it.endsWith("FirstSystem") },
            "with no constraint between them, declaration order decides",
        )
    }

    @Test
    fun `phase ordinal decides order regardless of declaration order`() {
        val module = object : UdeaModule {
            override fun simulation(registry: SimRegistry) {
                registry.add(SimPhase.Cleanup, { ThirdSystem() })
                registry.add(SimPhase.Intent, { FirstSystem() })
                registry.add(SimPhase.Gameplay, { SecondSystem() })
            }
        }

        val phases = UdeaGameDef(listOf(module)).build().manifest.entries.map { it.phase }
        assertEquals(phases.sortedBy { it.ordinal }, phases, "phases run in ordinal order: $phases")
    }

    @Test
    fun `the manifest is readable back off the built world`() {
        val game = UdeaGameDef(listOf(EngineModule()), config = EngineConfig(seed = 7L)).build()

        assertEquals(
            game.manifest.render(),
            game.world.systemManifest().render(),
            "world.systemManifest() is the same manifest the build resolved",
        )
        assertEquals(
            game.manifest.size,
            game.world.systems.size,
            "every resolved system was actually added to the world",
        )
        assertEquals(
            game.manifest.entries.map { it.name },
            game.world.systems.map { it::class.java.name },
            "and in the resolved order",
        )
        assertEquals(
            listOf(PhysicsStepSystem::class.java.name),
            game.manifest.inPhase(SimPhase.Physics).map { it.name },
            "a phase can be asked what runs in it, which is what agent introspection needs",
        )
        assertTrue(game.manifest.inPhase(SimPhase.Gameplay).isEmpty(), "nothing was registered there")
    }

    @Test
    fun `the module list is a value, not a classpath scan`() {
        val def = UdeaGameDef(listOf(EngineModule()))

        assertEquals(
            listOf("core", "EngineModule"),
            def.allModules.map { it.name },
            "CoreModule is implicit and first, so its services exist before a game module runs",
        )
    }
}
