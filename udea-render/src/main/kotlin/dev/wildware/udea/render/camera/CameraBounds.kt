package dev.wildware.udea.render.camera

import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.utils.viewport.Viewport

/**
 * The rectangle of the level the camera is not allowed to look outside of.
 *
 * A level is finite and the space around it is not drawn, so a camera that follows a player
 * into a corner otherwise frames half a screen of nothing. Clamping the *camera* rather than
 * the *player* is the version that keeps movement feeling unrestricted.
 */
public class CameraBounds(
    public val minX: Float,
    public val minY: Float,
    public val maxX: Float,
    public val maxY: Float,
) {

    init {
        require(maxX > minX && maxY > minY) {
            "bounds must have positive extent, was ($minX, $minY) to ($maxX, $maxY)"
        }
    }

    /**
     * Moves [camera] the shortest distance that puts its visible area inside these bounds.
     *
     * When the bounds are **narrower than the view** on an axis, the camera is centred on that
     * axis instead of clamped. Clamping a too-wide view would push it to one edge and leave a
     * lopsided margin, and — worse — the two clamps fight: the low edge asks for one position
     * and the high edge for another, so the camera lands wherever the second `coerce` ran.
     * Centring is the only stable answer, and it is the one a player reads as intentional.
     */
    public fun clamp(camera: OrthographicCamera, viewport: Viewport) {
        val halfWidth = viewport.worldWidth * camera.zoom / 2f
        val halfHeight = viewport.worldHeight * camera.zoom / 2f

        camera.position.x = clampAxis(camera.position.x, halfWidth, minX, maxX)
        camera.position.y = clampAxis(camera.position.y, halfHeight, minY, maxY)
    }

    override fun toString(): String = "CameraBounds(($minX, $minY) to ($maxX, $maxY))"

    private companion object {

        fun clampAxis(position: Float, halfExtent: Float, min: Float, max: Float): Float {
            val low = min + halfExtent
            val high = max - halfExtent
            return if (low > high) (min + max) / 2f else position.coerceIn(low, high)
        }
    }
}
