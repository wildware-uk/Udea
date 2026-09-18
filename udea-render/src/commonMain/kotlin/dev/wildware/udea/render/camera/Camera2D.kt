package dev.wildware.udea.render.camera

import dev.wildware.udea.render.draw.Projection2D
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An orthographic 2D camera: where it looks, and how far it is zoomed out.
 *
 * ## Why Udea has its own
 *
 * Under LibGDX this was `OrthographicCamera`, whose one output a 2D renderer used was the
 * `combined` matrix handed to `Batch.projectionMatrix`. Kool's cameras are scene-graph nodes that
 * render a whole pass, and a pass here is shared by every renderer drawing into it - each of which
 * can want its own projection (the world through the camera, a background in pixels). So the
 * camera is plain arithmetic in common code, and what it produces is a [Projection2D] from world
 * units to target pixels that a renderer hands to `SpriteBatch2D.begin`. Arithmetic like this is
 * checkable without a render context, which is the point.
 */
public class Camera2D {

    /** The world point at the centre of the view. */
    public val position: Position = Position()

    /** Larger shows more world. `1` shows exactly [ExtendViewport.worldWidth] units across. */
    public var zoom: Float = 1f

    /** A mutable world point; the camera's own, so moving it allocates nothing. */
    public class Position(public var x: Float = 0f, public var y: Float = 0f) {
        override fun toString(): String = "($x, $y)"
    }

    override fun toString(): String = "Camera2D(at $position, zoom $zoom)"
}

/**
 * LibGDX's `ExtendViewport` fit rule, which is Udea's: keep at least [minWorldWidth] by
 * [minWorldHeight] world units visible, and let a window of a different aspect ratio show **more**
 * world along the longer axis rather than stretching or letterboxing it.
 *
 * The arithmetic is LibGDX 1.13's `ExtendViewport.update` with no maximum world size, including
 * its rounding of the viewport's pixel size, so a picture framed on the old engine frames the same
 * here.
 */
public class ExtendViewport(
    /** World units always visible across. */
    public val minWorldWidth: Float,
    /** World units always visible vertically. */
    public val minWorldHeight: Float,
) {

    init {
        require(minWorldWidth > 0f && minWorldHeight > 0f) {
            "a viewport must show some world, was ${minWorldWidth}x$minWorldHeight"
        }
    }

    /** World units across at zoom 1, after [update] extended the shorter fit. */
    public var worldWidth: Float = minWorldWidth
        private set

    /** World units vertically at zoom 1, after [update]. */
    public var worldHeight: Float = minWorldHeight
        private set

    /** Left edge of the drawn area, in target pixels. */
    public var screenX: Int = 0
        private set

    /** Bottom edge of the drawn area, in target pixels. */
    public var screenY: Int = 0
        private set

    /** Width of the drawn area, in target pixels. */
    public var screenWidth: Int = 0
        private set

    /** Height of the drawn area, in target pixels. */
    public var screenHeight: Int = 0
        private set

    /** Fits the viewport to a target of [width] x [height] pixels. */
    public fun update(width: Int, height: Int) {
        require(width > 0 && height > 0) { "a viewport cannot fit a ${width}x$height target" }
        var worldW = minWorldWidth
        var worldH = minWorldHeight
        // Scaling.fit: the largest uniform scale that fits the minimum world in the target.
        val scale = min(width / worldW, height / worldH)
        var viewportW = (worldW * scale).roundToInt()
        var viewportH = (worldH * scale).roundToInt()
        if (viewportW < width) {
            val toViewportSpace = viewportH / worldH
            val lengthen = (width - viewportW) * (worldH / viewportH)
            worldW += lengthen
            viewportW += (lengthen * toViewportSpace).roundToInt()
        } else if (viewportH < height) {
            val toViewportSpace = viewportW / worldW
            val lengthen = (height - viewportH) * (worldW / viewportW)
            worldH += lengthen
            viewportH += (lengthen * toViewportSpace).roundToInt()
        }
        worldWidth = worldW
        worldHeight = worldH
        screenWidth = viewportW
        screenHeight = viewportH
        screenX = (width - viewportW) / 2
        screenY = (height - viewportH) / 2
    }

    /**
     * Writes into [into] the projection from world units, seen through [camera], to target pixels
     * with the origin at the bottom left.
     */
    public fun project(camera: Camera2D, into: Projection2D) {
        val visibleW = worldWidth * camera.zoom
        val visibleH = worldHeight * camera.zoom
        into.scaleX = screenWidth / visibleW
        into.scaleY = screenHeight / visibleH
        into.offsetX = screenX - (camera.position.x - visibleW / 2f) * into.scaleX
        into.offsetY = screenY - (camera.position.y - visibleH / 2f) * into.scaleY
    }

    override fun toString(): String =
        "ExtendViewport(world ${worldWidth}x$worldHeight in ${screenWidth}x$screenHeight at ($screenX, $screenY))"
}
