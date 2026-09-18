package dev.wildware.moba.lane

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import dev.wildware.moba.Player
import dev.wildware.moba.Position
import dev.wildware.moba.ability.Combatant
import dev.wildware.moba.ability.Corpse
import dev.wildware.moba.ability.MobaCues
import dev.wildware.moba.ability.MobaEffects
import dev.wildware.moba.ability.MobaTags

import dev.wildware.moba.level.GameUnit
import dev.wildware.moba.level.MobaBlueprints
import dev.wildware.moba.level.Team
import dev.wildware.moba.level.UnitBattleSystem
import dev.wildware.moba.match.MatchRules
import dev.wildware.moba.match.Respawn
import dev.wildware.udea.core.RngStream
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.blueprint.SpawnOverrides
import dev.wildware.udea.core.blueprint.SpawnPosition
import dev.wildware.udea.core.blueprint.blueprints
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.gas.AttributeId
import dev.wildware.udea.gas.EffectApplier
import dev.wildware.udea.gas.GameplayEffects
import dev.wildware.udea.gas.GasCueQueue

/**
 * The lane: it opens, it places two towers, and it sends a wave every
 * [LaneGeometry.WAVE_INTERVAL_TICKS].
 *
 * ## Why the lane state is minted here and not by the scene
 *
 * `level/test_level` is an authored roster and this is not part of it - see [LaneGeometry] on why
 * a polyline is not something the asset DSL can carry yet. More to the point, the lane has to
 * survive exactly what a match survives: `MatchSystem.restart` swaps the scene, which runs
 * `world.removeAll` and takes the [LaneState] singleton with it, and the next tick over a
 * populated world mints a fresh one here. So a new match opens a new lane at wave zero with no
 * reset path of its own, which is the same trick `MatchSystem.begin` plays and for the same
 * reason: the engine's teardown is already correct and a second one would have to be kept in
 * step with it.
 *
 * The `null` return from [open] is the boot ordering made explicit rather than absorbed: an entry
 * point requests the scene and the swap lands at the top of the *next* tick, so there is a tick in
 * every process where this system runs over an empty world. Opening a lane there would put two
 * towers in a world the scene swap is about to erase.
 *
 * ## Determinism
 *
 * Wave scatter is two draws from [RngStream.Wave] per creep, always, in a fixed order - the same
 * discipline `TestLevelScene` applies to [RngStream.Spawn], and for the same reason: a creep that
 * took its draws conditionally would make every later creep's position depend on whether an
 * earlier one had spawned. `RngStream.Wave` and not `Spawn`, so that a change to how a wave is
 * laid out cannot move where the *level* placed its twenty-seven units.
 *
 * Allocation per tick is one `SpawnOverrides` per creep on the tick a wave goes out - six objects
 * every six hundred ticks - and nothing at all on the other five hundred and ninety-nine.
 */
public class LaneSystem : SimSystem() {

    /** The singleton. A family and not a stored `Entity`, because a scene swap destroys it. */
    private val lanes: Family = world.family { all(LaneState) }

    /** Anything the level placed. Used only to tell a populated world from an empty one. */
    private val units: Family = world.family { all(GameUnit, Combatant) }

    /** Creeps currently walking. Published onto [LaneState.creepsAlive] for the HUD and tests. */
    private val creeps: Family = world.family { all(LaneCreep, Combatant) }

    private val netIds: NetIdIndex = ctx[CoreModule.NET_IDS]

    /** Waves this system has sent since the process started. A signal, not state. */
    public var wavesSent: Long = 0L
        private set

    /** Creeps this system has queued. Equal to `wavesSent * CREEPS_PER_WAVE * 2`. */
    public var creepsSpawned: Long = 0L
        private set

    override fun onTick() {
        val state = current() ?: open() ?: return
        state.creepsAlive = creeps.entities.size
        if (!state.towersPlaced) placeTowers(state)
        val now = tick.value
        if (now < state.nextWaveTick) return
        sendWave(state, now)
    }

