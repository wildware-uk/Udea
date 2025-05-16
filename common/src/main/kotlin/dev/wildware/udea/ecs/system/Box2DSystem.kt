package dev.wildware.udea.ecs.system

import com.badlogic.gdx.physics.box2d.Box2DDebugRenderer
import com.badlogic.gdx.physics.box2d.Contact
import com.badlogic.gdx.physics.box2d.ContactImpulse
import com.badlogic.gdx.physics.box2d.ContactListener
import com.badlogic.gdx.physics.box2d.Manifold
import com.badlogic.gdx.physics.box2d.World as Box2DWorld
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import com.github.quillraven.fleks.World.Companion.inject
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.ecs.physics.Body
import dev.wildware.udea.game


class Box2DSystem(
    val box2DWorld: Box2DWorld = inject()
) : IteratingSystem(family { all(Body, Transform) }) {
    val box2dDebugRenderer = Box2DDebugRenderer()

    private val onCollideListeners = mutableListOf<(Entity, Entity) -> Unit>()

    init {
        box2DWorld.setContactListener(object : ContactListener {
            override fun beginContact(contact: Contact) {
                val bodyA = contact.fixtureA.body.userData
                val bodyB = contact.fixtureB.body.userData

                if (bodyA is Entity && bodyB is Entity) {
                    onCollideListeners.forEach {
                        it(bodyB, bodyA)
                        it(bodyA, bodyB)
                    }
                }
            }

            override fun endContact(contact: Contact) {}
            override fun preSolve(contact: Contact, oldManifold: Manifold) {}
            override fun postSolve(contact: Contact, impulse: ContactImpulse) {}
        })
    }

    override fun onTick() {
        family.forEach {
            val transform = it[Transform]
            val rigidBody = it[Body]

            rigidBody.body.setTransform(transform.position, transform.rotation)
        }

        box2DWorld.step(1 / 60F, 2, 2)

        if (game.debug) {
            box2dDebugRenderer.render(box2DWorld, game.camera?.combined)
        }

        super.onTick()
    }

    override fun onTickEntity(entity: Entity) {
        val transform = entity[Transform]
        val rigidBody = entity[Body]

        transform.position.set(rigidBody.body.position)
        transform.rotation = rigidBody.body.angle
    }

    fun onCollide(callback: (Entity, Entity) -> Unit) {
        onCollideListeners += callback
    }
}
