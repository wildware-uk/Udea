package dev.wildware.spellcastgame

import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.ecs.component.AbilitiesComponent
import dev.wildware.ecs.component.Transform
import dev.wildware.spellcastgame.MainGame.Companion.camera
import ktx.graphics.use

class HealthbarRenderSystem : IteratingSystem(
    family = family { all(AbilitiesComponent, Transform) }
) {
    private val shapeRenderer = ShapeRenderer()

    override fun onTick() {
        shapeRenderer.use(ShapeRenderer.ShapeType.Filled, camera) {
            super.onTick()
        }
    }

    override fun onTickEntity(entity: Entity) {
        val abilities = entity[AbilitiesComponent]
        val transform = entity[Transform]
        val attributeSet = abilities.attributeSet

        if (attributeSet is CharacterAttributeSet) {
            val healthPercent = attributeSet.health.currentValue / attributeSet.maxHealth.currentValue

            shapeRenderer.setColor(1f, 0f, 0f, 1f)
            shapeRenderer.rect(transform.position.x - 0.5f, transform.position.y + 0.5f, 1f, 0.2f)

            shapeRenderer.setColor(0f, 1f, 0f, 1f)
            shapeRenderer.rect(transform.position.x - 0.5f, transform.position.y + 0.5f, healthPercent, 0.2f)
        }
    }

    override fun onDispose() {
        shapeRenderer.dispose()
    }
}