    /** The lane in this world, or `null` when a swap has just cleared it away. */
    private fun current(): LaneState? {
        val entities = lanes.entities
        if (entities.size == 0) return null
        return with(world) { entities[0][LaneState] }
    }

    /** Opens the lane over a populated world, or `null` over an empty one. */
    private fun open(): LaneState? {
        if (units.entities.size == 0) return null
        val now = tick.value
        val state = LaneState(
            startedTick = now,
            nextWaveTick = now + LaneGeometry.FIRST_WAVE_TICK,
        )
        val entity = world.entity { it += state }
        // A `NetId`, because the agent surface addresses entities by net id and a lane nothing can
        // name is a lane nothing can read. Same argument as `MatchSystem.install`.
        netIds.allocate(entity)
        return state
    }

    /**
     * Queues both towers, once.
     *
     * The flag is set before the spawn rather than after it, because a spawn is a barrier action
     * that lands at the top of the *next* tick: a flag written on arrival would let this run again
     * on the tick in between and put four towers on the field.
     */
    private fun placeTowers(state: LaneState) {
        state.towersPlaced = true
        val spawner = ctx.blueprints
        var index = 0
        while (index < LaneGeometry.TEAMS.size) {
            val team = LaneGeometry.TEAMS[index]
            spawner.spawn(
                blueprint = TowerBlueprint,
                position = SpawnPosition(LaneGeometry.towerX(team), LaneGeometry.TOWER_Y),
                overrides = TowerBlueprint.on(team),
            )
            index++
        }
    }

    /** Sends one wave: [LaneGeometry.CREEPS_PER_WAVE] a side, from each base, scattered. */
    private fun sendWave(state: LaneState, now: Long) {
        state.waveNumber++
        state.nextWaveTick = now + LaneGeometry.WAVE_INTERVAL_TICKS
        val spawner = ctx.blueprints
        val blueprints = ctx[MobaBlueprints.KEY]
        val rng = ctx.rng
        var side = 0
        while (side < LaneGeometry.TEAMS.size) {
            val team = LaneGeometry.TEAMS[side]
            val start = LaneGeometry.startWaypoint(team)
            val blueprint =
                if (team == Team.SOLDIER) blueprints.soldier else blueprints.skeleton
            var creep = 0
            while (creep < LaneGeometry.CREEPS_PER_WAVE) {
                val jitterX = scatter(rng.nextFloat(RngStream.Wave))
                val jitterY = scatter(rng.nextFloat(RngStream.Wave))
                spawner.spawn(
                    blueprint = blueprint,
                    position = SpawnPosition(
                        LaneGeometry.PATH_X[start] + jitterX,
                        LaneGeometry.PATH_Y[start] + jitterY,
                    ),
                    overrides = creepOverrides(team, start, state.waveNumber),
                )
                creepsSpawned++
                creep++
            }
            side++
        }
        wavesSent++
    }

    /** A `[0,1)` draw mapped onto `[-SPAWN_SCATTER, SPAWN_SCATTER)`. */
    private fun scatter(unit: Float): Float =
        (unit * 2f - 1f) * LaneGeometry.SPAWN_SCATTER

    override fun toString(): String = "LaneSystem(waves=$wavesSent, creeps=$creepsSpawned)"

    public companion object {

        /**
         * What a creep gets on top of the level blueprint it is spawned from.
         *
         * The [LastHit] is granted here rather than lazily by [LastHitSystem], so that the one
         * component the economy depends on is present from the creep's first tick and the hot
         * path never has to change an archetype mid-iteration.
         */
        public fun creepOverrides(team: Int, waypoint: Int, wave: Int): SpawnOverrides =
            SpawnOverrides { context, entity ->
                with(context) {
                    entity += LaneCreep(
                        heading = LaneGeometry.heading(team),
                        waypoint = waypoint,
                        waveNumber = wave,
                    )
                    entity += LastHit()
                }
            }
    }
}

