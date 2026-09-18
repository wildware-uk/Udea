package dev.wildware.moba.ability

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import dev.wildware.moba.Position
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.blueprint.BlueprintSpawner
import dev.wildware.udea.core.blueprint.SpawnOverrides
import dev.wildware.udea.core.blueprint.SpawnPosition
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.gas.AttributeId
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.gas.GameplayEffects

/**
 * A flat, per-tick view of every unit in the world, and the [CombatWorld] every ability asks.
 *
 * ## What it replaces
 *
 * `getUnitsWithin` and `PriestHeal.getNearbyFriendlyUnits` each ran a Box2D AABB query with a
 * closure, built a `mutableSetOf<Entity>`, filtered it into a second list and often sorted that.
 * Per swing, per unit, per tick. This walks one `Family` once per tick into parallel arrays, and
 * a radius query is then a loop over floats with no closure, no set and no boxing.
 *
 * It is a brute-force scan and says so: with 27 units a query is 27 distance comparisons, which
 * is cheaper than the allocation the old broadphase query cost to set up. It is `O(n)` per query
 * and will need a grid at a few hundred units - the point at which that is worth writing is the
 * point at which a profile says so, and the interface it lives behind ([CombatWorld]) is what
 * makes replacing it a one-file change.
 *
 * ## Why the arrays hold components rather than copies of their fields
 *
 * A copy taken in `SimPhase.PreSimulation` would be stale by the `Ability` phase - a unit knocked
 * back this tick would be hit at where it used to be. Holding the component means every read is
 * live, and the index only has to be rebuilt when the *set* of units changes, which is what the
 * per-tick rebuild handles without having to detect it.
 */
