package dev.wildware.udea.ecs.component.lights

import com.badlogic.gdx.graphics.Color
import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import box2dLight.DirectionalLight as Box2DDirectionalLight

/**
 * Represents a directional light source in the game world.
 * A directional light emits light in a specific direction, simulating sunlight or other distant light sources.
 */
class DirectionalLight(
    /** The color of the light being emitted, defaults to white */
    val colour: Color = Color.WHITE,
    /** The direction the light is emitted from, in degrees. */
    val direction: Float = 0F,
    /** The number of light rays used for the light calculation, higher values create smoother lighting */
    val rays: Int = 128
) : Component<DirectionalLight>, LightComponent {
    override lateinit var light: Box2DDirectionalLight

    override fun type() = DirectionalLight

    companion object : ComponentType<DirectionalLight>()
}