/**
 * Walks a creep down the lane when it has nothing to fight.
 *
 * ## The one condition, and why it is somebody else's field
 *
 * `UnitBattleSystem` already owns closing on an enemy: it writes the nearest hostile within
 * `AGGRO_RADIUS` to [GameUnit.targetRaw] and walks the unit at `UnitKind.moveSpeed` until it is
 * inside `UnitKind.reach`. A creep is an ordinary level unit, so all of that applies to it
 * unchanged, and a second mover fighting it for the same [Position] would net out to a fraction
 * of either speed - the exact bug `UnitBattleSystem`'s own rout guard exists to stop.
 *
 * So this system moves a creep on **one** condition: `targetRaw` is [NetId.NONE], which is
 * `UnitBattleSystem`'s way of saying it found nothing hostile within three hundred units and
 * therefore did not move this unit at all. Registered `after(UnitBattleSystem)`, so the value read
 * is this tick's. That is the whole of the interlock, and it is why lane creeps behave the way
 * MOBA creeps do: they march until they see something, then they stop marching and fight it.
 *
 * ## Pathfinding, honestly described
 *
 * This is waypoint following over a five-point polyline, not a navmesh and not A*. There is
 * nothing to path *around* in this game - `PhysicsWorld` is a no-op and the field has no
 * obstacles - so a graph search would be a search over a graph with one edge per node. What the
 * polyline buys is the thing a straight line does not: the lane bends, which is what makes it
 * read as a lane in a frame rather than as a queue.
 *
 * [GameUnit.movingTick] is stamped, because `CharacterStateSystem` reads exactly that field to
 * decide between the walk and the idle animation. Without it a marching creep slides down the
 * lane in its idle pose, which is the single most obvious way a renderer looks broken.
 */
public class LaneMarchSystem : SimSystem() {

    private val creeps: Family = world.family { all(LaneCreep, GameUnit, Position, Combatant) }

    /** Creep-steps walked since the process started. Zero over a live lane is a broken seam. */
    public var stepsTaken: Long = 0L
        private set

    /** Waypoints reached. Zero after a wave has had time to cross means nothing is advancing. */
    public var waypointsReached: Long = 0L
        private set

    override fun onTick() {
        val entities = creeps.entities
        val now = tick.value
        var index = 0
        while (index < entities.size) {
            march(entities[index], now)
            index++
        }
    }

    private fun march(entity: Entity, now: Long) {
        val unit = entity[GameUnit]
        // Something is in aggro range and `UnitBattleSystem` has already dealt with this unit
        // this tick - closed on it, or stood still inside its own reach and swung. Either way
        // the creep is fighting, and a creep that marched while fighting would walk out of its
        // own melee range every tick.
        if (unit.targetRaw != NetId.NONE.raw) return
        val position = entity[Position]
        if (position.hp <= 0f) return
        val creep = entity[LaneCreep]
        val target = creep.waypoint
        val dx = LaneGeometry.PATH_X[target] - position.x
        val dy = LaneGeometry.PATH_Y[target] - position.y
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        if (distance <= LaneGeometry.WAYPOINT_RADIUS) {
            advance(creep)
            return
        }
        position.x += dx / distance * LaneGeometry.MARCH_SPEED
        position.y += dy / distance * LaneGeometry.MARCH_SPEED
        unit.movingTick = now
        stepsTaken++
    }

    /**
     * Aims at the next waypoint, or holds at the end of the lane.
     *
     * Holding rather than wrapping: a creep that reached the enemy base and turned round would
     * walk back through its own wave for ever. There is no nexus to attack yet - see [Tower] on
     * why nothing in this wave is destructible - so the honest behaviour at the end of the lane
     * is to stand in the enemy base and fight whatever arrives.
     */
    private fun advance(creep: LaneCreep) {
        val next = creep.waypoint + creep.heading
        if (next < 0 || next >= LaneGeometry.WAYPOINTS) return
        creep.waypoint = next
        waypointsReached++
    }

    override fun toString(): String =
        "LaneMarchSystem(steps=$stepsTaken, waypoints=$waypointsReached)"
}

