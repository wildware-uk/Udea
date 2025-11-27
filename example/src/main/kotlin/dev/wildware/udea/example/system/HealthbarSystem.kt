package dev.wildware.udea.example.system

import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Vector3
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import com.github.quillraven.fleks.World.Companion.inject
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.example.ability.CharacterAttributeSet
import dev.wildware.udea.gameScreen
import ktx.graphics.use

val HealthBarWidth = 60F

class HealthbarSystem(
    val shapeRenderer: ShapeRenderer = inject()
) : IteratingSystem(
    family { all(Abilities) }
) {
    override fun onTick() {
        shapeRenderer.use(Filled) {
            super.onTick()
        }
    }

    override fun onTickEntity(entity: Entity) {
        val transform = entity[Transform]
        val abilities = entity[Abilities]

        val attributes = abilities.attributeSet as? CharacterAttributeSet ?: return

        if(attributes.health.currentValue <= 0F) return

        val position = gameScreen.camera.project(Vector3(transform.position, 0f))

        shapeRenderer.setColor(0.6F, 0.0F, 0.0F, 1.0F)
        shapeRenderer.rect(position.x - (HealthBarWidth / 2F), position.y + 25F, HealthBarWidth, 10F)

        val healthPercent = attributes.health.currentValue / attributes.maxHealth.currentValue
        shapeRenderer.setColor(0.3F, .6F, 0.3F, 1.0F)
        shapeRenderer.rect(position.x - (HealthBarWidth / 2f), position.y + 25F, healthPercent * HealthBarWidth, 10F)
    }
}
