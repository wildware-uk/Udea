package dev.wildware.udea.core.snapshot

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.identity.NetIdIndex

/**
 * Which component types a live world carries that the snapshot spine cannot see.
 *
 * ## The failure this exists to make loud
 *
 * Capture walks the [ComponentRegistry] and asks each registered type whether an entity has it.
 * That direction is right for speed and wrong for safety: a component type nobody registered is
 * not *partially* captured, it is **invisible**. Nothing throws, nothing is logged, and the
 * snapshot is smaller by exactly the state that was forgotten. The damage only appears on the
 * restore, and it appears twice:
 *
 * - an entity that survived the rewind keeps its unregistered components **at their post-rewind
 *   values**, so a world claiming to be at tick 121 holds tick 421's health;
 * - an entity that was destroyed and is rebuilt by the restore comes back carrying **only** the
 *   registered components, so a unit resurrects as a bare position with no combat state, no
 *   attributes and nothing to draw.
 *
 * That is exactly what a play agent measured on `moba`: 22 units before a rewind and 27 after,
 * five of them shells, with health and in-flight ability activations frozen at the tick the
 * rewind was supposed to undo. The spine was working correctly. It had simply never been told
 * that six of the eight components on a unit existed.
 *
 * ## Why an explicit audit and not a check inside capture
 *
 * Answering the question means going from the component instances Fleks holds back to the
 * registry, and the only API that enumerates an entity's components is `World.snapshotOf`, which
 * allocates a list per entity. Capture runs on the path
 * [SnapshotBudgets.CAPTURE_ALLOCATED_BYTES] gates at zero bytes, so this cannot live there and
 * must not be tempted into living there later. It is a boot-time and test-time call: run it once
 * over a world that has been dressed with everything a game spawns, and the answer holds for
 * every capture that world will ever take.
 *
 * A game wires it where it stands a scene up. `moba` pins it in a test over the real level, which
 * is the only place that spawns the whole roster.
 */
public object SnapshotCoverage {

    /**
     * Every component class on a live entity of [world] that [registry] does not capture.
     *
     * Only entities the [netIds] index knows about are examined, because those are exactly the
     * entities capture walks: an entity with no [dev.wildware.udea.core.identity.NetId] is not in
     * a snapshot at all, and reporting its components as "uncovered" would be reporting the wrong
     * defect.
     *
     * @return the fully-qualified names of the uncovered classes, sorted, so the result is stable
     *   across runs and can be asserted against verbatim. Empty means every component on every
     *   live entity round-trips through a snapshot.
     */
    public fun uncovered(
        registry: ComponentRegistry,
        world: World,
        netIds: NetIdIndex,
    ): List<String> {
        val missing = LinkedHashSet<String>()
        netIds.forEachLive { _, entity ->
            for (component in world.snapshotOf(entity).components) {
                if (registry.covers(component::class)) continue
                missing += component::class.qualifiedName ?: component::class.toString()
            }
        }
        return missing.sorted()
    }

    /**
     * Throws unless [world] holds nothing [registry] cannot capture.
     *
     * The message names every uncovered type, because the remedy is per type: give it a
     * `Replicator`, a `ComponentSchema` and a line in the game's registry, or decide it is
     * presentation state that a restore is allowed to rebuild from scratch.
     */
    public fun require(
        registry: ComponentRegistry,
        world: World,
        netIds: NetIdIndex,
    ) {
        val missing = uncovered(registry, world, netIds)
        check(missing.isEmpty()) {
            "${missing.size} component type(s) on live entities are outside the snapshot " +
                "registry, so a rewind will not restore them: ${missing.joinToString()}"
        }
    }
}