/**
 * A tower picks a target by the rules in issue #130 and shoots it on a cooldown.
 *
 * ## The rule, in the order it is applied
 *
 * 1. **A champion that has hit an ally in range** inside the last
 *    [LaneGeometry.TOWER_AGGRO_MEMORY_TICKS] ticks, and is itself in range. This is the override
 *    the brief names, and it is what stops a champion standing under a tower killing its creeps
 *    with impunity.
 * 2. **The nearest enemy creep** in range. The default, and the reason a lane pushes: a wave
 *    that reaches a tower is a wave the tower eats.
 * 3. **The nearest enemy anything else** in range - a champion that has kept its hands to itself,
 *    or a line unit that wandered up. "It hits whoever comes close" is the sentence in the brief
 *    and rule 3 is what makes it literally true rather than true of creeps only.
 *
 * ## How it knows a champion hit an ally
 *
 * Off [LastHit], which [LastHitSystem] writes from the GAS cue queue. That is the only record in
 * this game of who struck whom - see [LastHit] on why the alternative was four edits to files
 * this wave does not own.
 *
 * ## How it deals damage without an ability
 *
 * A tower has no `Abilities`, so `AbilitySystem` will never run anything for it. It opens an
 * application on the same [EffectApplier] every exec uses, with `ability/damage` and its own
 * `NetId` as the source. Two consequences worth naming: the damage goes through the one damage
 * path in the game, so armour and any future mitigation apply to it automatically; and the
 * `MobaCues.DAMAGE` cue it emits carries the tower as the source, so [LastHitSystem] attributes a
 * tower kill to the tower and the champion beside it earns nothing.
 *
 * ## Allocation
 *
 * None per tick. The scan is a nested index loop over two families and everything written is a
 * primitive field; the applier is reused and its staged magnitudes are a fixed array.
 */
