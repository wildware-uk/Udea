package dev.wildware.moba

import com.badlogic.gdx.Input
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.moba.level.GameUnit
import dev.wildware.moba.level.MobaBlueprints
import dev.wildware.moba.level.UnitKind
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.gas.Abilities
import dev.wildware.udea.generated.GameAssets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The whole roster is on the field, the player is the elite orc, and the keys are authored.
 *
 * ## What was broken
 *
 * Six characters were packed, cut into the atlas and drawable, and **two of them could never be
 * spawned by anything**: `assets/blueprint/units.udea.kts` declared four ids and `MobaBlueprints`
 * answered four, so no level could name `blueprint/orc_elite` or `blueprint/wizard` and
 * `MobaBlueprints.byAssetId` would have refused one that tried. A third of the art in the bundle
 * was unreachable, and so was `OrcSpinExec` - a registered exec with a `TargetPolicy`, a
 * `MobaCues` entry and an eleven-frame sheet behind it, granted by `MobaUnits` to `orc_elite` and
 * to nothing else. Every test in front of that was green, because a test over the ability table
 * asks what the table holds and not what a level can spawn.
 *
 * The control half is the same shape of gap: the bindings were six `ActionBinding`s in Kotlin
 * under a comment saying the packer could not publish a `control`, which it always could.
 */
class MobaFieldTest {

    // --- the roster ---------------------------------------------------------------------------

    /**
     * Every blueprint the art tree has a character for is declared, and code answers to each.
     *
     * Driven off [MobaCharacters.roster] rather than a written list of six names: the roster is
     * built from what the bundle packed, so a seventh character added to `assets/character/`
     * fails here until a blueprint names it - which is the failure that did not happen when the
     * sixth was added.
     */
    @Test
    fun `every packed character has a blueprint that spawns it`() {
        val blueprints = MobaGame.host(RenderMode.Headless).ctx[MobaBlueprints.KEY]
        val spawnable = blueprints.all.map { it.kind.character }.toSet()
        val packed = MobaCharacters.roster.entries.map { it.name }.toSet()
        assertEquals(
            packed,
            spawnable,
            "the art tree packs $packed and this game can spawn $spawnable; a character with no " +
                "blueprint is art in the bundle that no level can name",
        )
    }

    /** The level names both of the ids that used to have no blueprint behind them. */
    @Test
    fun `the authored level puts the elite orc and the wizard on the field`() {
        val level = MobaAssets.registry[GameAssets.level.testLevel]
        val named = level.entities.mapNotNull { it.blueprint?.id?.value }.toSet()
        assertTrue("character/orc_elite" in named, "the level names: $named")
        assertTrue("character/wizard" in named, "the level names: $named")
    }

    /**
     * Spawning the level really produces one of each, counted off the world.
     *
     * Off `GameUnit.kind` rather than off the level file, so this fails if `MobaBlueprints` maps
     * an authored id onto the wrong [UnitKind] - which the level asset alone cannot show.
     */
    @Test
    fun `the spawned world holds all six kinds`() {
        val host = booted()
        val seen = HashSet<UnitKind>()
        val units = host.world.family { all(GameUnit) }
        with(host.world) { units.forEach { seen += it[GameUnit].unitKind } }
        assertEquals(
            UnitKind.entries.toSet(),
            seen,
            "the field is missing ${UnitKind.entries.toSet() - seen}",
        )
    }

    // --- the player, and the ability that had no carrier -------------------------------------

    /**
     * The player is an elite orc carrying the spin, as `blueprint/player` was in the old game.
     *
     * The assertion that matters is the second one. `OrcSpinExec` is registered by
     * `MobaAbilityModule` and pointed at by `MobaAbilities.targeting` whatever the level does;
     * what makes it *reachable* is an entity whose `Abilities` component holds its index, and
     * before the elite was spawnable no entity in any bootable process ever did.
     */
    @Test
    fun `the player is an elite orc and carries the spin`() {
        val host = booted()
        val spin = MobaGame.definition()
            .modules.filterIsInstance<MobaModule>().single().combat.abilities.orcSpin
        val players = host.world.family { all(Player) }.entities
        assertEquals(1, players.size, "the level marks exactly one player")
        with(host.world) {
            val player = players[0]
            assertEquals(
                UnitKind.OrcElite,
                player[GameUnit].unitKind,
                "the old `blueprint/player` inherited `blueprint/orc_elite`",
            )
            val granted = player[Abilities]
            val slots = (0 until granted.slotCount).map { granted.instanceAt(it).abilityIndex }
            assertTrue(
                spin in slots,
                "the player holds abilities $slots and `ability/orc_elite_spin` is $spin; " +
                    "without it `OrcSpinExec` is registered code no entity can activate",
            )
        }
    }

    // --- the controls, back in the asset graph ------------------------------------------------

    /**
     * The keys the game runs on are the ones `assets/control/controls.udea.kts` authored.
     *
     * Pinned to literal `Input.Keys` values rather than compared against the same assets the
     * loader read, and that is the whole point: comparing the two sides of one graph read would
     * agree even if [MobaControlAssets] silently fell back to a hard-coded table. Change
     * `move_left`'s key in the asset and this goes red without a line of Kotlin being touched,
     * which is the property the port had lost.
     */
    @Test
    fun `the bindings come from the packed control graph`() {
        val bindings = MobaControls.BINDINGS
        val attack = bindings.binding(MobaControls.ATTACK_ACTION)
        assertEquals(listOf(Input.Keys.SPACE), attack.keys.toList(), "attack is Space")
        val second = bindings.binding(MobaControls.ATTACK_2_ACTION)
        assertEquals(listOf(Input.Keys.Q), second.keys.toList(), "attack_2 is Q")
        // The item bar, added by issue #166. A slot with no key bound to it is an active a human
        // cannot cast, which from the player's side of the window is the same as an active that
        // was never granted.
        assertEquals(
            listOf(Input.Keys.E),
            bindings.binding(MobaControls.ITEM_1_ACTION).keys.toList(),
            "item_1 is E",
        )
        assertEquals(
            listOf(Input.Keys.R),
            bindings.binding(MobaControls.ITEM_2_ACTION).keys.toList(),
            "item_2 is R",
        )
        val move = bindings.binding(MobaControls.MOVE_AXIS)
        assertEquals(Input.Keys.A, move.negativeX, "move_left")
        assertEquals(Input.Keys.D, move.positiveX, "move_right")
        assertEquals(Input.Keys.S, move.negativeY, "move_down")
        assertEquals(Input.Keys.W, move.positiveY, "move_up")
    }

    /**
     * The four control kinds are in the bundle, which is the capability claim itself.
     *
     * `control`, `axis2D`, `binding` and `axis2DBinding` are `AssetKind.of<...>()` in
     * `AssetScope` - published kinds - and `AssetCodecs` round-trips all four. Nothing had ever
     * put one in a packed root, so the claim "the packer cannot publish these" went unchallenged.
     */
    @Test
    fun `the bundle carries the authored controls`() {
        val registry = MobaAssets.registry
        assertNotNull(registry.find(GameAssets.control.attack.id), "control/attack")
        assertNotNull(registry.find(GameAssets.control.move.id), "control/move")
        assertNotNull(registry.find(GameAssets.control.attackBinding.id), "control/attack_binding")
        assertNotNull(registry.find(GameAssets.control.moveLeft.id), "control/move_left")
    }

    private fun booted(): GameHost {
        val host = MobaGame.host(RenderMode.Headless)
        MobaEntry.seed(host)
        return host
    }
}
