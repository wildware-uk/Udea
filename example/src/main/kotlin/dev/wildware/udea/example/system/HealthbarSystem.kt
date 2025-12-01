package dev.wildware.udea.example.system

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled
import com.badlogic.gdx.math.Vector3
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import com.github.quillraven.fleks.World.Companion.inject
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.ecs.component.ability.Attributes
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.ecs.component.physics.Body
import dev.wildware.udea.example.ability.CharacterAttributeSet
import dev.wildware.udea.example.component.GameUnit
import dev.wildware.udea.example.component.Player
import dev.wildware.udea.example.component.Team
import dev.wildware.udea.gameScreen
import dev.wildware.udea.hasAuthority
import ktx.graphics.use

val HealthBarWidth = 60F

class HealthbarSystem(
    val shapeRenderer: ShapeRenderer = inject()
) : IteratingSystem(
    family { all(Abilities) }
) {
    var playerTeam: Team? = null

    override fun onTick() {
        shapeRenderer.use(Filled, gameScreen.uiViewport.camera) {
            super.onTick()
        }
    }

    override fun onTickEntity(entity: Entity) {
        val transform = entity[Transform]
        val abilities = entity[Abilities]
        val attributes = entity[Attributes]

        if (Player in entity && world.hasAuthority(entity)) {
            playerTeam = entity[Team]
        }

        val attributeSet = attributes.getAttributes<CharacterAttributeSet>()

        if (entity[GameUnit].isDead) return


        val healthBarOffset = entity[Body].body.fixtureList.first().shape.radius * 20F

        val position =
            gameScreen.camera.project(Vector3(transform.position.x, transform.position.y + healthBarOffset, 0f))

        shapeRenderer.setColor(0.3F, 0.0F, 0.0F, 1.0F)
        shapeRenderer.rect(position.x - (HealthBarWidth / 2F), position.y, HealthBarWidth, 10F)

        val healthPercent = attributeSet.health.currentValue / attributeSet.maxHealth.currentValue
        shapeRenderer.color = if (playerTeam == entity[Team]) TeamColour else EnemyColour
        shapeRenderer.rect(position.x - (HealthBarWidth / 2f), position.y, healthPercent * HealthBarWidth, 10F)

        if (attributeSet.maxMana.currentValue > 0F) {
            shapeRenderer.setColor(0.1F, 0.3F, 0.4F, 1.0F)
            shapeRenderer.rect(position.x - (HealthBarWidth / 2F), position.y + 10F, HealthBarWidth, 5F)

            val manaPercent = attributeSet.mana.currentValue / attributeSet.maxMana.currentValue
            shapeRenderer.setColor(0.2F, 0.7F, 0.8F, 1.0F)
            shapeRenderer.rect(position.x - (HealthBarWidth / 2F), position.y + 10F, manaPercent * HealthBarWidth, 5F)
        }
    }

    companion object {
        private val TeamColour = Color(0.3F, .6F, 0.3F, 1.0F)
        private val EnemyColour = Color(0.9F, 0.2F, 0.3F, 1.0F)
    }
}