public class TowerSystem(
    /** Which effect index means `ability/damage`. */
    private val effects: MobaEffects,
    /** The tag `ability/damage` reads its magnitude out of. */
    private val tags: MobaTags,
    /** The one applier in the game. See the class KDoc on why a tower borrows it. */
    private val applier: EffectApplier,
) : SimSystem() {

    private val towers: Family = world.family { all(Tower, Position) }

    /** Everything a tower can shoot: alive, on a side, and able to take an effect. */
    private val targets: Family =
        world.family { all(Combatant, Position, Attributes, GameUnit) }

    private val netIds: NetIdIndex = ctx[CoreModule.NET_IDS]

    /** Shots fired by every tower since the process started. A signal, not state. */
    public var shotsFired: Long = 0L
        private set

    /**
     * Ticks on which rule 1 decided the target: a tower pointing at a champion for hitting an
     * ally rather than at the creep it would otherwise have shot.
     *
     * Ticks and not switches, because aggro is a state and not an edge - the tower holds the
     * champion for the whole of [LaneGeometry.TOWER_AGGRO_MEMORY_TICKS]. Zero over a lane a
     * champion has been fighting in is the rule not firing at all.
     */
    public var championOverrideTicks: Long = 0L
        private set

    override fun onTick() {
        val entities = towers.entities
        val now = tick.value
        var index = 0
        while (index < entities.size) {
            fire(entities[index], now)
            index++
        }
    }

    private fun fire(self: Entity, now: Long) {
        val tower = self[Tower]
        val position = self[Position]
        val target = acquire(tower, position, now)
        if (target == null) {
            tower.targetRaw = NetId.NONE.raw
            return
        }
        tower.targetRaw = netIds.netIdOf(target).raw
        if (tower.readyTick != Long.MIN_VALUE && now < tower.readyTick) return
        tower.readyTick = now + LaneGeometry.TOWER_COOLDOWN_TICKS
        shoot(self, target)
    }

    /**
     * The entity this tower should be pointing at, or `null`.
     *
     * One pass over the target family, tracking the best candidate in each of the three
     * categories at once, so the rule costs one scan rather than three.
     */
    private fun acquire(tower: Tower, from: Position, now: Long): Entity? {
        val entities = targets.entities
        var aggressor: Entity? = null
        var aggressorDistance = Float.MAX_VALUE
        var creep: Entity? = null
        var creepDistance = Float.MAX_VALUE
        var other: Entity? = null
        var otherDistance = Float.MAX_VALUE
        var index = 0
        while (index < entities.size) {
            val candidate = entities[index]
            index++
            val position = candidate[Position]
            if (position.hp <= 0f) continue
            val distance = length(position.x - from.x, position.y - from.y)
            if (distance > LaneGeometry.TOWER_RANGE) continue
            val team = candidate[GameUnit].team
            if (Team.isHostile(tower.team, team)) {
                if (LaneCreep in candidate) {
                    if (distance < creepDistance) {
                        creepDistance = distance
                        creep = candidate
                    }
                } else if (distance < otherDistance) {
                    otherDistance = distance
                    other = candidate
                }
                continue
            }
            // An ally of this tower, in range. Who last hit it?
            if (team != tower.team) continue
            val attacker = recentChampionAttacker(candidate, tower.team, now) ?: continue
            val attackerPosition = attacker[Position]
            val attackerDistance =
                length(attackerPosition.x - from.x, attackerPosition.y - from.y)
            if (attackerDistance > LaneGeometry.TOWER_RANGE) continue
            if (attackerDistance < aggressorDistance) {
                aggressorDistance = attackerDistance
                aggressor = attacker
            }
        }
        if (aggressor != null) {
            championOverrideTicks++
            return aggressor
        }
        return creep ?: other
    }

    /**
     * The champion that hit [ally] within the aggro window, or `null`.
     *
     * A champion is an entity with a `Player` on it: the unit a human is driving. A tower does not
     * change its mind for a creep hitting a creep, because that is what creeps are for.
     */
    private fun recentChampionAttacker(ally: Entity, towerTeam: Int, now: Long): Entity? {
        val record = ally.getOrNull(LastHit) ?: return null
        if (!record.known) return null
        if (now - record.tick > LaneGeometry.TOWER_AGGRO_MEMORY_TICKS) return null
        val attacker = netIds.resolveOrNull(NetId.ofRaw(record.attackerRaw)) ?: return null
        if (Player !in attacker) return null
        val unit = attacker.getOrNull(GameUnit) ?: return null
        if (!Team.isHostile(towerTeam, unit.team)) return null
        val attackerPosition = attacker.getOrNull(Position) ?: return null
        if (attackerPosition.hp <= 0f) return null
        return attacker
    }

    /** Applies [LaneGeometry.TOWER_DAMAGE] through the one damage path this game has. */
    private fun shoot(self: Entity, target: Entity) {
        val attributes = target[Attributes]
        val applied = target.getOrNull(GameplayEffects) ?: return
        val source = netIds.netIdOf(self)
        val targetId = netIds.netIdOf(target)
        applier
            .begin(effects.damage)
            .magnitude(tags.dataDamage, -LaneGeometry.TOWER_DAMAGE)
            .applyTo(applied, attributes, tick, targetId = targetId, source = source)
        self[Tower].shots++
        shotsFired++
    }

    private fun length(dx: Float, dy: Float): Float = kotlin.math.sqrt(dx * dx + dy * dy)

    override fun toString(): String =
        "TowerSystem(shots=$shotsFired, championTicks=$championOverrideTicks)"
}

/**
 * Writes down who struck whom, off the GAS cue queue, before anything drains it.
 *
 * ## Placement, which is the whole correctness argument
 *
 * `SimPhase.Cleanup`, `before(GasCueForwardSystem)`. That is the same seam `EffectSpawnSystem`
 * uses and for the same reason: `GasCueForwardSystem` empties the queue, so a reader after it
 * reads nothing. Everything that can emit a `MobaCues.DAMAGE` this tick - an exec in
 * `SimPhase.Ability`, an arrow in `SimPhase.PostPhysics`, a tower in `SimPhase.Gameplay` - has run
 * by the time `Cleanup` starts, so one pass here sees every blow of the tick in emission order and
 * the last one it sees is the last one that landed.
 *
 * `DeathSystem` runs in `SimPhase.Gameplay`, so the unit that died to that blow is already a
 * `Corpse` by the time this writes the attribution down - which is exactly what [BountySystem]
 * wants on the very next system in the phase.
 *
 * ## Why a lazy grant is acceptable here
 *
 * Creeps are given a [LastHit] at spawn, so the path that matters never changes an archetype.
 * Everything else - a line unit, the champion - is granted one the first time it is hit, which is
 * at most once per entity per match and never on a per-tick path.
 */
