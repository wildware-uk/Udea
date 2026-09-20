package dev.wildware.udea.nav

import com.github.quillraven.fleks.Entity
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.identity.NetIdVisitor
import dev.wildware.udea.core.spatial.Transform3D

/**
 * Keeps the nav grid agreeing with the buildings that are actually standing.
 *
 * ## A comparison, not an event
 *
 * Every tick it reads the footprint of every [NavObstacle] in the world and compares the list with
 * the one the current grid was **built from**. Different in any way - a building placed, moved,
 * resized or destroyed - and a new grid is built from the new list and swapped in, which drops
 * every cached route with it.
 *
 * The alternative, a flag raised when a building is placed, is what this deliberately is not. A
 * flag is a memory of an event, and a rewind restores the world without restoring the memory: a
 * client that rewound past a building's placement would hold a grid from a future that no longer
 * exists and never notice. Comparing two states cannot go wrong that way - after a restore the
 * obstacles are whatever the snapshot said, they disagree with the grid, and the grid is rebuilt
 * on the spot.
 *
 * ## Order, and why it is `NetId`
 *
 * Footprints are collected in ascending `NetId`, which two processes holding the same entities
 * agree on however they spawned them, where Fleks' own insertion order would not. The order does
 * not change which cells are blocked - blocking is a union - but it *is* the order the comparison
 * and the recorded list use, so a server and a client that collected in different orders would
 * rebuild the grid on alternate ticks for ever.
 *
 * ## Cost
 *
 * The comparison is one pass over the obstacles, and it is all that happens on a tick where
 * nothing was built. A rebuild is a fresh clearance sweep over the whole grid, which is why it
 * happens when the buildings change and not when a unit moves.
 *
 * It runs in `PreSimulation`, so a route asked for anywhere later in the tick is asked of a grid
 * that already matches the world.
 */
public class NavGridSystem(
    private val navigation: Navigation,
    private val netIds: NetIdIndex,
) : SimSystem() {

    /** The footprints found this tick, four ints each. Grown, never shrunk. */
    private var footprints = IntArray(INITIAL_CAPACITY * 4)

    private var count = 0

    private val visitor = object : NetIdVisitor {
        override fun visit(netId: NetId, entity: Entity) {
            collect(entity)
        }
    }

    override fun onTick() {
        count = 0
        netIds.forEachLive(visitor)
        if (matchesCurrentGrid()) return
        val builder = NavGridBuilder(navigation.grid.layout)
        var offset = 0
        while (offset < count) {
            builder.blockCells(
                footprints[offset],
                footprints[offset + 1],
                footprints[offset + 2],
                footprints[offset + 3],
            )
            offset += 4
        }
        navigation.rebuild(builder.build())
    }

    private fun collect(entity: Entity) {
        val obstacle = with(world) { entity.getOrNull(NavObstacle) } ?: return
        val transform = with(world) { entity.getOrNull(Transform3D) } ?: return
        if (count + 4 > footprints.size) footprints = footprints.copyOf(footprints.size * 2)
        navigation.grid.layout.footprintInto(
            footprints,
            count,
            transform.x,
            transform.y,
            obstacle.halfWidth,
            obstacle.halfDepth,
        )
        count += 4
    }

    private fun matchesCurrentGrid(): Boolean {
        val built = navigation.grid.footprints
        if (built.size != count) return false
        for (index in 0 until count) {
            if (built[index] != footprints[index]) return false
        }
        return true
    }

    private companion object {
        /** Buildings a map starts with, before the array has to grow. */
        const val INITIAL_CAPACITY: Int = 64
    }
}
