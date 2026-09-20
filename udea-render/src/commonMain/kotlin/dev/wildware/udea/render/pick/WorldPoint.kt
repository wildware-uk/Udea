package dev.wildware.udea.render.pick

/**
 * A point in the world, in world units, Z up: what a pixel un-projects to (issue #262).
 *
 * Mutable and reused, like `ViewPoint` and `ViewRay` - a pointer is read every frame, and a fresh
 * three-float object 60 times a second is garbage the collector deals with in the middle of drawing.
 * The caller owns it and hands it to whatever writes into it.
 */
public class WorldPoint(
    public var x: Float = 0f,
    public var y: Float = 0f,
    public var z: Float = 0f,
) {

    /** Puts this point at ([x], [y], [z]). */
    public fun set(x: Float, y: Float, z: Float) {
        this.x = x
        this.y = y
        this.z = z
    }

    override fun toString(): String = "WorldPoint($x, $y, $z)"
}
