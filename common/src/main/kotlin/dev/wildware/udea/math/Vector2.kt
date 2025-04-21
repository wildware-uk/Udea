package dev.wildware.udea.math

/**
 * Represents a 2D vector with x and y coordinates.
 * Used for positions, scales, and other 2D mathematical operations.
 */
data class Vector2(
    /** The x-coordinate of the vector */
    var x: Float,
    /** The y-coordinate of the vector */
    var y: Float
) {
    /** Creates a Vector2 with both coordinates set to 0 */
    constructor() : this(0F, 0F)

    /** Creates a Vector2 with both coordinates set to the same value */
    constructor(v: Float) : this(v, v)
}
