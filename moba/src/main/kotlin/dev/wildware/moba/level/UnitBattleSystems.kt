package dev.wildware.moba.level

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import dev.wildware.moba.CharacterView
import dev.wildware.moba.Player
import dev.wildware.moba.Position
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.CoreModule

/**
 * The half of the fight `udea-gas` has no opinion about: who a unit is going for, and walking there.
 *
 * ## What it is, after the integration
 *
 * It used to be the whole fight - it picked a target, closed, and subtracted a float from
 * `Position.hp` on a cooldown of its own. Its own KDoc called that a stub and named what was
 * missing: no heal, no arrows, no cues, no stun, no knockback. All of that exists now, in
 * `dev.wildware.moba.ability`, and every unit this level spawns carries it (see
 * [UnitBlueprint.configure]). So the swing is gone from this file: damage is
 * [dev.wildware.moba.ability.MeleeAttackExec] applying `ability/damage` through the shared
 * applier, and the cooldown is a real gameplay effect that a rewind restores.
 *
 * What is left is the part `udea-gas` genuinely cannot do, and the reason is structural rather
 * than an omission: **GAS has no space.** It has no position, no velocity and no notion of "far",
 * so it can neither decide that an orc should walk north nor walk it.
 * `AbilityAutopilotSystem` fires an ability when an enemy is already in reach; something has to
 * put one there.
 *
 * That leaves three jobs here, and they are all spatial:
 *
 * - **Targeting.** The nearest hostile within [AGGRO_RADIUS], written to [GameUnit.targetRaw] so
 *   an agent can read who a unit is going for without inferring it from geometry.
 * - **Closing.** Walk at [UnitKind.moveSpeed] until inside [UnitKind.reach]. Every kind has a `reach`
 *   is inside [dev.wildware.moba.ability.MeleeAttackExec.RANGE], so a unit that has finished
 *   closing is a unit whose own swing can find what it closed on - the relationship
 *   `MobaIntegrationTest` pins, and the one that decides whether this game is a fight or a
 *   stand-off.
 * - **Facing.** [CharacterView.flipX], because the art faces right and a unit fighting something
 *   on its left with its back turned is the most obvious way a renderer looks broken.
 * - **Separation.** A gentle shove out of anything standing inside [PERSONAL_SPACE], for every
 *   unit except the one a human is steering.
 *
 * ## Why separation is here and not in physics
 *
 * `PhysicsWorld` is a no-op in this engine, so nothing stops two units occupying one point. In
 * the old game Box2D bodies did it for free, and losing that is not cosmetic: five orcs all
 * closing on one soldier converge on the *same* coordinates, and five sprites drawn at one point
 * are one sprite. A capture of the surviving pack looked like a single orc, the healthbars stacked
 * into one bar, and a fight that was working read as a fight in which everything had died.
 *
 * It is a fixed-strength shove and not an impulse into [dev.wildware.moba.ability.Motion],
 * deliberately: `Motion` carries knockback, which is combat and rewinds as combat, and a permanent
 * crowd pressure leaking into it would make every unit drift for the rest of the match. This is
 * applied to [Position] in the same pass that does the closing, so it is spent within the tick.
 *
 * ## Allocation and determinism
 *
 * No allocation per tick: the nearest-enemy search is a nested index loop over
 * [Family.entities] and everything it writes is a field on a component. Targeting is `O(n^2)`
 * over the fighting units - 27 of them here, 729 distance comparisons a tick - which is honestly
 * a placeholder for a broadphase, and is the reason [AGGRO_RADIUS] exists as a real cut rather
 * than a formality: it is what a spatial index would replace.
 *
 * Order is fixed: units are visited in family order and each one reads the positions as they
 * stand when it is visited, so the tick is a pure function of the world - which is what
 * `time.rewind` and the snapshot hash both depend on.
 */
public class UnitBattleSystem : SimSystem() {

    private val units: Family = world.family { all(GameUnit, Position) }

    private val netIds: NetIdIndex = ctx[CoreModule.NET_IDS]

    /** Steps walked since the process started. A signal for a test and for a log line. */
    public var stepsTaken: Long = 0L
        private set

    override fun onTick() {
        val entities = units.entities
        val now = tick.value
        var index = 0
        while (index < entities.size) {
            val self = entities[index]
            act(self, entities.size, now)
            index++
        }
    }

