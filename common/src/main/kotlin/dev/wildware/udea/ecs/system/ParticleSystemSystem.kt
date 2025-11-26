package dev.wildware.udea.ecs.system
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.github.quillraven.fleks.*
import com.github.quillraven.fleks.World.Companion.family
import com.github.quillraven.fleks.World.Companion.inject
import dev.wildware.udea.ecs.UdeaSystem
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Editor
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Game
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.ecs.component.render.ParticleEffect
import dev.wildware.udea.gameScreen
import dev.wildware.udea.use

@UdeaSystem(runIn = [Editor, Game])
class ParticleSystemSystem(
    val spriteBatch: SpriteBatch = inject()
) : IteratingSystem(
    family = family { all(ParticleEffect, Transform) }
) {
    override fun onTick() {
        spriteBatch.use(gameScreen.camera) {
            super.onTick()
        }
    }

    override fun onTickEntity(entity: Entity) {
        val transform = entity[Transform]
        val particles = entity[ParticleEffect]

        particles.particleEffects.forEach {
            it.setPosition(transform.position.x, transform.position.y)
            it.draw(spriteBatch, gameScreen.delta)
        }
    }
}
