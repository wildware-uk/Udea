package dev.wildware.systems

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.ecs.component.base.Transform

class EditorSystem : IteratingSystem(family = family {
    all(Transform)
}) {

    val shapeRenderer by lazy { ShapeRenderer() }

    override fun onTick() {
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
        super.onTick()
        shapeRenderer.end()
    }

    override fun onTickEntity(entity: Entity) {
        val transform = entity[Transform]
        shapeRenderer.color = Color.WHITE
        shapeRenderer.circle(transform.position.x, transform.position.y, 5f)
        shapeRenderer.circle(0F, 0F, 10F)
    }
}