public class LastHitSystem(
    /** The queue every damage application emits into. */
    private val cues: GasCueQueue,
) : SimSystem() {

    private val netIds: NetIdIndex = ctx[CoreModule.NET_IDS]

    /** Blows recorded since the process started. Zero over a live fight is a broken seam. */
    public var recorded: Long = 0L
        private set

    override fun onTick() {
        val now = tick.value
        var index = 0
        while (index < cues.size) {
            val event = cues.eventAt(index)
            index++
            if (event.cueId != MobaCues.DAMAGE) continue
            if (event.source.isNone) continue
            if (event.target.isNone) continue
            val victim = netIds.resolveOrNull(event.target) ?: continue
            record(victim, event.source, now)
        }
    }

    private fun record(victim: Entity, attacker: NetId, now: Long) {
        with(world) {
            val existing = victim.getOrNull(LastHit)
            if (existing == null) {
                victim.configure { it += LastHit(attackerRaw = attacker.raw, tick = now) }
            } else {
                existing.attackerRaw = attacker.raw
                existing.tick = now
            }
        }
        recorded++
    }

    override fun toString(): String = "LastHitSystem(recorded=$recorded)"
}

/**
 * Pays a creep's bounty out: gold to the killer alone, experience to everybody near.
 *
 * ## The distinction this class exists to make
 *
 * Gold goes to **one** entity - the one [LastHit] names on the tick the creep died - and only if
 * that entity carries a [Wallet]. Experience goes to **every** enemy champion within
 * [LaneGeometry.XP_RADIUS] whether they touched the creep or not. That asymmetry is the laning
 * phase: a champion that stands in the lane levels up, and a champion that lands the killing blow
 * gets paid. Everything else in this file is machinery for those two sentences.
 *
 * A tower can hold the last hit. A tower has no [Wallet], so nobody is paid - which is the denial
 * every player of the genre knows, arrived at without a special case.
 *
 * ## Why it pays a corpse rather than watching a death
 *
 * `DeathSystem` does not publish a death anywhere a later system can read; it emits a
 * `MobaCues.DEATH` cue whose source and target are both the *victim*, so the killer is not in it.
 * What it does leave behind is a `Corpse` on an entity that has lost its `Combatant`, and that is
 * a state rather than an event - so this looks for a creep corpse whose [LaneCreep.paid] is still
 * false. Idempotent by construction, which is what makes it correct across a `time.step(100)` and
 * across a rewind: `paid` is snapshotted, so a rewind to before the kill unpays it and the same
 * creep is paid again for the same blow rather than a second time.
 */
