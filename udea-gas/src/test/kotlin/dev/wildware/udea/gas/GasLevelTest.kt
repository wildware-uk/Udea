package dev.wildware.udea.gas

import dev.wildware.udea.core.EngineConfig
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.level.LevelFormatException
import dev.wildware.udea.core.level.LevelOutcome
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.module.UdeaGame
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * GAS state survives a level file, and a loaded game goes on to play the ticks the saved one would
 * have (issue #191).
 *
 * GAS is where a level is most easily wrong in a way nothing notices at once: an effect list grown
 * past its first capacity, an ability part-way through a cast holding scratch state, an attribute
 * vector that means nothing without its table, and an effect-handle counter that lives in a
 * service rather than any component. Each test starts from a game that has all of them.
 */
class GasLevelTest {

    private class Game(attributes: AttributeTable? = null) {
        val fixture = GasFixture()
        val module = GasModule(
            attributes = attributes ?: fixture.attributeTable,
            effects = fixture.effectTable,
            abilities = fixture.abilityTable,
            execs = fixture.execs,
        )
        val def = UdeaGameDef(modules = listOf(module), config = EngineConfig(seed = 20_260_916L))
        val game: UdeaGame = def.build()

        fun step(ticks: Int) = repeat(ticks) { game.simulation.step() }

        fun save(): ByteArray {
            val action = game.levels.save(game.simulation.barrier)
            game.simulation.barrier.drain(game.world, game.ctx)
            return assertIs<LevelOutcome.Completed<ByteArray>>(action.outcome, "save: ${action.outcome}").value
        }

        fun load(bytes: ByteArray) {
            val action = game.levels.load(game.levels.read(bytes), game.simulation.barrier)
            game.simulation.barrier.drain(game.world, game.ctx)
            assertIs<LevelOutcome.Completed<*>>(action.outcome, "load: ${action.outcome}")
        }

        fun entity(netId: NetId) = def.core.netIds.resolveOrNull(netId)!!

        fun effects(netId: NetId): GameplayEffects = with(game.world) { entity(netId)[GameplayEffects] }

        fun attributes(netId: NetId): Attributes = with(game.world) { entity(netId)[Attributes] }

        fun abilities(netId: NetId): Abilities = with(game.world) { entity(netId)[Abilities] }
    }

    /** Two units fighting: one cooling down under an effect list grown past its capacity, one mid-channel. */
    private fun busy(game: Game): List<NetId> {
        val ids = List(2) {
            val entity = game.game.world.entity {
                it += Attributes(game.fixture.attributeTable)
                it += GameplayEffects()
                it += Abilities(2)
            }
            game.def.core.netIds.allocate(entity)
        }
        with(game.game.world) {
            game.entity(ids[0])[Abilities].grant(0, game.fixture.fireball)
            game.entity(ids[1])[Abilities].grant(0, game.fixture.blink)
        }
        game.step(3)
        val now = game.game.ctx.tick
        val fireball = game.module.activation.activate(
            ids[0], game.abilities(ids[0]), game.attributes(ids[0]), game.effects(ids[0]), 0, now,
        )
        val blink = game.module.activation.activate(
            ids[1], game.abilities(ids[1]), game.attributes(ids[1]), game.effects(ids[1]), 0, now,
        )
        assertEquals(ActivationResult.Activated to ActivationResult.Activated, fireball to blink, "the fixture could not start its casts")
        repeat(GameplayEffects.DEFAULT_CAPACITY + 4) {
            game.module.applier.begin(game.fixture.hasteEffect)
                .applyTo(game.effects(ids[0]), game.attributes(ids[0]), now, targetId = ids[0], source = ids[1])
        }
        game.module.applier.begin(game.fixture.regenEffect)
            .applyTo(game.effects(ids[1]), game.attributes(ids[1]), now, targetId = ids[1])
        game.step(2)
        assertTrue(
            game.effects(ids[0]).count > GameplayEffects.DEFAULT_CAPACITY,
            "the effect list never grew past its first capacity; the fixture is not exercising it",
        )
        assertTrue(game.abilities(ids[1]).instanceAt(0).isActive, "the channel ended before the save")
        return ids
    }

    @Test
    fun `a loaded game plays on exactly as the saved one does, new effect handles included`() {
        val original = Game()
        val ids = busy(original)
        val bytes = original.save()

        val fresh = Game()
        fresh.load(bytes)
        assertContentEquals(bytes, fresh.save(), "saving the loaded game did not reproduce the level")

        // Play both on: the channel ends, the regen ticks, and a new effect takes the next handle.
        original.step(40)
        fresh.step(40)
        val originalHandle = original.module.applier.begin(original.fixture.slowEffect)
            .applyTo(original.effects(ids[1]), original.attributes(ids[1]), original.game.ctx.tick)
        val freshHandle = fresh.module.applier.begin(fresh.fixture.slowEffect)
            .applyTo(fresh.effects(ids[1]), fresh.attributes(ids[1]), fresh.game.ctx.tick)

        assertEquals(originalHandle, freshHandle, "the next effect handle after the load")
        assertContentEquals(original.attributes(ids[1]).current, fresh.attributes(ids[1]).current)
        assertContentEquals(original.save(), fresh.save(), "the two games after forty more ticks")
    }

    @Test
    fun `a level saved against other attributes is refused, naming them`() {
        val original = Game()
        busy(original)
        val bytes = original.save()

        val renamed = AttributeTableBuilder().apply {
            add(AttributeDecl("game.Character.armour", defaultBase = 0f), "game")
            add(AttributeDecl("game.Character.health", defaultBase = 100f), "game")
            add(AttributeDecl("game.Character.mana", defaultBase = 100f), "game")
            add(AttributeDecl("game.Character.maxHealth", defaultBase = 100f), "game")
            add(AttributeDecl("game.Character.moveSpeed", defaultBase = 10f), "game")
        }.build()
        val other = Game(attributes = renamed)

        val refused = assertFailsWith<LevelFormatException> { other.game.levels.read(bytes) }
        assertContains(refused.message.orEmpty(), "game.Character.armour")
    }
}
