package dev.wildware.ecs.system

import box2dLight.RayHandler
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.FamilyOnAdd
import com.github.quillraven.fleks.FamilyOnRemove
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import com.github.quillraven.fleks.World.Companion.inject
import dev.wildware.ecs.component.Box2DLight
import dev.wildware.ecs.component.Transform
import dev.wildware.spellcastgame.MainGame
import ktx.assets.disposeSafely

class Box2DLightsSystem(
    val rayHandler: RayHandler = inject()
) : IteratingSystem(
    family { all(Box2DLight) }
), FamilyOnRemove {
    override fun onTick() {
        super.onTick()
        rayHandler.setCombinedMatrix(
            MainGame.camera.combined,
            MainGame.camera.position.x,
            MainGame.camera.position.y,
            MainGame.camera.viewportWidth,
            MainGame.camera.viewportHeight
        )
        rayHandler.updateAndRender()
    }

    override fun onTickEntity(entity: Entity) {
        val box2DLight = entity[Box2DLight]
        box2DLight.pointLight.position = entity[Transform].position
    }

    override fun onRemoveEntity(entity: Entity) {
        val box2DLight = entity[Box2DLight]
        box2DLight.pointLight.remove()
    }

    override fun onDispose() {
        rayHandler.disposeSafely()
    }
}
