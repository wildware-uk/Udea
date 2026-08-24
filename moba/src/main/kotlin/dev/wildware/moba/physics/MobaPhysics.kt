package dev.wildware.moba.physics

import com.github.quillraven.fleks.Entity
import dev.wildware.moba.Player
import dev.wildware.moba.Position
import dev.wildware.moba.ability.Combatant
import dev.wildware.moba.level.GameUnit
import dev.wildware.moba.level.UnitBattleSystem
import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.identity.NetIdVisitor
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.core.physics.BodyDef
import dev.wildware.udea.core.physics.BodyHandle
import dev.wildware.udea.core.physics.BodyHandleBuffer
import dev.wildware.udea.core.physics.BodyKind
import dev.wildware.udea.core.physics.BodyPose
import dev.wildware.udea.core.physics.Circle
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.core.physics.PhysicsStepSystem
import dev.wildware.udea.core.physics.NoOpPhysicsWorld
import dev.wildware.udea.core.physics.PhysicsWorld
import dev.wildware.udea.core.physics.ShapeComponent
import kotlin.math.sqrt

/**
 * The game's physics: a real Box2D world, and the systems that put this game's units in it.
 *
 * ## What this module changes about the game
 *
 * Two things a player can see.
 *
 * **Units stop standing inside each other.** `UnitBattleSystem.separate` was a hand-rolled
 * `O(n^2)` scan that summed a normalised push and applied a flat
 * `SEPARATION_SPEED = 0.13` units per tick - about a fifth of the slowest walk, which is slower
 * than a pack closes, so a crowd converging on one target never actually came apart. Five orcs
 * on one soldier drew as one orc with five healthbars stacked into one. [PhysicsCrowdSystem]
 * replaces it with a real Box2D broadphase query and a real penetration resolve: a unit leaves
 * an overlap by half of however deep it is in it, so an overlap is gone in a tick or two and
 * stays gone. [MobaPhysics.SEPARATION_DISTANCE] is what "gone" means, and it is the same number
 * `PERSONAL_SPACE` always was, so the crowd is the same width - it is now enforced instead of
 * approached.
 *
 * **Arrows hit bodies instead of points.** `ProjectileSystem` asked `CombatWorld.nearest` -
 * a linear scan over every combatant, comparing centre-to-centre distance against
 * `Projectile.hitRadius`, with the unit modelled as a point. It now asks
 * [PhysicsWorld.overlap], which is Box2D's dynamic tree plus [OverlapNarrowphase]'s exact
 * circle test against the unit's actual collision circle.
 *
 * ## What it deliberately does not do (spec 3.4)
 *
 * The solver decides **nothing** about where a unit is. `Position` is written by
 * `PlayerMovementSystem`, `UnitBattleSystem` and `CombatMotionSystem`, and this module's bodies
 * are *mirrors* of it: kinematic, created from `Position`, moved to `Position`, and read back
 * only as answers to spatial questions. Nothing here writes a solver result into a component,
 * and nothing here is snapshot state - which is why a rewind can throw every body away.
 *
 * The honest consequence, stated rather than left to be discovered: **the example game does not
 * yet integrate a dynamic body.** Spec 3.4 keeps Box2D for "sensor queries, debris and
 * server-only projectiles"; this module delivers the first of the three. Debris and
 * solver-integrated projectiles work - `Box2DPhysicsWorldTest` drops dynamic bodies under
 * gravity, stacks them on static geometry and reads real begin/end contacts off them - but no
 * entity in `moba` is one. An arrow is `@Replicated`, so its position must be authoritative and
 * therefore cannot come out of the solver, and there is no debris entity in the game yet.
 *
 * ## Where the mirror bodies come from after a rewind
 *
 * `SnapshotService.applyNow` finishes by calling [PhysicsWorld.rebuildFrom], which destroys
 * every body and rebuilds from `PhysicsBody` components. No `moba` entity carries a
 * `PhysicsBody`, so that pass rebuilds nothing here, and [PhysicsCrowdSystem] rebuilds the
 * mirrors on the next tick from `Position` - which the restore has just written - in ascending
 * [NetId] order. The two together are the "one deterministic pass" spec 3.4 asks for, and
 * `MobaPhysicsRewindProofTest` measures the whole physics world across a real 300-tick rewind
 * to prove it lands in the same place every time.
 *
 * A `PhysicsBody` on a `moba` unit would be the tidier arrangement and is **not** done here for
 * a reason worth writing down: capture walks `MobaGame.componentRegistry`, so a component on a
 * live entity that is not in that list is invisible to a snapshot - `SnapshotCoverage` and
 * `SnapshotRestoreProofTest` fail the build for exactly that, correctly. Registering
 * `PhysicsBody` needs a hand-written `Replicator` and a `ComponentSchema` in `MobaGame`, and
 * `MobaGame.kt` is not this agent's file to restructure.
 */