public class BountySystem(
    /** `health`, so a level-up can raise the number a respawn restores. */
    private val health: AttributeId,
    /** `maxHealth`, the bar a level-up widens. */
    private val maxHealth: AttributeId,
    /** `strength`, which is what `MeleeAttackExec` reads for damage. */
    private val strength: AttributeId,
) : SimSystem() {

    /** Creep bodies. Disjoint from the living by construction: `DeathSystem` takes the `Combatant`. */
    private val bodies: Family = world.family { all(LaneCreep, Corpse, Position, GameUnit) }

    /** Everybody who can be paid. */
    private val wallets: Family = world.family { all(Wallet, Position, GameUnit) }

    private val netIds: NetIdIndex = ctx[CoreModule.NET_IDS]

    /** Bounties paid. One per creep that has died, whether or not anybody collected. */
    public var bountiesSettled: Long = 0L
        private set

    /** Last hits credited to a wallet. Strictly fewer than [bountiesSettled] in a real lane. */
    public var goldPaid: Long = 0L
        private set

    /** Level-ups granted. */
    public var levelUps: Long = 0L
        private set

    override fun onTick() {
        val entities = bodies.entities
        var index = 0
        while (index < entities.size) {
            settle(entities[index])
            index++
        }
    }

    private fun settle(body: Entity) {
        val creep = body[LaneCreep]
        if (creep.paid) return
        creep.paid = true
        bountiesSettled++
        val position = body[Position]
        val victimTeam = body[GameUnit].team
        payGold(body, creep)
        shareExperience(position, victimTeam, creep.xpBounty)
    }

    /** Gold to whoever [LastHit] names, and to nobody else. */
    private fun payGold(body: Entity, creep: LaneCreep) {
        val record = body.getOrNull(LastHit) ?: return
        if (!record.known) return
        val killer = netIds.resolveOrNull(NetId.ofRaw(record.attackerRaw)) ?: return
        val wallet = killer.getOrNull(Wallet) ?: return
        wallet.gold += creep.goldBounty
        wallet.lastHits++
        goldPaid++
    }

    /** Experience to every enemy champion standing within [LaneGeometry.XP_RADIUS]. */
    private fun shareExperience(at: Position, victimTeam: Int, xp: Int) {
        val entities = wallets.entities
        var index = 0
        while (index < entities.size) {
            val champion = entities[index]
            index++
            if (!Team.isHostile(victimTeam, champion[GameUnit].team)) continue
            val position = champion[Position]
            val distance = kotlin.math.sqrt(
                (position.x - at.x) * (position.x - at.x) +
                    (position.y - at.y) * (position.y - at.y),
            )
            if (distance > LaneGeometry.XP_RADIUS) continue
            val wallet = champion[Wallet]
            wallet.xp += xp
            levelUp(champion, wallet)
        }
    }

    /**
     * Spends experience on levels, and applies what a level is worth.
     *
     * A `while` and not an `if`, because a single creep can carry a champion across two thresholds
     * at low level and a champion that banked a level without taking it would be permanently one
     * behind its own experience.
     *
     * The stat growth is written with `setBase` and not to `current`: `AttributeRecompute` rebuilds
     * `current` from `base` plus every active modifier on the next `SimPhase.Attribute`, so a write
     * to `current` would survive exactly until then. `RespawnSystem` makes the same call for the
     * same reason.
     */
    private fun levelUp(champion: Entity, wallet: Wallet) {
        val attributes = champion.getOrNull(Attributes) ?: return
        while (
            wallet.level < LaneGeometry.MAX_LEVEL &&
            wallet.xp >= LaneGeometry.xpForLevel(wallet.level + 1)
        ) {
            wallet.level++
            attributes.setBase(
                maxHealth,
                attributes.base(maxHealth) + LaneGeometry.HEALTH_PER_LEVEL,
            )
            // The current pool goes up with the maximum, so a level-up is a heal rather than a
            // widening of a bar that stays where it was.
            attributes.setBase(health, attributes.base(health) + LaneGeometry.HEALTH_PER_LEVEL)
            attributes.setBase(
                strength,
                attributes.base(strength) + LaneGeometry.STRENGTH_PER_LEVEL,
            )
            levelUps++
        }
    }

    override fun toString(): String =
        "BountySystem(settled=$bountiesSettled, paid=$goldPaid, levels=$levelUps)"
}

/**
 * Gives a champion a [Wallet].
 *
 * ## Why a system and not a blueprint
 *
 * The champion is `level/test_level`'s authored `player` entity, dressed by a `SpawnOverrides`
 * that adds a `Player` and nothing else, and that override lives in `TestLevelScene` - a file
 * this wave does not own. Granting here also means an agent that spawns a second champion with
 * `world.spawn_blueprint` gets a working wallet without knowing this package exists, and a match
 * restart re-grants a fresh one because the entity carrying the old one was destroyed with the
 * scene.
 *
 * It is one `configure` per champion per match. There is no per-tick allocation: after the first
 * tick the `getOrNull` finds the wallet and nothing is created.
 */
