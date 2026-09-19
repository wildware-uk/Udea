package dev.wildware.hollow

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import kotlinx.serialization.Serializable

/**
 * How the level is lit: the sun, which casts shadows, and the ambient light that keeps a face
 * turned away from it from going black.
 *
 * On an entity of the level, so the light is saved with the clearing and an editor can change it
 * like any other field; `ScenerySystem` copies it into the renderer's light every frame. Plain
 * floats, like every Hollow component: no renderer type reaches the simulation.
 *
 * Colours are linear red, green and blue, each 0 to 1.
 */
@Serializable
public class Sunlight(
    /** The direction sunlight travels: `(0, 0, -1)` shines straight down. Need not be unit length. */
    public var directionX: Float = -0.5f,
    public var directionY: Float = 0.3f,
    public var directionZ: Float = -0.8f,
    public var red: Float = 1f,
    public var green: Float = 1f,
    public var blue: Float = 1f,
    /** Multiplies the sun's colour. */
    public var intensity: Float = 3f,
    public var ambientRed: Float = 0.3f,
    public var ambientGreen: Float = 0.3f,
    public var ambientBlue: Float = 0.35f,
) : Component<Sunlight> {

    override fun type(): ComponentType<Sunlight> = Sunlight

    override fun toString(): String =
        "Sunlight(dir=($directionX, $directionY, $directionZ), ($red, $green, $blue) x $intensity, " +
            "ambient=($ambientRed, $ambientGreen, $ambientBlue))"

    public companion object : ComponentType<Sunlight>()
}