public class MobaPhysicsModule(
    /** Box2D metres per world unit. See [Box2DPhysicsWorld.metresPerUnit]. */
    private val metresPerUnit: Float = Box2DPhysicsWorld.DEFAULT_METRES_PER_UNIT,
) : UdeaModule {

    override val name: String get() = "moba-physics"

    /**
     * The solver, created in [context] because it needs the configured tick rate.
     *
     * Held so a test, an agent tool or a shutdown hook can reach the concrete world - the
     * counters on it (`stepCount`, `rebuildCount`, `teleportCount`) are the evidence that the
     * game is stepping one solver tick per simulation tick and writing a transform only when
     * something asked it to.
     */
    public var world: Box2DPhysicsWorld? = null
        private set

    /**
     * Replaces `CoreModule`'s `NoOpPhysicsWorld` on the context.
     *
     * This is the swap `UdeaModule.context`'s KDoc describes - "how a game swaps
     * `NoOpPhysicsWorld` for a real Box2D world without the kernel knowing Box2D exists" - and
     * until now nothing in the tree performed it. It works because a module's `context` hook
     * runs for every module in list order, so this one, listed after `MobaModule`, overwrites
     * the field `CoreModule` set.
     *
     * Zero gravity, and that is a game decision rather than an oversight: this is a top-down
     * field seen from above, so "down" is a direction on the ground and not a force. Debris that
     * wants to fall gets its own velocity at spawn.
     */
    override fun context(builder: GameContextBuilder) {
        val created = Box2DPhysicsWorld(
            metresPerUnit = metresPerUnit,
            // Straight from the engine config rather than a literal: `Box2DSystem` hardcoded a
            // sixtieth while its caller ran on the render delta, and that disagreement is the
            // named defect `PhysicsWorld` has no `step(dt)` in order to prevent.
            secondsPerTick = 1f / builder.config.tickRate,
        )
        world = created
        builder.physics = created
    }

    override fun simulation(registry: SimRegistry) {
        registry.add(
            SimPhase.Physics,
            { ctx -> PhysicsCrowdSystem(ctx.physics, ctx[CoreModule.NET_IDS]) },
        ) {
            // Before the solver step, so the bodies Box2D advances are at this tick's positions
            // rather than last tick's - and cross-phase-explicit, so moving either system's
            // phase fails world construction naming both instead of silently costing a tick.
            before(PhysicsStepSystem::class)
        }
    }

    override fun toString(): String = "MobaPhysicsModule(world=$world)"
}

/** The numbers the game's physics is built from, in one place. */
public object MobaPhysics {

    /**
     * How far apart two units end up, in world units.
     *
     * The same value `UnitBattleSystem.PERSONAL_SPACE` has always been, read from there rather
     * than repeated: the crowd is meant to be the same width it was, and the change is that the
     * width is now reached instead of approached. It is also still smaller than every
     * `UnitKind.reach`, which `MobaIntegrationTest` pins - a crowd that held units further apart
     * than they can swing turns the fight into a stand-off.
     */
    public const val SEPARATION_DISTANCE: Float = UnitBattleSystem.PERSONAL_SPACE

    /** A unit's collision circle: half [SEPARATION_DISTANCE], so two of them just touch. */
    public const val UNIT_RADIUS: Float = SEPARATION_DISTANCE / 2f

    /**
     * The most a unit is moved by separation in one tick, in world units.
     *
     * A cap and not a speed. The resolve is proportional to how deep the overlap is, so two
     * units spawned on the same point would otherwise be flung apart in a single tick; the cap
     * turns that into a shove over a handful of ticks. It is well above the old
     * `SEPARATION_SPEED` of `0.13` on purpose - that number was a fifth of the slowest walk, so
     * a pack closing on one target crowded faster than it separated and never came apart.
     */
    public const val MAX_SEPARATION_STEP: Float = 2.5f

    /** Below this, two units count as standing on the same point and are split along +x. */
    internal const val CO_LOCATED: Float = 1e-4f
}

