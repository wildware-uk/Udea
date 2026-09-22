package com.example.newgame.sim

import dev.wildware.udea.assets.AssetRegistry
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.spatial.Drawn
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.generated.GameAssets

/**
 * Drives every [Rover] east by its own speed, once per tick, round a field of a fixed width.
 *
 * The whole of this game's rules, and the shape every rule here has: read the tick's delta off
 * the clock, write component state, return. It never reads the wall clock and never draws an
 * unseeded random number - `udeaVerifyDeterminism` scans this package and fails the build on
 * either, because a simulation that does one of those cannot rewind, cannot replay and cannot
 * agree with a server.
 *
 * ## What a rover looks like is said here too
 *
 * Each rover also carries a `Drawn`, naming the model it is drawn with, and a `Transform3D`, where
 * it stands. Both are the engine's components and both are plain data: a window reads them and
 * draws, and a dedicated server carries them and draws nothing. So "which model is this" is a
 * fact the simulation owns, which is what lets a client, a snapshot and a saved level agree about
 * it. The renderer is in `NewGameScene`, outside this package.
 *
 * @param assets the packed asset graph: where `GameAssets.models.rover` is resolved to the slot a
 *   `Drawn` carries.
 */
public class RoverSystem(private val assets: AssetRegistry) : SimSystem() {

    private val rovers = world.family { all(Rover) }

    /** How many rover updates have been applied, so a caller can see the tick loop working. */
    public var updates: Long = 0L
        private set

    /** Spawns the starting rovers, once, before the first tick. */
    override fun onInit() {
        repeat(ROVERS) { index ->
            val rover = Rover(x = 0f, y = index * LANE_SPACING, speed = 1f + index)
            world.entity {
                it += rover
                it += Transform3D(x = rover.x, y = rover.y)
                it += Drawn(GameAssets.models.rover, assets)
            }
        }
    }

    override fun onTick() {
        val entities = rovers.entities
        val dt = ctx.clock.dt
        var index = 0
        while (index < entities.size) {
            val entity = entities[index]
            val rover = entity[Rover]
            rover.x += rover.speed * dt
            if (rover.x > FIELD_HALF_WIDTH) rover.x -= 2f * FIELD_HALF_WIDTH
            val at = entity[Transform3D]
            at.x = rover.x
            at.y = rover.y
            index++
            updates++
        }
    }

    /** Where the rovers are, for a launcher that wants to print something true. */
    public fun positions(): List<Float> {
        val entities = rovers.entities
        val found = ArrayList<Float>(entities.size)
        var index = 0
        while (index < entities.size) {
            found += entities[index][Rover].x
            index++
        }
        return found
    }

    public companion object {
        /** How many rovers a fresh world starts with. */
        public const val ROVERS: Int = 3

        /** Metres from the middle of the field to its east and west edges. */
        public const val FIELD_HALF_WIDTH: Float = 6f

        /** Metres between one rover's lane and the next, north. */
        public const val LANE_SPACING: Float = 1.5f
    }
}
