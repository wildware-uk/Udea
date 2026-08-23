package dev.wildware.udea.core.snapshot

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.identity.NetIdVisitor
import dev.wildware.udea.core.loop.BarrierAction
import dev.wildware.udea.core.loop.SimBarrier
import dev.wildware.udea.core.rng.CapturableRng

/**
 * Capture and restore, through the generated `Replicator`s and nothing else.
 *
 * Spec 3.1 settles this: there is no `SnapshotCodec`, no `@Snapshotted`, and **no reflective
 * fallback** — not even a temporary one "so the mechanism works end to end before codegen
 * exists". This replaces the reflective component inspection in `common/reflection.kt` and
 * the `isNetworkable()`/`getNetworkData()` helpers in `common/utils.kt`, and it replaces them
 * with call sites the compiler checks.
 *
 * Capture uses `Replicator.allMask` — `@Net` **and** `@Sim` — while a delta write uses
 * `netMask`, which is how a jungle timer and a bot blackboard rewind without ever reaching a
 * client.
 *
 * ## Capture order
 *
 * Ascending [NetId], via `NetIdIndex.forEachLive`. Two processes holding the same live set
 * therefore produce identical stores no matter what order their entities were spawned in,
 * which is what makes [WorldHasher] a determinism gate rather than an insertion-order
 * detector.
 *
 * ## Restore is a between-tick mutation
 *
 * [restore] enqueues on [SimBarrier] and applies at the top of `Simulation.step()`, so no
 * system ever observes half a restored world (spec 3.3). [applyNow] is the same work without
 * the queue, for a caller that is already at a tick boundary and holds the barrier — which is
 * exactly what a paused `TimeControl.rewind` is.
 *
 * ## Allocation
 *
 * Capture allocates nothing once the target snapshot is warm: one reused visitor, pooled
 * columns, pooled scratch. Restore allocates only when it has to create an entity or a
 * component that the live world does not have, which by definition is not a per-tick path.
 */