    private fun act(self: Entity, count: Int, now: Long) {
        val unit = self[GameUnit]
        val position = self[Position]
        if (position.hp <= 0f) return
        // Not the player. Same rule as the closing below and for a stronger reason: a unit a human
        // is steering must move because they moved it, and a crowd that shoves it sideways while
        // they hold nothing reads as drift - `MobaInputTest` catches exactly that, and caught it
        // here. The honest cost is that AI units walk into the player rather than around them.
        if (Player !in self) separate(self, position, count)
        val target = nearestEnemy(self, unit.team, position, count)
        if (target == null) {
            unit.targetRaw = NetId.NONE.raw
            return
        }
        unit.targetRaw = netIds.netIdOf(target).raw
        val kind = unit.unitKind
        val targetPosition = target[Position]
        val dx = targetPosition.x - position.x
        val dy = targetPosition.y - position.y
        // Face what you are going for, walking or not: a unit standing still and swinging at
        // something on its left must not be drawn swinging to the right. A player's facing is
        // overwritten by the axis the human is holding, in `PlayerControlSystem`, because a
        // player's sprite should follow their hands rather than the targeting rule.
        val view = self.getOrNull(CharacterView)
        if (view != null && dx != 0f) view.flipX = dx < 0f
        val distance = length(dx, dy)
        if (distance <= kind.reach) return
        // A player-driven unit closes with WASD, not by itself (issue #124). Targeting above still
        // ran, so the player unit has a target and its abilities fire when it is in reach - what
        // it does not do is walk there on its own, which would fight the axis the human is holding
        // and read as unresponsive controls rather than as an AI decision.
        if (Player in self) return
        // Closing. `distance` is greater than `reach`, which is positive, so it cannot be zero
        // here and the normalisation is safe without a guard.
        position.x += dx / distance * kind.moveSpeed
        position.y += dy / distance * kind.moveSpeed
        // The tick, not a boolean: `CharacterStateSystem` runs later in the same tick and asks
        // "did this unit walk *this* tick", which a flag would answer wrongly for every unit that
        // stopped, because nothing would ever clear it.
        unit.movingTick = now
        stepsTaken++
    }

    /**
     * The closest living enemy within [AGGRO_RADIUS], or `null`.
     *
     * Ties break on family order, which is spawn order until something dies. That is arbitrary
     * and it is *stable*, which is the only property a simulation needs from a tie-break.
     */
    private fun nearestEnemy(self: Entity, team: Int, from: Position, count: Int): Entity? {
        val entities = units.entities
        var best: Entity? = null
        var bestDistance = AGGRO_RADIUS
        var index = 0
        while (index < count) {
            val other = entities[index]
            index++
            if (other == self) continue
            val otherUnit = other[GameUnit]
            if (!Team.isHostile(team, otherUnit.team)) continue
            val otherPosition = other[Position]
            if (otherPosition.hp <= 0f) continue
            val distance = length(otherPosition.x - from.x, otherPosition.y - from.y)
            if (distance >= bestDistance) continue
            bestDistance = distance
            best = other
        }
        return best
    }

    /**
     * Pushes [self] out of anything standing inside [PERSONAL_SPACE].
     *
     * Summed over every crowding neighbour rather than only the nearest, because a unit wedged
     * between two others has to leave along the resultant and shoving it away from one of them
     * alone walks it straight into the other. Applied at [SEPARATION_SPEED], which is a fraction
     * of the slowest [UnitKind.moveSpeed] - a crowd that shoves harder than it walks turns a melee
     * into an explosion, and one that shoves as hard as it walks lets a unit at the back of a pack
     * push the front rank out of its own reach.
     *
     * Two units at *exactly* the same point are separated along +x. Arbitrary, and deterministic,
     * which is the only property this needs; a random direction would want an `RngService` stream
     * and would still be arbitrary.
     */
    private fun separate(self: Entity, position: Position, count: Int) {
        val entities = units.entities
        var pushX = 0f
        var pushY = 0f
        var index = 0
        while (index < count) {
            val other = entities[index]
            index++
            if (other == self) continue
            val theirs = other[Position]
            if (theirs.hp <= 0f) continue
            val dx = position.x - theirs.x
            val dy = position.y - theirs.y
            val distance = length(dx, dy)
            if (distance >= PERSONAL_SPACE) continue
            if (distance < CO_LOCATED) {
                pushX += 1f
                continue
            }
            // Scaled by how deep the overlap is, so a unit brushing the edge of somebody's space
            // is nudged and one standing on top of them is shoved.
            val strength = (PERSONAL_SPACE - distance) / PERSONAL_SPACE
            pushX += dx / distance * strength
            pushY += dy / distance * strength
        }
        val magnitude = length(pushX, pushY)
        if (magnitude < CO_LOCATED) return
        position.x += pushX / magnitude * SEPARATION_SPEED
        position.y += pushY / magnitude * SEPARATION_SPEED
    }

    private fun length(dx: Float, dy: Float): Float = kotlin.math.sqrt(dx * dx + dy * dy)

    public companion object {

        /**
         * How far a unit will look for an enemy, in world units.
         *
         * The clusters of `level/test_level` sit up to a hundred and eighty units apart, so
         * this being larger than the field is what makes the three groups converge into one
         * fight rather than three standoffs. The old game used ten units in a world where a
         * character was about one across, which is the same statement about a smaller field.
         */
        public const val AGGRO_RADIUS: Float = 300f

        /**
         * How much room a unit insists on, in world units.
         *
         * Smaller than every [UnitKind.reach], and that ordering is load-bearing rather than
         * aesthetic: a crowd that held units further apart than they can swing would push every
         * attacker out of its own melee range and the fight would stop. `MobaIntegrationTest` pins
         * it. Twenty-two is about two thirds of a sprite, so a pack reads as a pack and you can
         * still count it.
         */
        public const val PERSONAL_SPACE: Float = 16f

        /**
         * How fast a crowded unit slides out, in world units per tick.
         *
         * A fifth of the slowest walk. See [separate] for why it is deliberately well under it.
         */
        public const val SEPARATION_SPEED: Float = 0.13f

        /** Below this separation two units count as standing on the same point. */
        private const val CO_LOCATED: Float = 1e-4f
    }
}
