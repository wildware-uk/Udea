package dev.wildware.udea.ecs.component.lights

import com.badlogic.gdx.graphics.Color
import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.ecs.system.Box2DLightsSystem
import box2dLight.PointLight as Box2DPointLight

/**
 * Represents a point light source in the game world.
 * This component defines how light is emitted from a point source with specific properties.
 */
class PointLight(
    /** The color of the light being emitted, defaults to white */
    val colour: Color = Color.WHITE,
    /** The distance the light travels from its source point in world units */
    val distance: Float = 10f,
    /** The number of light rays used for the light calculation, higher values create smoother lighting */
    val rays: Int = 128
) : Component<PointLight>, LightComponent {
    override lateinit var light: Box2DPointLight

    override fun type() = PointLight

    override fun World.onAdd(entity: Entity) {
        light = box2dLight.PointLight(
            system<Box2DLightsSystem>().rayHandler,
            rays,
            colour,
            distance,
            0f, 0f
        )

        light.setSoft(true)
    }

    override fun World.onRemove(entity: Entity) {
        light.remove()
    }

    companion object : ComponentType<PointLight>()
}
