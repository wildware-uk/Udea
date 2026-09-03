package dev.wildware.moba

import dev.wildware.moba.ability.MobaAbilities
import dev.wildware.moba.ability.UnitBlueprint
import dev.wildware.moba.entry.MobaEntry
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.render.input.InjectedIntent
import dev.wildware.udea.render.input.IntentState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The two things a human said were missing: a second ability key, and somewhere to read a cooldown.
 *
 * ## Why the assertions are on [MobaHudModel] and not on pixels
 *
 * The claim being made is "a player can see their health and their cooldowns", and that claim is
 * false if the numbers are wrong however good the boxes look. [MobaHudModel] is the half of the
 * HUD that produces the numbers, it needs no GL context, and everything below runs in
 * [RenderMode.Headless] with no window in the process - the same property `MobaInputTest` rests
 * on. What is left untested is the drawing, and it draws nothing it was not handed here.
 *
 * ## The input is the agent's source, on purpose
 *
 * [InjectedIntent] is what `input.tap` drives, and it is the *same* `IntentState` a keyboard
 * writes: `MobaInputTest` proves those two paths land the player on the same coordinate. So a
 * press here is a press, and nothing in `PlayerControlSystem` can tell which one made it.
 */
class MobaHudTest {

    /** The HUD reports the player's real health, off the attribute the fight writes. */
    @Test
    fun `the hud reports the player's health out of the unit's attributes`() {
        val fixture = Fixture()

        val state = fixture.sample()

        assertTrue(state.alive, "the level seeded no player, so the HUD has nothing to show")
        // The unit a human drives is `blueprint/player`, which inherits `orc_elite` -
        // exactly as it did in the old game. Asserting `soldier` here is what this test
        // did first, and it is what caught the assumption.
        assertEquals("orc_elite", state.unitName)
        assertEquals(PLAYER_HEALTH, state.maxHealth, absoluteTolerance = 1e-3f)
        assertTrue(
            state.health > 0f && state.health <= state.maxHealth,
            "health ${state.health} is not inside 0..${state.maxHealth}",
        )
    }

    /**
     * The kind's slots are named, so a player can see what they have before pressing anything,
     * and the item bar is drawn empty rather than not drawn.
     *
     * Issue #166 put `UnitBlueprint.ITEM_SLOTS` above the two a kind fills. A champion carrying
     * nothing has them empty, and an empty box with its key on it is how a player finds out the
     * bar is there at all - which is the same argument the whole HUD rests on.
     */
    @Test
    fun `the hud names the player's kind slots and draws the item bar empty`() {
        val state = Fixture().sample()

        assertEquals(UnitBlueprint.ABILITY_SLOTS, state.slotCount)
        assertEquals(MobaAbilities.MELEE, state.nameAt(PlayerControlSystem.SLOT_PRIMARY))
        assertEquals(MobaAbilities.ORC_SPIN, state.nameAt(PlayerControlSystem.SLOT_SECONDARY))
        for (slot in UnitBlueprint.ITEM_SLOT_FIRST until UnitBlueprint.ABILITY_SLOTS) {
            assertEquals("", state.nameAt(slot), "item slot $slot holds nothing until one is bought")
        }
    }

    /**
     * Every slot the HUD draws has a key printed on it.
     *
     * `MobaHudScreen` indexes `keyLabels` by slot, so a bar with more slots than
     * `MobaControls.SLOT_ACTIONS` has actions is an `ArrayIndexOutOfBoundsException` in `draw` -
     * on the frame a player first carries an item, and only in a mode that has a GL context.
     */
    @Test
    fun `every ability slot has a key bound to it`() {
        assertEquals(
            UnitBlueprint.ABILITY_SLOTS,
            MobaControls.SLOT_ACTIONS.size,
            "a slot with no action is a box a player presses nothing to fire",
        )
        assertEquals(
            UnitBlueprint.ABILITY_SLOTS,
            MobaHudSystem.keyLabels().size,
            "and the HUD prints one label per slot",
        )
    }

    /**
     * **Q fires the special, and the HUD shows it going on cooldown.**
     *
     * This is the whole of the first half of this wave's brief. `moba/attack_2` was declared in
     * `moba/assets/control/controls.udea.kts`, bound to `Q`, packed into the bundle and resolved
     * into `MobaControls.ATTACK_2_ACTION` - and no system in the tree read it. The two assertions
     * are the two halves of "it is wired": the activation happened, and a human can see that it
     * happened.
     */
    @Test
    fun `Q activates the second ability and the hud shows its cooldown`() {
        val fixture = Fixture()
        assertEquals(
            0,
            fixture.sample().remainingAt(PlayerControlSystem.SLOT_SECONDARY),
            "the special was already cooling down before anything was pressed",
        )

        fixture.tap(MobaControls.ATTACK_2_ACTION)

        val state = fixture.sample()
        assertEquals(
            1L,
            fixture.control().specialsRequested,
            "pressing Q started no ability at all",
        )
        assertTrue(
            state.remainingAt(PlayerControlSystem.SLOT_SECONDARY) > 0,
            "the special fired and the HUD still reports it ready",
        )
        assertTrue(
            state.remainingAt(PlayerControlSystem.SLOT_SECONDARY) <=
                state.totalAt(PlayerControlSystem.SLOT_SECONDARY),
            "the remaining cooldown is longer than the whole cooldown, so the sweep would " +
                "draw outside its own box",
        )
        assertEquals(
            MobaAbilities.ORC_SPIN_COOLDOWN_TICKS,
            state.totalAt(PlayerControlSystem.SLOT_SECONDARY),
            "the HUD is showing a cooldown length the ability table does not have",
        )
    }

