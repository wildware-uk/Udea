package dev.wildware.moba.ai

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.moba.MobaGame
import dev.wildware.moba.Position
import dev.wildware.moba.ability.CharacterAttributes
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.moba.level.GameUnit
import dev.wildware.moba.level.Team
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.gas.Attributes
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A routed unit actually leaves, **in the assembled game** and not only in the AI's own fixture.
 *
 * ## The gap this closes, and why the existing retreat test could not see it
 *
 * `UnitAiProofTest.a wounded unit retreats and a fearless one at the same health holds` drives
 * `CombatFixture`, which is the ability half of the game and nothing else. The level's own
 * `UnitBattleSystem` - the thing that walks a unit towards the nearest enemy at
 * `UnitKind.moveSpeed` - is not in that fixture, so the fixture measured `UnitBrain.retreat`
 * against no opposition and reported a clean rout.
 *
 * In the shipped process both systems run on the same entity in the same tick. The brain pushed
 * the runner away through `Motion` and the battle system walked it straight back, and the two
 * netted roughly a sixth of [UnitBrain.RETREAT_SPEED]: the unit backed off at a shuffle while
 * whatever it was running from closed at full speed. Every test in the tree stayed green, because
 * no test ran the two together and asked how far anybody got.
 *
 * So this test boots the **real level** through the same `MobaEntry.seed` every entry point uses,
 * and asks the only question that distinguishes the two builds: after
 * [FLIGHT_TICKS] ticks, how much further away is the runner?
 *
 * ## Why the health is pinned rather than set once
 *
 * A unit on [WOUNDED] hit points in the middle of a real fight dies within a second or two, and a
 * dead runner's separation stops meaning anything - `UnitBattleSystem` returns early on
 * `hp <= 0` and the corpse it leaves behind neither runs nor closes. Re-wounding both the runner
 * and its chaser every tick holds the *scenario* still - "somebody routed, somebody chasing" -
 * so what the numbers measure is movement rather than who happened to die first. It is the same
 * technique as `CombatFixture.wound`, applied once a tick instead of once.
 *
 * Both `base` and `current` are written because `AttributeSystem` derives `current` from `base`
 * each tick and `DeathSystem` copies `current` onto the `Position.hp` window that
 * [UnitBrain.isRouted]'s caller reads. Writing one of the three leaves the other two to overwrite
 * it, which is the shape of bug that made `Position.hp` worth a paragraph in `GameUnit`'s KDoc.
 */
class RoutedUnitTest {

    @Test
    fun `a routed unit in the real level runs instead of shuffling`() {
        val host = MobaGame.host(RenderMode.Headless)
        MobaEntry.seed(host)
        // Long enough for the level's spawn barrier to have drained and for every unit to have
        // acquired a target, so "nearest enemy" below is the fight's answer and not spawn order.
        host.run(SETTLE_TICKS)

        val runner = pickRunner(host)
        val chaser = nearestEnemy(host, runner)
        val started = separation(host, runner, chaser)

        repeat(FLIGHT_TICKS) {
            wound(host, runner)
            wound(host, chaser)
            host.run(1)
        }
        val ended = separation(host, runner, chaser)
        println(
            "[rout] over $FLIGHT_TICKS ticks the runner went from $started to $ended world " +
                "units from its chaser, a gain of ${ended - started}",
        )

        assertTrue(
            hp(host, runner) > 0f,
            "the runner was re-wounded to $WOUNDED every tick and should still be standing",
        )
        assertTrue(
            ended > started + ESCAPED,
            "a unit on $WOUNDED health that is not Fearless should have run at least $ESCAPED " +
                "world units clear of its chaser in $FLIGHT_TICKS ticks. It started $started " +
                "away and finished $ended away - a gain of ${ended - started}. " +
                "UnitBrain.RETREAT_SPEED is ${UnitBrain.RETREAT_SPEED} per tick, so an " +
                "unobstructed rout covers about ${UnitBrain.RETREAT_SPEED * FLIGHT_TICKS}; a " +
                "gain far below that is UnitBattleSystem walking the runner back into the fight " +
                "in the same tick UnitBrain pushed it out.",
        )
    }

    /** A living unit on the one team the corpus gives no `AITag.Fearless`, with an enemy in sight. */
    private fun pickRunner(host: GameHost): Entity {
        val candidates = host.world.family { all(GameUnit, Position) }.entities
        var index = 0
        while (index < candidates.size) {
            val entity = candidates[index]
            index++
            with(host.world) {
                if (entity[GameUnit].team != Team.SOLDIER) return@with
                if (entity[Position].hp <= 0f) return@with
                if (entity.getOrNull(Attributes) == null) return@with
                if (nearestEnemyOrNull(host, entity) == null) return@with
                return entity
            }
        }
        throw AssertionError(
            "the real level spawned no living Team.SOLDIER unit with an enemy in range after " +
                "$SETTLE_TICKS ticks, so there is nobody in it who can be routed",
        )
    }

    private fun nearestEnemy(host: GameHost, self: Entity): Entity =
        checkNotNull(nearestEnemyOrNull(host, self)) { "the runner lost its enemy" }

    private fun nearestEnemyOrNull(host: GameHost, self: Entity): Entity? {
        val world = host.world
        val entities = world.family { all(GameUnit, Position) }.entities
        var best: Entity? = null
        var bestDistance = Float.MAX_VALUE
        var index = 0
        while (index < entities.size) {
            val other = entities[index]
            index++
            if (other == self) continue
            with(world) {
                if (!Team.isHostile(self[GameUnit].team, other[GameUnit].team)) return@with
                if (other[Position].hp <= 0f) return@with
                val distance = separation(host, self, other)
                if (distance >= bestDistance) return@with
                bestDistance = distance
                best = other
            }
        }
        return best
    }

    private fun separation(host: GameHost, a: Entity, b: Entity): Float = with(host.world) {
        val first = a[Position]
        val second = b[Position]
        val dx = first.x - second.x
        val dy = first.y - second.y
        sqrt(dx * dx + dy * dy)
    }

    private fun hp(host: GameHost, entity: Entity): Float = with(host.world) { entity[Position].hp }

    /** Holds [entity] at [WOUNDED] on all three of base, current and the `Position.hp` window. */
    private fun wound(host: GameHost, entity: Entity) {
        val health = CharacterAttributes.create().health
        with(host.world) {
            val attributes = entity.getOrNull(Attributes) ?: return
            attributes.setBase(health, WOUNDED)
            attributes.current[health.index] = WOUNDED
            entity[Position].hp = WOUNDED
        }
    }

    private companion object {

        /** Ticks before the scenario is set up. Past the spawn barrier and target acquisition. */
        const val SETTLE_TICKS: Int = 20

        /** Below `UnitBrain.FLEE_HEALTH`, and the same nine `UnitAiProofTest` wounds to. */
        const val WOUNDED: Float = 9f

        /** How long the rout is watched. Two seconds at 60Hz. */
        const val FLIGHT_TICKS: Int = 120

        /**
         * How much clear ground a rout has to gain, in world units.
         *
         * Well above the ~18 the netted-out build managed over [FLIGHT_TICKS] and well below the
         * ~108 an unobstructed rout covers, so the threshold separates the two builds rather than
         * pinning either one's exact number - which knockback, separation pressure and the chaser's
         * own speed all move.
         */
        const val ESCAPED: Float = 40f
    }
}
