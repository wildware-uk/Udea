package dev.wildware.udea.ecs.component.lights

import com.badlogic.gdx.graphics.Color
import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType

/**
 * Represents a cone light source in the game world.
 * This component defines how light is emitted from a point source with specific properties.
 */
class ConeLight(
    /** The color of the light being emitted, defaults to white */
    val colour: Color = Color.WHITE,
    /** The distance the light travels from its source point in world units */
    val distance: Float = 10f,
    /** The angle of the light cone in degrees, defaults to 45 degrees */
    val coneDegrees: Float = 45F,
    /** The number of light rays used for the light calculation, higher values create smoother lighting */
    val rays: Int = 128,
) : Component<ConeLight>, LightComponent {
    override lateinit var light: box2dLight.ConeLight

    override fun type() = ConeLight

    companion object : ComponentType<ConeLight>()
}