public class ChampionSystem : SimSystem() {

    private val champions: Family = world.family { all(Player, Position) }

    /** Wallets granted since the process started. One per champion per match. */
    public var granted: Long = 0L
        private set

    override fun onTick() {
        val entities = champions.entities
        // Backwards: `configure` changes an archetype, which compacts the family bag, and walking
        // forwards would skip whichever entity moved into the slot just vacated.
        var index = entities.size - 1
        while (index >= 0) {
            val entity = entities[index]
            index--
            if (entity.getOrNull(Wallet) != null) continue
            with(world) { entity.configure { it += Wallet() } }
            granted++
        }
    }

    override fun toString(): String = "ChampionSystem(granted=$granted)"
}

/**
 * Makes a champion's death cost more the higher its level.
 *
 * ## What it adds to `RespawnSystem`, which already owns the timer
 *
 * `RespawnSystem` schedules `now + MatchRules.RESPAWN_TICKS`, flat, for every champion, on the
 * first tick it sees a body. Issue #131 asks for a respawn timer, and a timer that is the same
 * three seconds at level one and at level eighteen is a timer in name only: the reason the genre
 * has one is that dying late costs more than dying early.
 *
 * So this runs `after(RespawnSystem)` and rewrites the tick as a function of the wallet's level.
 * It is written as an absolute expression of `Corpse.diedTick` rather than as an addition to
 * whatever `RespawnSystem` wrote, so it is **idempotent**: it computes the same tick on every one
 * of the hundred and eighty ticks the body lies there, rather than pushing the respawn further
 * away every tick and never letting the champion up at all. `LaneRespawnTest` proves that by
 * stepping the whole timer out and watching the unit stand up.
 *
 * [LaneGeometry.respawnTicks] caps the result below `DeathSystem.CORPSE_TICKS`, because a body
 * that has lain there that long is removed outright - net id, attributes and abilities with it -
 * and a respawn scheduled past that point would find nothing to stand up, which is a human's
 * controls going dead for the rest of the match.
 */
public class ChampionRespawnSystem : SimSystem() {

    private val bodies: Family = world.family { all(Player, Wallet, Respawn, Corpse) }

    /** Respawns this system has lengthened. */
    public var scaled: Long = 0L
        private set

    override fun onTick() {
        val entities = bodies.entities
        var index = 0
        while (index < entities.size) {
            val entity = entities[index]
            index++
            val respawn = entity[Respawn]
            if (!respawn.isScheduled) continue
            val due = entity[Corpse].diedTick +
                LaneGeometry.respawnTicks(entity[Wallet].level, MatchRules.RESPAWN_TICKS)
            if (respawn.readyTick == due) continue
            respawn.readyTick = due
            scaled++
        }
    }

    override fun toString(): String = "ChampionRespawnSystem(scaled=$scaled)"
}

/**
 * Copies the lane onto [LaneService] once a tick.
 *
 * The authority is [LaneState] and [Wallet] on entities, because a component is the only kind of
 * state a `time.rewind` restores. This is the mirror everything that only wants to *look* reads -
 * a HUD, a renderer, a test - so that none of them needs a family lookup and a `with(world)`, and
 * so that none of them can hold a reference to an entity a scene swap is about to destroy.
 *
 * Last in `SimPhase.Cleanup`, so what it publishes is the state at the end of the tick.
 */
public class LanePublishSystem(
    /** The mirror. One object, owned by `LaneModule`. */
    private val service: LaneService,
) : SimSystem() {

    private val lanes: Family = world.family { all(LaneState) }

    private val champions: Family = world.family { all(Player, Wallet) }

    override fun onTick() {
        with(world) {
            val lanes = lanes.entities
            if (lanes.size > 0) service.publish(lanes[0][LaneState])
            val champions = champions.entities
            service.publish(if (champions.size > 0) champions[0][Wallet] else null)
        }
    }

    override fun toString(): String = "LanePublishSystem($service)"
}
