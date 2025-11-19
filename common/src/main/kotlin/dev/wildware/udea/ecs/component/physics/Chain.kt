package dev.wildware.udea.ecs.component.physics

import com.badlogic.gdx.physics.box2d.Body
import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import dev.wildware.udea.assets.dsl.ListBuilder
import dev.wildware.udea.dsl.CreateDsl
import dev.wildware.udea.ecs.component.ComponentDependency.Companion.dependencies
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.ecs.component.physics.vertex
import ktx.box2d.chain
import ktx.box2d.loop

/**
 * Represents a chain collision shape.
 * */
data class Chain(
    val vertices: List<Vertex>,
    val friction: Float = 0.0F,
    val loop: Boolean = true,
    override val isSensor: Boolean = false,

    ) : PhysicsComponent, Component<Chain> {
    override fun registerComponent(entity: Entity, body: Body) {
        if (loop) {
            body.loop(vertices.flatMap { listOf(it.x, it.y) }.toFloatArray()) {
                userData = entity
            }
        } else {
            body.chain(vertices.flatMap { listOf(it.x, it.y) }.toFloatArray()) {
                userData = entity
            }
        }.apply {
            friction = this@Chain.friction
            density = 1.0F
            isSensor = this@Chain.isSensor
        }
    }

    override fun type() = Chain

    companion object : UdeaComponentType<Chain>(
        dependsOn = dependencies(Body)
    )
}

@CreateDsl(onlyList = true)
data class Vertex(val x: Float, val y: Float)

/**
 * Creates a box chain shape.
 * */
fun ListBuilder<in Vertex>.box(
    width: Float,
    height: Float,
) {
    val x = width / 2F
    val y = height / 2F

    vertex(-x, y + height)
    vertex(x + width, y + height)
    vertex(x + width, 0F)
    vertex(0F, 0F)
}
