package dev.wildware.systems

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType
import com.badlogic.gdx.math.Vector2
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.game
import ktx.graphics.use

class EditorSystem : IteratingSystem(family = family {
    all(Transform)
}) {

    val shapeRenderer by lazy { ShapeRenderer() }

    override fun onTick() {
        shapeRenderer.use(ShapeType.Line, game.camera) {
            super.onTick()
        }
    }

    override fun onTickEntity(entity: Entity) {
        val transform = entity[Transform]
        shapeRenderer.color = Color.WHITE

        val position = transform.position
        drawArrow(position, position.cpy().add(0f, 5f), 5f)
        drawArrow(position, position.cpy().add(5f, 0f), 5f)
    }

    fun drawArrow(from: Vector2, to: Vector2, arrowSize: Float = 5f) {
        val direction = Vector2(to).sub(from).nor()
        val right = Vector2(-direction.y, direction.x).scl(arrowSize)
        val length = from.dst(to)
        val arrowEnd = Vector2(from).add(direction.scl(length))

        shapeRenderer.line(from, arrowEnd)
    }
}
