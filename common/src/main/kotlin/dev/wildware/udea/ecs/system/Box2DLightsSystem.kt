package dev.wildware.udea.ecs.system

import box2dLight.RayHandler
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import com.github.quillraven.fleks.World.Companion.inject
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.ecs.component.lights.PointLight
import dev.wildware.udea.ecs.component.lights.ConeLight
import dev.wildware.udea.ecs.component.lights.DirectionalLight
import dev.wildware.udea.game
import ktx.assets.disposeSafely

class Box2DLightsSystem(
    val rayHandler: RayHandler = inject()
) : IteratingSystem(
    family { any(PointLight, ConeLight, DirectionalLight) }
) {
    override fun onTick() {
        super.onTick()
        game.camera.let {
            rayHandler.setCombinedMatrix(
                it.combined,
                it.position.x,
                it.position.y,
                it.viewportWidth,
                it.viewportHeight
            )
        }
        rayHandler.updateAndRender()
    }

    override fun onTickEntity(entity: Entity) {
        val lightComponent = when {
            PointLight in entity -> entity[PointLight]
            ConeLight in entity -> entity[ConeLight]
            DirectionalLight in entity -> entity[DirectionalLight]
            else -> error("Unknown light type")
        }

        lightComponent.light.position = entity[Transform].position
    }

    override fun onDispose() {
        rayHandler.disposeSafely()
    }
}
