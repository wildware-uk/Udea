package com.example.newgame.sim

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType

/**
 * A thing that drives across the ground: where it is, and how fast.
 *
 * A Fleks component, which is all a piece of simulation state has to be. Adding `@Replicated`
 * from `dev.wildware.udea.annotations` is what makes it travel to a client and into a snapshot,
 * and that is a step with a wire contract attached - see `docs/new-game.md`.
 */
public class Rover(
    /** Metres east of the origin. */
    public var x: Float = 0f,
    /** Metres north of the origin. */
    public var y: Float = 0f,
    /** Metres per second, east. */
    public var speed: Float = 1f,
) : Component<Rover> {

    override fun type(): ComponentType<Rover> = Rover

    override fun toString(): String = "Rover(x=$x, y=$y, speed=$speed)"

    /** Fleks' handle for this component. */
    public companion object : ComponentType<Rover>()
}