public class SnapshotService(
    /** Every replicated component type, in canonical order. */
    public val registry: ComponentRegistry,
    private val world: World,
    private val ctx: GameContext,
    /** The one place a [NetId] becomes an `Entity`. Capture walks it; restore rebuilds it. */
    public val netIds: NetIdIndex,
    /**
     * Subsystems that [SnapshotExclusion] says must be brought to a defined state after a
     * restore. Empty in a headless simulation, which has no particles and no camera.
     */
    private val excluded: List<ExcludedSubsystem> = emptyList(),
) {

    /**
     * The context's RNG, proven capturable at construction.
     *
     * Loud here rather than silent later: a service built over an `RngService` whose state
     * cannot be snapshotted would capture and restore a world whose random streams keep
     * running, and the snapshot-equivalence gate would then fail on the first tick that drew
     * a number, with nothing pointing at the cause.
     */
    private val rng: CapturableRng = ctx.rng as? CapturableRng ?: throw IllegalArgumentException(
        "SnapshotService needs an RngService that implements CapturableRng so random streams " +
            "rewind with the world; ${ctx.rng::class.simpleName} does not",
    )

    private val captureVisitor = CaptureVisitor()

    /** Scratch for restore: the ids the snapshot holds, and the entity each one will be on. */
    private var rosterIds: IntArray = IntArray(INITIAL_ROSTER)
    private var rosterEntities: Array<Entity?> = arrayOfNulls(INITIAL_ROSTER)

    /** Scratch for restore: every id currently live, read before the index is rebuilt. */
    private var liveIds: IntArray = IntArray(INITIAL_ROSTER)
    private var liveCount: Int = 0
    private val liveVisitor = NetIdVisitor { netId, _ -> recordLive(netId) }

    /** Restores applied since construction. A health signal an agent can poll. */
    public var restoreCount: Long = 0L
        private set

    /** A fresh snapshot slot sized for this registry. Only tests and the ring should build one. */
    public fun newSnapshot(): WorldSnapshot = WorldSnapshot(registry)

    /**
     * Every component class on a live entity that this service cannot capture.
     *
     * Capture asks the registry what to look for, so a component type the registry does not know
     * about is silently absent from every snapshot and silently missing from every restored
     * entity. This is the question asked the other way round. Allocating and off the per-tick
     * path by construction - see [SnapshotCoverage].
     */
    public fun uncoveredComponents(): List<String> =
        SnapshotCoverage.uncovered(registry, world, netIds)

    /**
     * Captures the whole world into [target], which is reset first.
     *
     * @throws IllegalStateException if [target] was built against a different registry, which
     *   would mean its columns are laid out for other components entirely.
     */
    public fun captureInto(target: WorldSnapshot) {
        check(target.registry === registry) {
            "snapshot was built against ${target.registry}, not $registry"
        }
        target.reset()
        target.tick = ctx.clock.tick
        target.scene = ctx.scenes.activeSceneId

        captureVisitor.target = target
        netIds.forEachLive(captureVisitor)
        captureVisitor.target = null

        rng.saveInto(target.rngBuffer(rng.stateWords), 0)
        netIds.saveInto(target.handles)
        target.isFilled = true
    }

    /** Captures into a freshly allocated snapshot. Convenience for tests; the ring pools. */
    public fun capture(): WorldSnapshot = newSnapshot().also { captureInto(it) }

    /**
     * Queues [snapshot] to be applied at the top of the next `Simulation.step()`.
     *
     * The mutation is named rather than a lambda, so a desync triage that asks why the world
     * changed between two ticks gets "restore snapshot t100" and not an anonymous closure.
     *
     * @return the submitted action, whose [RestoreAction.failure] is how a caller finds out
     *   whether the restore actually landed. [SimBarrier.drain] catches and logs a throwing
     *   action and carries on by design, so a caller that only looked at the barrier returning
     *   would believe a half-applied restore had succeeded — and `SimBarrier.failedActions` is
     *   no substitute, because it also counts unrelated actions that were queued behind this
     *   one.
     */
    public fun restore(snapshot: WorldSnapshot, barrier: SimBarrier): RestoreAction {
        require(snapshot.isFilled) { "cannot restore an empty snapshot slot" }
        return RestoreAction(snapshot).also(barrier::submit)
    }

    /**
     * Applies [snapshot] to the world immediately.
     *
     * Only correct at a tick boundary — from a [BarrierAction], or from a caller holding a
     * paused loop, which is what `TimeControl.rewind` is. Mid-tick it would tear the world.
     *
     * @throws SceneMismatchException when the snapshot belongs to another scene. Loading the
     *   scene is the caller's job (spec 5, scene lifecycle); applying entity ids minted in a
     *   different scene would silently bind them to whatever occupies those slots now.
     */
    public fun applyNow(snapshot: WorldSnapshot) {
        require(snapshot.isFilled) { "cannot restore an empty snapshot slot" }
        val active = ctx.scenes.activeSceneId
        if (snapshot.scene != active) throw SceneMismatchException(snapshot.scene, active)

        val fields = snapshot.fields
        ensureRoster(fields.rowCount)

        collectLiveIds()
        destroyEntitiesNotIn(fields)
        resolveOrCreateRoster(fields)
        rebindIdentity(snapshot)
        applyComponents(fields)

        ctx.clock.moveTo(snapshot.tick)
        rng.restoreFrom(snapshot.rng, 0)

        // Box2D is never snapshot state (spec 3.4): bodies come back from components in one
        // deterministic pass, and this is the last thing a restore does — after every
        // Replicator.apply above, because the components it reads are what those calls just
        // wrote. Ascending NetId order comes from the index, which is why it is passed.
        ctx.physics.rebuildFrom(world, netIds)

        for (subsystem in excluded) subsystem.onRestored()
        restoreCount++
    }

    // --- capture -----------------------------------------------------------------------------

    /**
     * One reusable visitor. A lambda would capture the target and allocate once per capture,
     * which is sixty allocations a second on the path spec 7 gates at zero.
     */
    private inner class CaptureVisitor : NetIdVisitor {

        var target: WorldSnapshot? = null

        override fun visit(netId: NetId, entity: Entity) {
            val snapshot = checkNotNull(target) { "capture visitor ran outside a capture" }
            val fields = snapshot.fields
            val row = fields.appendRow(netId)
            for (component in 0 until registry.size) {
                val type = registry.typeAt(component)
                if (!type.isPresent(world, entity)) continue
                val slot = fields.claimSlot(row, component)
                check(type.captureInto(world, entity, fields.storeAt(component), slot)) {
                    "${registry.schemaAt(component).typeName} vanished from $netId mid-capture"
                }
            }
        }
    }

    // --- restore -----------------------------------------------------------------------------

    private fun collectLiveIds() {
        liveCount = 0
        netIds.forEachLive(liveVisitor)
    }

    private fun recordLive(netId: NetId) {
        if (liveCount == liveIds.size) liveIds = liveIds.copyOf(liveIds.size * 2)
        liveIds[liveCount] = netId.raw
        liveCount++
    }

    /** Removes every entity the snapshot does not have. Its id becomes free on the rebuild. */
    private fun destroyEntitiesNotIn(fields: WorldFieldStore) {
        for (position in 0 until liveCount) {
            val netId = NetId.ofRaw(liveIds[position])
            if (fields.rowOf(netId) != WorldFieldStore.NO_ROW) continue
            val entity = netIds.resolveOrNull(netId) ?: continue
            world -= entity
        }
    }

    /**
     * Pairs every id in the snapshot with the entity it will live on, reusing the entity that
     * already holds that exact id where there is one.
     *
     * Reuse is not an optimisation. `Replicator.apply` mutates in place, so a surviving
     * entity keeps the `Vec2` identity that rendering and physics hold references to; recreating
     * every entity on every rewind would re-point all of them once per rollback.
     */
    private fun resolveOrCreateRoster(fields: WorldFieldStore) {
        for (row in 0 until fields.rowCount) {
            val netId = fields.netIdAt(row)
            rosterIds[row] = netId.raw
            rosterEntities[row] = netIds.resolveOrNull(netId) ?: world.entity { }
        }
    }

    /**
     * Rebuilds the identity index: the captured allocator state, then the captured roster.
     *
     * Order matters. `restoreFrom` clears every binding and installs the free queue and the
     * generations that were recorded, and only then can `bind` reinstate each id — including
     * its generation, so a reference captured before the rewind still resolves and a reference
     * to something destroyed before it still reads stale.
     */
    private fun rebindIdentity(snapshot: WorldSnapshot) {
        netIds.restoreFrom(snapshot.handles)
        for (row in 0 until snapshot.fields.rowCount) {
            val entity = checkNotNull(rosterEntities[row]) { "roster entry $row was never resolved" }
            netIds.bind(entity, NetId.ofRaw(rosterIds[row]))
        }
    }

    private fun applyComponents(fields: WorldFieldStore) {
        for (row in 0 until fields.rowCount) {
            val entity = checkNotNull(rosterEntities[row]) { "roster entry $row was never resolved" }
            for (component in 0 until registry.size) {
                val type = registry.typeAt(component)
                if (fields.isPresent(row, component)) {
                    val slot = fields.componentSlotAt(row, component)
                    type.applyOnto(world, entity, fields.storeAt(component), slot)
                } else {
                    type.removeFrom(world, entity)
                }
            }
        }
    }

    private fun ensureRoster(rows: Int) {
        if (rows <= rosterIds.size) return
        var capacity = rosterIds.size
        while (capacity < rows) capacity *= 2
        rosterIds = rosterIds.copyOf(capacity)
        rosterEntities = rosterEntities.copyOf(capacity)
    }

    /** The named mutation [restore] queues. */
    /**
     * One queued restore, and whether it landed.
     *
     * [failure] exists because the barrier is deliberately forgiving: it logs a throwing
     * action and drains the rest, so "the drain returned" says nothing about this action.
     * Recording the throwable here — and rethrowing it, so the barrier still logs and counts
     * it — is what lets `SnapshotTimeTravel` answer `RewindFailure.RestoreFailed` instead of
     * telling an agent it is at a tick it never reached.
     */
    public inner class RestoreAction internal constructor(
        private val snapshot: WorldSnapshot,
    ) : BarrierAction {

        /** What [applyNow] threw, or `null` while the restore has not run or did not throw. */
        public var failure: Throwable? = null
            private set

        override val label: String get() = "restore snapshot ${snapshot.tick}"

        override fun apply(world: World, ctx: GameContext) {
            failure = null
            try {
                applyNow(snapshot)
            } catch (thrown: Throwable) {
                // Recorded and rethrown, never swallowed: the barrier still logs it with this
                // label and still counts it in `failedActions`, and the caller still gets a
                // typed refusal instead of a rewind that never happened.
                failure = thrown
                throw thrown
            }
        }
    }

    private companion object {
        /** A small scene's roster without a regrow. */
        const val INITIAL_ROSTER: Int = 64
    }
}

/**
 * A restore into a scene the snapshot was not taken in.
 *
 * Typed rather than an `IllegalStateException` because `TimeControl` turns it into the
 * agent-facing `scene_mismatch` result, and an agent needs to be told both scene names to
 * know which one to load.
 */
public class SceneMismatchException(
    public val snapshotScene: SceneId?,
    public val activeScene: SceneId?,
) : IllegalStateException(
    "snapshot belongs to scene ${snapshotScene ?: "<none>"} but ${activeScene ?: "<none>"} is " +
        "active; load the scene before restoring",
)