/**
 * One kinematic mirror body per living unit, and the crowd separation that reads them.
 *
 * ## Why the bodies are kinematic and why moving them is not the defect `PhysicsWorld` reverses
 *
 * `PhysicsWorld.teleport`'s KDoc calls a per-tick write-back "the defect this whole interface
 * reverses", and it is right about the case it describes: `Box2DSystem` wrote every entity's
 * transform into its body immediately *before* `world.step`, so the solver's own integration
 * result was thrown away every frame, which is why bodies jittered against static geometry.
 *
 * That argument is about bodies the solver integrates. These are **kinematic with zero
 * velocity**: Box2D never writes their pose, so there is no computed result for a sync to
 * overwrite. The unit's position is decided by the game and mirrored in; the mirror is read
 * back only as an answer to a query.
 *
 * It is still a per-tick `setTransform`, and the interface has no better word for it.
 * `PhysicsWorld` wants an explicit `syncKinematic(handle, pose)` - distinct from `teleport`,
 * which is a one-shot command a `Teleport` component queues - so a spying world can tell a
 * mirror sync from a discontinuous move and assert on each separately. That is a change to
 * `udea-core/.../PhysicsWorld.kt`, which is not this agent's file.
 *
 * ## Determinism
 *
 * Three separate orderings, all explicit, because a rewind rebuilds every body and a broadphase
 * tree's walk order depends on the order things were inserted into it:
 *
 * - bodies are created and destroyed in ascending [NetId], from [NetIdIndex.forEachLive];
 * - [PhysicsWorld.overlap] returns its results sorted by owning [NetId], not by tree order;
 * - the push is summed over those results in that order, so the float rounding is a function of
 *   the simulation's own ids rather than of the solver's internal state.
 *
 * Without the second and third, this system would produce a slightly different push after a
 * rewind than before it, and `MobaPhysicsRewindProofTest` would be flaky rather than red -
 * which is the worse of the two.
 *
 * ## Allocation
 *
 * Nothing per tick. The visitor, the pose, the shape and the handle buffer are fields; the
 * handle table is an `IntArray` indexed by `NetId.raw`.
 */
