package dev.wildware.udea.render.model

import dev.wildware.udea.render.draw.Rgba

/**
 * Where the 3D world is seen from: a perspective camera, in world units, Z up.
 *
 * Mutable and read by [ModelRenderSystem] every frame, so moving the camera is writing a field.
 * The 3D counterpart of `CameraRig`: handed to the system, not put on an entity.
 */
public class ModelCamera(
    public var eyeX: Float = 0f,
    public var eyeY: Float = -10f,
    public var eyeZ: Float = 5f,
    public var targetX: Float = 0f,
    public var targetY: Float = 0f,
    public var targetZ: Float = 0f,
    /** Vertical field of view, in degrees. */
    public var fovYDegrees: Float = 45f,
    /** Nearest distance drawn, in world units. */
    public var near: Float = 0.1f,
    /** Farthest distance drawn, in world units. */
    public var far: Float = 100f,
) {

    /** Puts the eye at `(x, y, z)`, looking at `(targetX, targetY, targetZ)`. */
    public fun lookAt(x: Float, y: Float, z: Float, targetX: Float, targetY: Float, targetZ: Float) {
        eyeX = x
        eyeY = y
        eyeZ = z
        this.targetX = targetX
        this.targetY = targetY
        this.targetZ = targetZ
    }

    override fun toString(): String =
        "ModelCamera(($eyeX, $eyeY, $eyeZ) -> ($targetX, $targetY, $targetZ), fov=$fovYDegrees)"
}

/**
 * How the 3D world is lit: one directional light that casts shadows, and a uniform ambient light.
 *
 * Mutable and read by [ModelRenderSystem] every frame.
 */
public class ModelLight(
    /** The direction the light travels, like sunlight: `(0, 0, -1)` shines straight down. */
    public var directionX: Float = -0.5f,
    public var directionY: Float = 0.6f,
    public var directionZ: Float = -1f,
    /** The light's colour. */
    public var color: Rgba = Rgba.WHITE,
    /** Multiplies [color]: how strong the light is. */
    public var intensity: Float = 3f,
    /**
     * Light that reaches every surface from everywhere, so a face turned away from the directional
     * light is dim rather than black.
     */
    public var ambient: Rgba = Rgba.of(0.25f, 0.25f, 0.3f),
    /**
     * How far from the camera, in world units, shadows are drawn. Nearer is sharper: the shadow
     * map's texels are spread over less of the world.
     */
    public var shadowDistance: Float = 25f,
) {

    init {
        require(shadowDistance > 0f) { "shadowDistance must be positive, was $shadowDistance" }
    }

    override fun toString(): String =
        "ModelLight(dir=($directionX, $directionY, $directionZ), $color x $intensity, ambient=$ambient)"
}
