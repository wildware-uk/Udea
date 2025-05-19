package dev.wildware.udea

import box2dLight.RayHandler
import com.badlogic.gdx.assets.AssetManager
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.assets.Level
import dev.wildware.udea.ecs.system.NetworkClientSystem
import dev.wildware.udea.ecs.system.NetworkServerSystem
import ktx.app.KtxGame
import ktx.app.KtxScreen
import ktx.app.clearScreen
import kotlin.reflect.full.createInstance
import com.badlogic.gdx.physics.box2d.World as Box2DWorld

lateinit var game: Game

class Game(
    val level: Level
) : KtxScreen {
    val assetManager = AssetManager()
    var debug: Boolean = true
    var camera: Camera = OrthographicCamera(19.2F, 10.8F)
    var delta: Float = 0F
    var networkServerSystem: NetworkServerSystem? = null
    var networkClientSystem: NetworkClientSystem? = null
    val isServer: Boolean = false
    var localPlayer: Entity? = null
    var clientId: Int = -1

    val box2DWorld by lazy { Box2DWorld(Vector2(0.0F, -9.8F), true) }

    val rayHandler by lazy {
        RayHandler(box2DWorld).apply {
            setShadows(true)
            setAmbientLight(0.1F)
            setBlurNum(3)
        }
    }

    val spriteBatch by lazy { SpriteBatch() }

    val world = configureWorld {
        injectables {
            add(box2DWorld)
            add(rayHandler)
            add(spriteBatch)
        }

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
        clearScreen(0.1F, 0.1F, 0.1F, 1F)
        this.delta = delta
        world.update(delta)
    }
}

class UdeaGameManager : KtxGame<KtxScreen>() {

    var onCreate: (() -> Unit)? = null

//    private val defaultLevel = Assets.get<Level>(gameConfig.defaultLevel)

    fun onCreate(block: () -> Unit) {
        onCreate = block
    }

    fun setLevel(level: Level) {
        screens.clear()
        addScreen(Game(level))
        setScreen<Game>()
    }

    override fun create() {
        super.create()
        onCreate?.invoke()
    }

    override fun dispose() {}
    override fun pause() {}
    override fun resize(width: Int, height: Int) {}
    override fun resume() {}
}
