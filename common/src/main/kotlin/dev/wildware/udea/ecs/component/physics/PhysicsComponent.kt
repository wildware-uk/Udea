package dev.wildware.udea.ecs.component.physics

import com.badlogic.gdx.physics.box2d.Body

interface PhysicsComponent {
    /**
     * Whether the fixture is a sensor or not.
     * */
    val isSensor: Boolean

    fun registerComponent(body: Body)
}
