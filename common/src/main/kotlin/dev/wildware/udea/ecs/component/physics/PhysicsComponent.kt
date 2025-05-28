package dev.wildware.udea.ecs.component.physics

import com.badlogic.gdx.physics.box2d.Body

interface PhysicsComponent {
    fun registerComponent(body: Body)
}
