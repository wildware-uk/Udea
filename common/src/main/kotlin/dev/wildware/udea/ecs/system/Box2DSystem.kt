package dev.wildware.udea.ecs.system

import com.badlogic.gdx.physics.box2d.*
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.FamilyOnAdd
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import com.github.quillraven.fleks.World.Companion.inject
import dev.wildware.udea.ecs.UdeaSystem
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Editor
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Game
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.ecs.component.physics.Body
import dev.wildware.udea.ecs.component.physics.Box
import dev.wildware.udea.ecs.component.physics.Capsule
import dev.wildware.udea.ecs.component.physics.Circle
import dev.wildware.udea.game
import com.badlogic.gdx.physics.box2d.World as Box2DWorld

@UdeaSystem(runIn = [Editor, Game])
class Box2DSystem(
    val box2DWorld: Box2DWorld = inject()
) : IteratingSystem(family { all(Body, Transform).any(Box, Capsule, Circle) }), FamilyOnAdd {
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

    override fun onAddEntity(entity: Entity) {
        val body = entity[Body].body

        when {
            Box in entity -> entity[Box].registerComponent(body)
            Circle in entity -> entity[Circle].registerComponent(body)
            Capsule in entity -> entity[Capsule].registerComponent(body)
            else -> error("No physics component found for entity $entity")
        }
    }

    override fun onTick() {
        if (!game.isEditor) {
            family.forEach {
                val transform = it[Transform]
                val rigidBody = it[Body]

                rigidBody.body.setTransform(transform.position, transform.rotation)
            }

            box2DWorld.step(1 / 60F, 2, 2)
        }

        if (game.debug) {
            box2dDebugRenderer.render(box2DWorld, game.camera.combined)
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