public class PhysicsCrowdSystem(
    private val physics: PhysicsWorld,
    private val netIds: NetIdIndex,
) : SimSystem() {

    /**
     * `NetId.index` -> live [BodyHandle.raw], or [BodyHandle.NONE].
     *
     * Keyed by [NetId.index] and never by `NetId.raw`: the raw word packs a generation above
     * the index, so an array indexed by it would be sixteen times too small and would throw the
     * first time a slot was recycled.
     */
    private val handleByIndex = IntArray(netIds.capacity) { BodyHandle.NONE.raw }

    /** `NetId.index` -> the tick its body was last seen alive on. The sweep's mark. */
    private val seenTick = LongArray(netIds.capacity) { Long.MIN_VALUE }

    /**
     * `NetId.index` -> whether a human is steering it, as of the sync pass of this tick.
     *
     * Cached rather than resolved per neighbour: the separation pass asks "will the unit I am
     * overlapping move out of my way" once per overlapping pair, and answering that by
     * resolving a [NetId] back to an entity and reading a component would be a lookup per pair
     * per tick.
     */
    private val isPlayerByIndex = BooleanArray(netIds.capacity)

    /**
     * `NetId.index` -> whether the body is a *walking unit* rather than only a hurtbox.
     *
     * The two are different populations and conflating them broke a lane. Everything with a
     * `Combatant` gets a body, because a body is what an arrow hits and a combatant is what an
     * arrow is allowed to hit. Only a `GameUnit` is *separated*, because separation is a crowd
     * rule for things that walk: a tower is a combatant with a position and no legs, and giving
     * it a share of a crowd's push slides a building down its own lane - which is what
     * `LaneProofTest` reported, in seven different ways, the first time this predicate was one
     * predicate instead of two.
     *
     * A unit standing inside a tower is the stated cost. Fixing it means the solver resolving a
     * unit out of a static body, which is a `CharacterMover` question (spec 3.4 gives it the
     * static geometry) rather than a crowd one.
     */
    private val isUnitByIndex = BooleanArray(netIds.capacity)

    /** Which `NetId.index`es own a body. Compact, so the sweep is over bodies and not over ids. */
    private var owned = IntArray(INITIAL_OWNED_CAPACITY)
    private var ownedCount = 0

    /** The shape every unit's mirror body is built from. One instance; `createBody` reads it. */
    private val unitShape = Circle(MobaPhysics.UNIT_RADIUS)

    /** The description `createBody` is handed. Rewritten per creation, never retained by anyone. */
    private val bodyTemplate = PhysicsBody(kind = BodyKind.Kinematic, isSensor = false)

    private val shapeList: List<ShapeComponent> = listOf(unitShape)

    private val scratchPose = BodyPose()
    private val neighbourPose = BodyPose()
    private val overlapping = BodyHandleBuffer()

    /** The last [PhysicsWorld] rebuild this system reacted to. See [onTick]. */
    private var seenRebuilds = -1L

    /** How many mirror bodies exist right now. A health signal a test and an agent read. */
    public val mirrorCount: Int get() = ownedCount

    /** How many units this system has pushed out of an overlap since it was constructed. */
    public var separations: Long = 0L
        private set

    override fun onTick() {
        val now = tick.value
        // A restore destroyed every body (spec 3.4). Handles held here name nothing - on this
        // backend they *resolve* to nothing rather than aliasing, because a handle carries a
        // generation, but the table still has to be emptied or the sweep would try to destroy
        // bodies that are already gone. Keyed off the world's own rebuild counter rather than
        // off a probe, so it costs one comparison a tick.
        val rebuilds = rebuildCountOf(physics)
        if (rebuilds != seenRebuilds) {
            forget()
            seenRebuilds = rebuilds
        }

        sync.now = now
        netIds.forEachLive(sync)
        sweep(now)
        netIds.forEachLive(resolve)
    }

    /** Creates or moves one unit's mirror body. Ascending [NetId], because `forEachLive` is. */
    private val sync = object : NetIdVisitor {

        var now: Long = 0L

        override fun visit(netId: NetId, entity: Entity) {
            val position = with(world) { entity.getOrNull(Position) } ?: return
            // `Combatant` or `GameUnit`, not `GameUnit` alone. A collision body is a *hurtbox*,
            // and what makes something hittable in this game is being a combatant - `GameUnit` is
            // the level's record of which kind and which side it is. The two coincide on the
            // shipping roster, and they do not in `CombatFixture`, which dresses combatants
            // without a level: with the narrower predicate every arrow in that fixture flew
            // through its target, because the target had no body to hit.
            if (with(world) { entity.getOrNull(Combatant) == null && entity.getOrNull(GameUnit) == null }) return
            // A corpse keeps its `Position` and its `GameUnit` until it is cleared away, and
            // something nobody can walk into is what a corpse should be: the mirror is destroyed
            // by the sweep on the tick the unit's health reaches zero.
            if (position.hp <= 0f) return

            val index = netId.index
            // The handle is *checked*, not merely non-null, and that is the difference between a
            // mirror table and a set of dangling pointers. A `NetId` index is recycled when a unit
            // dies and another spawns, and a body is destroyed by anything that rebuilds the world
            // - so an entry in this table can name a body that is gone, or one that now belongs to
            // somebody else. `ownerOf` answers both questions in one lookup: it returns `NetId.NONE`
            // for a dead handle (a handle carries a generation on this backend) and the *current*
            // owner otherwise. Anything that is not this unit's live body is rebuilt.
            //
            // Found by `MatchProofTest`, which restarts a match in-process: the first draft trusted
            // the table and threw `NoSuchBodyException` out of `onTick`, which kills the tick loop.
            val existing = BodyHandle(handleByIndex[index])
            val handle = if (existing.isValid && physics.ownerOf(existing) == netId) {
                existing
            } else {
                if (existing.isValid) forgetOne(index)
                create(netId, position)
            }
            physics.teleport(handle, scratchPose.set(position.x, position.y, 0f))
            seenTick[index] = now
            with(world) {
                isPlayerByIndex[index] = entity.getOrNull(Player) != null
                isUnitByIndex[index] = entity.getOrNull(GameUnit) != null
            }
        }
    }

    /** Pushes one crowded unit out of its overlaps. Ascending [NetId], for the same reason. */
    private val resolve = object : NetIdVisitor {

        override fun visit(netId: NetId, entity: Entity) {
            val index = netId.index
            if (handleByIndex[index] == BodyHandle.NONE.raw) return
            // A hurtbox that is not a walking unit is never pushed: see [isUnitByIndex].
            if (!isUnitByIndex[index]) return
            if (isPlayerByIndex[index]) return
            val position = with(world) { entity.getOrNull(Position) } ?: return
            resolveOne(index, position)
        }
    }

    private fun create(netId: NetId, position: Position): BodyHandle {
        bodyTemplate.x = position.x
        bodyTemplate.y = position.y
        bodyTemplate.angle = 0f
        bodyTemplate.awake = true
        val handle = physics.createBody(BodyDef(bodyTemplate, netId, shapeList))
        handleByIndex[netId.index] = handle.raw
        if (ownedCount == owned.size) owned = owned.copyOf(owned.size * 2)
        owned[ownedCount] = netId.index
        ownedCount++
        return handle
    }

    /** Destroys the mirror of anything that did not report alive on [now]. Order-stable. */
    private fun sweep(now: Long) {
        var write = 0
        var read = 0
        while (read < ownedCount) {
            val index = owned[read]
            read++
            if (seenTick[index] == now) {
                owned[write] = index
                write++
                continue
            }
            physics.destroyBody(BodyHandle(handleByIndex[index]))
            handleByIndex[index] = BodyHandle.NONE.raw
        }
        ownedCount = write
    }

    /** Drops one stale entry, so [create] does not append a second `owned` row for the same id. */
    private fun forgetOne(index: Int) {
        handleByIndex[index] = BodyHandle.NONE.raw
        var slot = 0
        while (slot < ownedCount) {
            if (owned[slot] == index) {
                // Shift rather than swap: `owned` is walked in creation order, and creation order
                // after a rebuild is ascending NetId. A swap-remove would scramble that.
                System.arraycopy(owned, slot + 1, owned, slot, ownedCount - slot - 1)
                ownedCount--
                return
            }
            slot++
        }
    }

    /** Drops every handle without touching the solver: a rebuild has already destroyed them. */
    private fun forget() {
        for (slot in 0 until ownedCount) handleByIndex[owned[slot]] = BodyHandle.NONE.raw
        ownedCount = 0
    }

    /**
     * Pushes one unit out of its overlaps, using the solver as the broadphase.
     *
     * Each unit resolves **half** of each overlap, because the unit on the other side is
     * resolving the other half on the same tick - so a pair separates at the rate the overlap
     * is deep rather than at a fixed crawl. The exception is a neighbour that will not move: a
     * player-driven unit is never shoved (a crowd that slid a human sideways while they held
     * nothing reads as broken controls, and `MobaInputTest` catches exactly that), so the AI
     * unit beside one takes the whole overlap itself and actually leaves.
     */
    private fun resolveOne(index: Int, position: Position) {
        physics.overlap(unitShape, scratchPose.set(position.x, position.y, 0f), overlapping)
        var pushX = 0f
        var pushY = 0f
        var query = 0
        while (query < overlapping.size) {
            val handle = overlapping[query]
            query++
            val owner = physics.ownerOf(handle)
            if (owner.isNone || owner.index == index) continue
            // Nor does one push: a tower is something to be stopped by, not something that
            // shoulders a crowd out of the way, and this system does not do stopping.
            if (!isUnitByIndex[owner.index]) continue
            physics.poseOf(handle, neighbourPose)
            var dx = position.x - neighbourPose.x
            var dy = position.y - neighbourPose.y
            var distance = sqrt(dx * dx + dy * dy)
            if (distance >= MobaPhysics.SEPARATION_DISTANCE) continue
            if (distance < MobaPhysics.CO_LOCATED) {
                // Two units on exactly the same point. Split along +x: arbitrary, deterministic,
                // and the only property this needs. A random direction would want an RngService
                // stream and would still be arbitrary.
                dx = 1f
                dy = 0f
                distance = 1f
            }
            // The whole overlap rather than half of it when the neighbour is a player, because a
            // player is never shoved and would otherwise leave this unit permanently half inside
            // them.
            val share = if (isPlayerByIndex[owner.index]) 1f else 0.5f
            val depth = (MobaPhysics.SEPARATION_DISTANCE - distance) * share
            pushX += dx / distance * depth
            pushY += dy / distance * depth
        }
        val magnitude = sqrt(pushX * pushX + pushY * pushY)
        if (magnitude < MobaPhysics.CO_LOCATED) return
        val step = if (magnitude > MobaPhysics.MAX_SEPARATION_STEP) MobaPhysics.MAX_SEPARATION_STEP else magnitude
        position.x += pushX / magnitude * step
        position.y += pushY / magnitude * step
        separations++
    }

    /** The rebuild counter of whichever backend is installed, or `0` for one that has none. */
    private fun rebuildCountOf(physics: PhysicsWorld): Long = when (physics) {
        is Box2DPhysicsWorld -> physics.rebuildCount
        is NoOpPhysicsWorld -> physics.rebuildCount
        else -> 0L
    }

    private companion object {
        /** More units than the level spawns, so the first tick does not regrow. */
        const val INITIAL_OWNED_CAPACITY: Int = 64
    }
}
