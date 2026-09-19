package com.example.newgame.sim

import dev.wildware.udea.core.SimSystem

/**
 * Drives every [Rover] east by its own speed, once per tick.
 *
 * The whole of this game's rules, and the shape every rule here has: read the tick's delta off
 * the clock, write component state, return. It never reads the wall clock and never draws an
 * unseeded random number - `udeaVerifyDeterminism` scans this package and fails the build on
 * either, because a simulation that does one of those cannot rewind, cannot replay and cannot
 * agree with a server.
 */
public class RoverSystem : SimSystem() {

    private val rovers = world.family { all(Rover) }

    /** How many rover updates have been applied, so a caller can see the tick loop working. */
    public var updates: Long = 0L
        private set

    /** Spawns the starting rovers, once, before the first tick. */
    override fun onInit() {
        repeat(ROVERS) { index ->
            world.entity { it += Rover(x = 0f, y = index.toFloat(), speed = 1f + index) }
        }
    }

    override fun onTick() {
        val entities = rovers.entities
        val dt = ctx.clock.dt
        var index = 0
        while (index < entities.size) {
            val rover = entities[index][Rover]
            rover.x += rover.speed * dt
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
    }
}
