package dev.wildware.udea.ecs.system

import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.math.Vector3
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.ecs.UdeaSystem
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Editor
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Game
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.ecs.component.base.Debug
import dev.wildware.udea.ecs.component.base.Networkable
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.ecs.component.physics.Body
import dev.wildware.udea.game
import ktx.assets.toInternalFile
import ktx.graphics.use

@UdeaSystem(runIn = [Editor, Game])
class DebugDrawSystem : IteratingSystem(
    family { all(Transform, Debug) }
) {
    var generator: FreeTypeFontGenerator = FreeTypeFontGenerator("font/LilitaOne-Regular.ttf".toInternalFile())
    val size32Font = generator.generateFont(FreeTypeFontGenerator.FreeTypeFontParameter().apply {
        size = 32
        borderWidth = 1F
    })
    val spriteBatch = SpriteBatch()

    override fun onTick() {
        if (!game.debug) return

        spriteBatch.use {
            super.onTick()
            size32Font.draw(it, if (game.isServer) "server" else "client", 10F, 50F)
        }
    }

    override fun onTickEntity(entity: Entity) {
        val transform = entity[Transform]
        val debug = entity[Debug]

        val position = game.camera!!.project(Vector3(transform.position, 0f))

        if (debug.drawId) {
            val id = entity.id
            size32Font.draw(spriteBatch, "ID: $id", position.x, position.y)
        }

        if (debug.drawOwner) {
            val id = entity[Networkable].owner
            size32Font.draw(spriteBatch, "Owner: $id", position.x, position.y + 50F)
        }

        if (debug.drawRemote && entity.has(Networkable)) {
            val id = entity[Networkable].remoteEntity
            size32Font.draw(spriteBatch, "Remote: $id", position.x, position.y + 100F)
        }

        if (debug.drawStats) {
            val stats = entity[Abilities]
            size32Font.draw(spriteBatch, "Stats: ${stats.attributeSet}", position.x, position.y + 150F)
        }

        if (debug.debugPhysics && entity.has(Body)) {
            val rb = entity[Body]
            size32Font.draw(spriteBatch, "v: ${rb.body.linearVelocity}", position.x, position.y + 150F)
        }
    }
}
