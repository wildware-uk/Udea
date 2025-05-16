package dev.wildware.udea

import com.badlogic.gdx.assets.AssetManager
import com.badlogic.gdx.graphics.Camera
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.assets.Level
import dev.wildware.udea.config.gameConfig
import dev.wildware.udea.ecs.system.NetworkClientSystem
import dev.wildware.udea.ecs.system.NetworkServerSystem
import ktx.app.KtxGame
import ktx.app.KtxScreen
import kotlin.reflect.full.createInstance

lateinit var game: Game

class Game(
    val level: Level
) : KtxScreen {
    val assetManager = AssetManager()
    var debug: Boolean = false
    var camera: Camera? = null
    var delta: Float = 0F
    var networkServerSystem: NetworkServerSystem? = null
    var networkClientSystem: NetworkClientSystem? = null
    val isServer: Boolean = false
    var localPlayer: Entity? = null
    var clientId: Int = -1

    val world = configureWorld {
        systems {
            level.systems.forEach {
                Class.forName(it.className).kotlin.createInstance()
            }
        }
    }

    init {
        game = this

        level.entities.forEach {
            world.entity().apply {
                world.loadSnapshotOf(this, it)
            }
        }
    }

    override fun render(delta: Float) {
        this.delta = delta
    }
}

class UdeaGameManager : KtxGame<KtxScreen>() {
    val level = Assets.get<Level>(gameConfig.defaultLevel)

    override fun create() {
        addScreen(Game(level))
        setScreen<Game>()
    }

    override fun dispose() {
    }

    override fun pause() {
    }

    override fun resize(width: Int, height: Int) {
    }

    override fun resume() {
    }
}