public class CombatIndex(
    private val netIds: NetIdIndex,
    private val spawner: BlueprintSpawner,
    private val arrow: ArrowBlueprint,
    /** Where health sits in the shared attribute vector, for the liveness check in [query]. */
    private val health: AttributeId,
) : SimSystem(), CombatWorld {

    private val units: Family = world.family { all(Combatant, Position, Attributes, GameplayEffects) }

    private var ids = IntArray(INITIAL_CAPACITY) { NetId.NONE.raw }
    private var teams = IntArray(INITIAL_CAPACITY) { Teams.NEUTRAL }
    private var positions = arrayOfNulls<Position>(INITIAL_CAPACITY)
    private var attributes = arrayOfNulls<Attributes>(INITIAL_CAPACITY)
    private var effects = arrayOfNulls<GameplayEffects>(INITIAL_CAPACITY)
    private var motions = arrayOfNulls<Motion>(INITIAL_CAPACITY)

    /** How many units the last rebuild found. */
    public var size: Int = 0
        private set

    override fun onTick() {
        rebuild()
    }

    /**
     * Refreshes the index from the world.
     *
     * Public so a caller that spawns units outside the loop - a test, a scene's `populate` - can
     * make them visible without stepping a tick first. Idempotent.
     */
    public fun rebuild() {
        val entities = units.entities
        ensureCapacity(entities.size)
        var index = 0
        while (index < entities.size) {
            val entity: Entity = entities[index]
            ids[index] = netIds.netIdOf(entity).raw
            teams[index] = entity[Combatant].teamId
            positions[index] = entity[Position]
            attributes[index] = entity[Attributes]
            effects[index] = entity[GameplayEffects]
            motions[index] = entity.getOrNull(Motion)
            index++
        }
        // Null the tail so a shrinking world cannot leave a dead unit's components reachable.
        var stale = entities.size
        while (stale < size) {
            positions[stale] = null
            attributes[stale] = null
            effects[stale] = null
            motions[stale] = null
            ids[stale] = NetId.NONE.raw
            stale++
        }
        size = entities.size
    }

    // --- CombatWorld ---------------------------------------------------------------------------

    override fun contains(id: NetId): Boolean = slotOf(id) >= 0

    override fun x(id: NetId): Float = positions[slotOrZero(id)]?.x ?: 0f

    override fun y(id: NetId): Float = positions[slotOrZero(id)]?.y ?: 0f

    override fun teamOf(id: NetId): Int {
        val slot = slotOf(id)
        return if (slot < 0) Teams.NEUTRAL else teams[slot]
    }

    override fun attributesOf(id: NetId): Attributes? {
        val slot = slotOf(id)
        return if (slot < 0) null else attributes[slot]
    }

    override fun effectsOf(id: NetId): GameplayEffects? {
        val slot = slotOf(id)
        return if (slot < 0) null else effects[slot]
    }

    override fun impulse(id: NetId, x: Float, y: Float) {
        val slot = slotOf(id)
        if (slot < 0) return
        motions[slot]?.push(x, y)
    }

    override fun query(
        centreX: Float,
        centreY: Float,
        radius: Float,
        relation: TeamRelation,
        viewerTeam: Int,
        exclude: NetId,
        into: NetIdBuffer,
    ): Int {
        into.clear()
        val radiusSquared = radius * radius
        var slot = 0
        while (slot < size) {
            if (accepts(slot, centreX, centreY, radiusSquared, relation, viewerTeam, exclude)) {
                into.add(NetId.ofRaw(ids[slot]))
            }
            slot++
        }
        return into.size
    }

    override fun nearest(
        centreX: Float,
        centreY: Float,
        radius: Float,
        relation: TeamRelation,
        viewerTeam: Int,
        exclude: NetId,
    ): NetId {
        val radiusSquared = radius * radius
        var best = NetId.NONE
        var bestDistance = Float.MAX_VALUE
        var slot = 0
        while (slot < size) {
            if (accepts(slot, centreX, centreY, radiusSquared, relation, viewerTeam, exclude)) {
                val distance = distanceSquared(slot, centreX, centreY)
                // `<` and then the id tie-break: two units at the same distance must resolve the
                // same way on every machine, and array order is whatever Fleks compaction left.
                val id = NetId.ofRaw(ids[slot])
                if (distance < bestDistance || (distance == bestDistance && id < best)) {
                    best = id
                    bestDistance = distance
                }
            }
            slot++
        }
        return best
    }

    override fun fireArrow(
        owner: NetId,
        team: Int,
        x: Float,
        y: Float,
        vx: Float,
        vy: Float,
        damage: Float,
    ): NetId {
        // One closure per shot, on an activation path rather than a per-tick one. `SpawnOverrides`
        // is how a blueprint is told what this instance differs by, and the alternative - a
        // blueprint object per shot - allocates strictly more.
        val overrides = SpawnOverrides { context, entity ->
            with(context) {
                entity[Projectile].owner = owner
                entity[Projectile].teamId = team
                entity[Projectile].damage = damage
                entity[Projectile].stunTicks = ArrowBlueprint.STUN_TICKS
                entity[Projectile].knockback = ArrowBlueprint.KNOCKBACK
                entity[Motion].vx = vx
                entity[Motion].vy = vy
            }
        }
        return spawner.spawn(arrow, SpawnPosition(x, y), overrides)
    }

    override fun toString(): String = "CombatIndex($size units)"

    // --- internals -----------------------------------------------------------------------------

    private fun accepts(
        slot: Int,
        centreX: Float,
        centreY: Float,
        radiusSquared: Float,
        relation: TeamRelation,
        viewerTeam: Int,
        exclude: NetId,
    ): Boolean {
        if (ids[slot] == exclude.raw) return false
        if (!relation.matches(viewerTeam, teams[slot])) return false
        if (!isAlive(slot)) return false
        return distanceSquared(slot, centreX, centreY) <= radiusSquared
    }

    /**
     * Whether the unit in [slot] still has health.
     *
     * A dead unit is despawned by [DeathSystem] in `SimPhase.Gameplay`, which is *after*
     * abilities run, so for the rest of the tick that killed it the index still lists it. Without
     * this check a spin that killed three units would go on healing, hitting and knocking back
     * corpses for the remainder of the tick.
     */
    private fun isAlive(slot: Int): Boolean {
        val values = attributes[slot] ?: return false
        return values.current(health) > 0f
    }

    private fun distanceSquared(slot: Int, centreX: Float, centreY: Float): Float {
        val position = positions[slot] ?: return Float.MAX_VALUE
        val dx = position.x - centreX
        val dy = position.y - centreY
        return dx * dx + dy * dy
    }

    private fun slotOf(id: NetId): Int {
        if (id.isNone) return -1
        var slot = 0
        while (slot < size) {
            if (ids[slot] == id.raw) return slot
            slot++
        }
        return -1
    }

    /** [slotOf], or slot zero for the `?:` reads that then find a null component anyway. */
    private fun slotOrZero(id: NetId): Int = slotOf(id).coerceAtLeast(0)

    private fun ensureCapacity(needed: Int) {
        if (needed <= ids.size) return
        var capacity = ids.size
        while (capacity < needed) capacity *= 2
        ids = ids.copyOf(capacity)
        teams = teams.copyOf(capacity)
        positions = positions.copyOf(capacity)
        attributes = attributes.copyOf(capacity)
        effects = effects.copyOf(capacity)
        motions = motions.copyOf(capacity)
    }

    private companion object {
        /** Units before the first grow. A lane fight is smaller than this. */
        const val INITIAL_CAPACITY: Int = 64
    }
}
