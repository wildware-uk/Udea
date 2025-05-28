package dev.wildware.udea.ecs.system
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.github.quillraven.fleks.*
import com.github.quillraven.fleks.World.Companion.family
import com.github.quillraven.fleks.World.Companion.inject
import dev.wildware.ecs.component.Particles
import dev.wildware.ecs.component.Transform
import dev.wildware.game
import dev.wildware.spellcastgame.MainGame.Companion.camera
import ktx.graphics.use

class ParticleSystemSystem(
    val spriteBatch: SpriteBatch = inject()
) : IteratingSystem(
    family = family { all(Particles, Transform) }
) {
    override fun onTick() {
        spriteBatch.use(camera) {
            super.onTick()
        }
    }

    override fun onTickEntity(entity: Entity) {
        val transform = entity[Transform]
        val particles = entity[Particles]

        particles.particleEffects.forEach {
            it.setPosition(transform.position.x, transform.position.y)
            it.draw(spriteBatch, game.delta)
        }
    }
}
