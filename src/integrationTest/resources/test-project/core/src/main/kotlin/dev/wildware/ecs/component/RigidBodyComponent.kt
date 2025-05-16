package dev.wildware.ecs.component

import com.badlogic.gdx.physics.box2d.Body
import com.badlogic.gdx.physics.box2d.BodyDef
import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.ecs.system.Box2DSystem
import dev.wildware.math.Vector2
import dev.wildware.network.ComponentDelegate
import dev.wildware.network.NetworkComponent
import dev.wildware.network.Networked
import dev.wildware.network.RawSerializerDelegate
import dev.wildware.network.SerializerDelegate
import kotlinx.serialization.Serializable
import ktx.box2d.FixtureDefinition
import kotlin.reflect.KClass

@Networked
class RigidBodyComponent(
    val bodyDef: BodyDef,
    val fixtureDefinition: FixtureDefinition
) : Component<RigidBodyComponent> {
    lateinit var body: Body

    override fun type() = RigidBodyComponent

    override fun World.onAdd(entity: Entity) {
        body = system<Box2DSystem>().box2DWorld.createBody(bodyDef).apply {
            userData = entity
            createFixture(fixtureDefinition)
        }
    }

    override fun World.onRemove(entity: Entity) {
        system<Box2DSystem>().box2DWorld.destroyBody(body)
    }

    override fun toString(): String {
        return "RigidBodyComponent(linearVelocity=${body.linearVelocity}, angularVelocity=${body.angularVelocity})"
    }

    companion object : ComponentType<RigidBodyComponent>(), NetworkComponent<RigidBodyComponent> {
        override val delegate = RigidBodyComponentDelegate
    }
}

object RigidBodyComponentDelegate : SerializerDelegate<RigidBodyComponent, RigidBodyPacketDelegate> {
    override fun create(component: RigidBodyComponent): RigidBodyPacketDelegate {
        return RigidBodyPacketDelegate(
            component.body.linearVelocity,
            component.body.angularVelocity
        )
    }
}

@Serializable
data class RigidBodyPacketDelegate(
    val linearVelocity: Vector2 = Vector2.Zero,
    val angularVelocity: Float = 0F
) : ComponentDelegate {
    override fun World.applyToEntity(entity: Entity) {
        entity[RigidBodyComponent].apply {
            body.linearVelocity = linearVelocity
            body.angularVelocity = angularVelocity
        }
    }
}
