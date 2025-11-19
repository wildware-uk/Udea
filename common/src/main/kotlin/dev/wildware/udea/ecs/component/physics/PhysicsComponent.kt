package dev.wildware.udea.ecs.component.physics

import com.badlogic.gdx.physics.box2d.Body
import com.github.quillraven.fleks.Entity

interface PhysicsComponent {
    /**
     * Whether the fixture is a sensor or not.
     * */
    val isSensor: Boolean

    fun registerComponent(entity: Entity, body: Body)
}