    /**
     * **E reaches the item bar**, even with nothing in it.
     *
     * The same gap `moba/attack_2` sat in until it was given a slot to point at: a control can be
     * declared, packed and resolved into an `ActionId` and read by no system at all, and from the
     * player's side of the window that is indistinguishable from a key that is not bound. What
     * separates the two is a counter moving.
     *
     * A refusal rather than an activation, because the champion the level seeds is carrying
     * nothing - which is the honest state of the item bar at the start of a match, and the reason
     * this asserts on `itemActivesRefused`. `ItemActiveTest` is where a press that actually fires
     * something is proved, over a champion that has been to the shop.
     */
    @Test
    fun `pressing the item key reaches the item bar and is counted`() {
        val fixture = Fixture()
        assertEquals(0L, fixture.control().itemActivesRefused, "nothing has been pressed yet")

        fixture.tap(MobaControls.ITEM_1_ACTION)

        assertEquals(
            1L,
            fixture.control().itemActivesRefused,
            "pressing ${MobaControls.ITEM_1} reached no system: the item bar is empty, so the " +
                "press must be a counted refusal rather than nothing at all",
        )
        assertEquals(
            0L,
            fixture.control().specialsRefused,
            "the item key must not be booked against the champion's own second slot",
        )
    }

    /**
     * Space is the sword and only the sword.
     *
     * The regression that made `Q` unreachable was not a missing binding: it was that the one
     * attack key ran "highest granted slot that will fire wins", so Space silently spent the fire
     * arrow whenever it was up and there was nothing left for a second key to do. If that comes
     * back, the arrow goes on cooldown here without Q ever being touched.
     */
    @Test
    fun `Space fires the basic attack and leaves the special alone`() {
        val fixture = Fixture()

        fixture.tap(MobaControls.ATTACK_ACTION)

        val state = fixture.sample()
        assertEquals(0L, fixture.control().specialsRequested, "Space activated the special")
        assertNotEquals(
            0,
            state.remainingAt(PlayerControlSystem.SLOT_PRIMARY),
            "Space started nothing: the basic attack is still off cooldown",
        )
        assertEquals(
            0,
            state.remainingAt(PlayerControlSystem.SLOT_SECONDARY),
            "Space spent the special, which is the bug that made Q unreachable",
        )
    }

    /**
     * A dead player is told they are dead.
     *
     * `DeathSystem.retire` does **not** remove the entity - it adds a `Corpse` and leaves the
     * body on the field for five seconds, still carrying its `Player`. So the assertion is on
     * the corpse and not on an empty family, which is the distinction that decides whether a
     * dead player is told immediately or five seconds later. Killed by emptying the health
     * attribute rather than by waiting for the fight: a test that waits for a battle to reach
     * one particular unit is a test that fails on a balance change.
     */
    @Test
    fun `the hud says so when the player is dead`() {
        val fixture = Fixture()
        assertTrue(fixture.sample().alive)

        fixture.killPlayer()
        fixture.host.run(DEATH_TICKS)
        val state = fixture.sample()

        assertTrue(!state.alive, "the HUD still reports a dead player as alive")
        assertTrue(state.died, "nothing on screen would say the player had died")
        assertEquals(0, state.slotCount, "a corpse is still being offered ability slots")
        assertTrue(
            fixture.playerStillInWorld(),
            "the entity was removed outright, so this test would pass against a HUD that " +
                "only notices death when the `Player` family empties - which is the bug",
        )
    }

    /** A real host over the real definition, plus the HUD model built off that definition's tables. */
    private class Fixture {

        val definition: UdeaGameDef = MobaGame.definition()

        val host: GameHost = GameHost(RenderMode.Headless, definition)

        private val module = definition.modules.filterIsInstance<MobaModule>().single()

        private val injected = InjectedIntent(MobaControls.BINDINGS.catalog)

        private val model = MobaHudModel(
            attributeIds = module.combat.attributes,
            abilityTable = module.combat.abilities.table,
            activation = module.combat.gas.activation,
        )

        init {
            MobaEntry.seed(host)
            host.ctx[IntentState.KEY].source = injected
            host.run(1)
            model.bind(host.world, host.ctx)
        }

        /** One press, then the tick that samples it and the tick the activation lands on. */
        fun tap(action: dev.wildware.udea.render.input.ActionId) {
            injected.tap(action)
            host.run(1)
        }

        fun sample(): HudState {
            model.sample()
            return model.state
        }

        /** Whether the dead player's entity is still there. See the death test. */
        fun playerStillInWorld(): Boolean =
            host.world.family { all(Player) }.entities.size == 1

        /** The one `PlayerControlSystem` this host is running, for its counters. */
        fun control(): PlayerControlSystem = host.world.system<PlayerControlSystem>()

        /**
         * Empties the player's health, which is what `DeathSystem` reads.
         *
         * Written through the attribute rather than through `Position.hp`: `hp` is a copy
         * `DeathSystem` writes *from* the attribute once a tick, so setting it would be
         * overwritten on the next tick and the player would not die.
         */
        fun killPlayer() {
            val entity = host.ctx[CoreModule.NET_IDS].resolveOrNull(MobaEntry.playerId(host))
                ?: error("the player entity is gone before the test killed it")
            with(host.world) {
                val attributes = entity[dev.wildware.udea.gas.Attributes]
                attributes.setBase(module.combat.attributes.health, 0f)
                attributes.current[module.combat.attributes.health.index] = 0f
            }
        }
    }

    private companion object {

        /** `MobaUnits.kinds` gives the elite orc five hundred. */
        const val PLAYER_HEALTH: Float = 500f

        /** Enough for `AttributeSystem` to recompute and `DeathSystem` to retire the unit. */
        const val DEATH_TICKS: Int = 3
    }
}
