package dev.wildware.moba

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.moba.ability.ArrowBlueprint
import dev.wildware.moba.ability.Combatant
import dev.wildware.moba.ability.Corpse
import dev.wildware.moba.ability.DeathSystem
import dev.wildware.moba.ability.Projectile
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.moba.level.GameUnit
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.SpriteAnimation
import dev.wildware.udea.assets.SpriteSheet
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.CoreModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The three pictures this game was missing: the corpse, the arrow and the flash.
 *
 * ## Why these are headless assertions about a running level
 *
 * Not one of them can be proved by a screenshot alone - a capture says "these pixels" and not
 * "this unit is in the death animation because its health ran out". And not one of them can be
 * proved by a fixture either: the claim is that the *shipping level* produces the situation, which
 * is exactly what was wrong before. Six death sheets were packed, cut and addressable for the
 * whole life of this port and no unit in a running game ever showed a frame of one, because
 * `DeathSystem` removed the entity on the tick its health hit zero. A fixture that put a unit in
 * `UnitState.Death` by hand would have been green throughout.
 *
 * `RenderMode.Headless` throughout: none of this is about pixels, so none of it needs a driver.
 */
class MobaVfxTest {

    /** A headless host with the level loaded and the swap tick already applied. */
    private fun booted(): GameHost {
        val host = MobaGame.host(RenderMode.Headless)
        MobaEntry.seed(host)
        return host
    }

    /** Runs until [predicate] holds, up to [limit] ticks. @return the tick it held on, or -1. */
    private fun runUntil(host: GameHost, limit: Int = 900, predicate: (GameHost) -> Boolean): Long {
        var ticks = 0
        while (ticks < limit) {
            if (predicate(host)) return host.ctx.clock.tick.value
            host.run(1)
            ticks++
        }
        return -1L
    }

    private fun corpses(host: GameHost): List<Entity> =
        host.world.family { all(Corpse, Position, CharacterView) }.entities.let { bag ->
            List(bag.size) { bag[it] }
        }

    /**
     * A unit that runs out of health stays on the field, in its death animation.
     *
     * The whole of the first defect, in one assertion each: the entity is **still there**, its
     * `CharacterView` is in [UnitState.Death], and its health is not positive. Before this change
     * the first of those was false and the second was unreachable - `CharacterStateSystem`'s own
     * KDoc said so.
     */
    @Test
    fun `a dead unit stays on the field in the death animation`() {
        val host = booted()
        val at = runUntil(host) { corpses(it).isNotEmpty() }
        assertTrue(at > 0, "nothing died within 900 ticks, so there is no corpse to look at")
        val body = corpses(host).first()
        with(host.world) {
            assertTrue(body[Position].hp <= 0f, "a corpse with health left is not a corpse")
            assertEquals(
                UnitState.Death,
                body[CharacterView].state,
                "the body is on the field and is not playing its death animation, which is the " +
                    "half of this that `CharacterStateSystem` owns",
            )
            assertNotNull(body.getOrNull(GameUnit), "a corpse is still a unit of its team")
        }
    }

    /**
     * A corpse is out of the fight: nothing targets it and it swings at nobody.
     *
     * The port of what `GameUnitSystem.checkDead` did with `isSensor = true`, a `StaticBody` and
     * `controller.isActive = false`, in a game with no physics: one component removed. Asserted
     * from the component rather than from an outcome because "it was never hit again" is a claim a
     * quiet ten seconds satisfies by accident.
     */
    @Test
    fun `a corpse is not a combatant`() {
        val host = booted()
        val at = runUntil(host) { corpses(it).isNotEmpty() }
        assertTrue(at > 0, "nothing died within 900 ticks")
        for (body in corpses(host)) {
            with(host.world) {
                assertTrue(
                    body.getOrNull(Combatant) == null,
                    "a corpse still carries `Combatant`, so `CombatIndex` still offers it as a " +
                        "target and `AbilityAutopilotSystem` still lets it swing",
                )
            }
        }
    }

    /**
     * The body is cleared away, and the id with it.
     *
     * The half that is not `GameUnitSystem`'s: the old game left every corpse until the level
     * ended. [DeathSystem.CORPSE_TICKS] after it fell, the entity goes and its `NetId` is freed -
     * which is what keeps `world.query_entities` finite and the id space from leaking a battle at
     * a time.
     */
    @Test
    fun `a corpse is cleared after its linger`() {
        val host = booted()
        val at = runUntil(host) { corpses(it).isNotEmpty() }
        assertTrue(at > 0, "nothing died within 900 ticks")
        val before = corpses(host).size
        host.run((DeathSystem.CORPSE_TICKS + 2).toInt())
        val stillThere = corpses(host).count { with(host.world) { it[Corpse].diedTick <= at } }
        assertEquals(
            0,
            stillThere,
            "$before bodies fell on or before tick $at and at least one is still lying there " +
                "${DeathSystem.CORPSE_TICKS} ticks later",
        )
        var live = 0
        host.ctx[CoreModule.NET_IDS].forEachLive { _, _ -> live++ }
        assertEquals(
            host.world.numEntities,
            live,
            "the net id index and the world disagree, so a cleared body took its id with it or " +
                "left one behind",
        )
    }

    /**
     * Every arrow in flight carries the view the port dropped.
     *
     * The defect was not that the arrow was drawn badly - it was that `ArrowBlueprint` added
     * `Position`, `Motion` and `Projectile` and nothing a renderer could see, so a soldier's shot
     * crossed forty world units invisibly. Asserted on every arrow rather than on the blueprint,
     * because a blueprint is only worth what the spawner does with it.
     */
    @Test
    fun `an arrow in flight carries the arrow sprite`() {
        val host = booted()
        val at = runUntil(host) { it.world.family { all(Projectile) }.entities.size > 0 }
        assertTrue(at > 0, "no arrow was ever in flight within 900 ticks")
        val arrows = host.world.family { all(Projectile) }.entities
        with(host.world) {
            var index = 0
            while (index < arrows.size) {
                val view = arrows[index].getOrNull(SpriteView)
                assertNotNull(view, "an arrow with no `SpriteView` is an invisible arrow")
                assertEquals(ArrowBlueprint.ANIMATION, view.animation, "the arrow's animation")
                assertTrue(view.facesMotion, "an arrow that does not turn flies sideways")
                index++
            }
        }
    }

    /**
     * A blow landing puts a flash on the field, and the flash goes away again.
     *
     * Both halves in one test on purpose. Spawning without expiring is the failure this replaced:
     * the first version of `EffectBlueprint` threw inside `configure`, `SimBarrier` logged it and
     * carried on, and six hundred ticks left a hundred and seventy-six view-less entities in the
     * world that nothing drew and nothing removed. So the assertion is not "a flash appeared" but
     * "a flash appeared **and** the count came back down".
     */
    @Test
    fun `a hit spawns a flash that expires`() {
        val host = booted()
        val at = runUntil(host) { flashes(it) > 0 }
        assertTrue(at > 0, "no flash was ever spawned within 900 ticks, so no hit was ever drawn")
        val peak = flashes(host)
        // Longer than the longest `EffectKind.lifeTicks`, and long enough that the fight is over:
        // by tick 1500 `MobaIntegrationTest`'s six hundred are well past and one side has won.
        host.run(1500)
        assertTrue(
            flashes(host) <= peak,
            "flashes are accumulating: $peak at tick $at and ${flashes(host)} much later, so " +
                "`EffectExpirySystem` is not removing them",
        )
        var live = 0
        host.ctx[CoreModule.NET_IDS].forEachLive { _, _ -> live++ }
        assertEquals(
            host.world.numEntities,
            live,
            "the world holds entities with no net id, which is what an effect entity whose " +
                "`configure` threw looks like",
        )
    }

    private fun flashes(host: GameHost): Int =
        host.world.family { all(SpriteView, Position) }.entities.size -
            host.world.family { all(SpriteView, Projectile) }.entities.size

    /**
     * Every animation this game's Kotlin names is in the packed graph, with frames behind it.
     *
     * The check that stops an [EffectKind] or [ArrowBlueprint.ANIMATION] naming an asset the
     * bundle does not hold. `SpriteRenderSystem` answers a missing animation by drawing nothing,
     * which is indistinguishable from the bug this whole change exists to fix - so it is caught
     * here, where the message can name the id.
     */
    @Test
    fun `every effect and arrow animation is packed with frames`() {
        val registry = MobaAssets.registry
        val ids = EffectKind.entries.map { it.animation } + ArrowBlueprint.ANIMATION
        for (id: AssetId in ids) {
            val animation = registry.find(id) as? SpriteAnimation
            assertNotNull(animation, "'${id.value}' is not a `spriteAnimation` in the bundle")
            val sheet = registry.find(animation.sheet.id) as? SpriteSheet
            assertNotNull(sheet, "'${id.value}' names sheet '${animation.sheet.id}', which is not one")
            val frames = MobaAssets.atlas.framesOf(animation.sheet.id)
            assertEquals(
                sheet.frameCount,
                frames.size,
                "'${animation.sheet.id}' declares ${sheet.frameCount} frames and the atlas cut " +
                    "${frames.size}",
            )
            assertTrue(sheet.scale > 0f, "'${animation.sheet.id}' has no world scale")
        }
    }
}
