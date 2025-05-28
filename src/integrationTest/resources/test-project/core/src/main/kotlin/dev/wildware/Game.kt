package dev.wildware

import box2dLight.RayHandler
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input.Keys
import com.badlogic.gdx.assets.AssetManager
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.Disposable
import com.github.quillraven.fleks.SystemConfiguration
import com.github.quillraven.fleks.configureWorld
import dev.wildware.ecs.system.*
import dev.wildware.spellcastgame.PlayerControllerSystem
import ktx.app.clearScreen
import com.badlogic.gdx.physics.box2d.World as Box2DWorld

typealias Box2DWorld = Box2DWorld

lateinit var game: Game

fun game(worldSource: WorldSource, builder: GameBuilder.() -> Unit): Game {
    val gameBuilder = GameBuilder()
        .apply(builder)
    return Game(gameBuilder, worldSource).also {
        game = it
    }
}

class WorldBuilder {
    internal var preSystems: SystemConfiguration.() -> Unit = {}
    internal var postSystems: SystemConfiguration.() -> Unit = {}

    fun preSystems(preSystems: SystemConfiguration.() -> Unit) {
        this.preSystems = preSystems
    }

    fun postSystems(postSystems: SystemConfiguration.() -> Unit) {
        this.postSystems = postSystems
    }
}

class GameBuilder {
    val worldBuilder = WorldBuilder()
    internal var configureAssets: AssetManager.() -> Unit = {}
    internal var onInit: Game.() -> Unit = {}

    fun world(configureWorld: WorldBuilder.() -> Unit) {
        worldBuilder.apply(configureWorld)
    }

    fun assets(configureAssets: AssetManager.() -> Unit) {
        this.configureAssets = configureAssets
    }

    fun init(onInit: Game.() -> Unit) {
        this.onInit = onInit
    }
}

sealed interface WorldSource {
    data class Host(
        val tcpPort: Int = DefaultTcpPort,
        val udpPort: Int = DefaultUdpPort
    ) : WorldSource

    data class Connect(
        val host: String,
        val tcpPort: Int = DefaultTcpPort,
        val udpPort: Int = DefaultUdpPort,
    ) : WorldSource

    companion object {
        const val DefaultTcpPort = 28855
        const val DefaultUdpPort = 28856
    }
}

class Game(
    val gameBuilder: GameBuilder,
    val worldSource: WorldSource
) : Disposable {
    var debug = false
    var clientId: Int = 0
    var isServer: Boolean = false
        private set
    var gameTicks = 0L
        private set

    var delta: Float = 0F
        private set

    val assetManager = AssetManager()
    val networkServerSystem by lazy { world.system<NetworkServerSystem>() }
    val networkClientSystem by lazy { world.system<NetworkClientSystem>() }

    val box2DWorld by lazy { Box2DWorld(Vector2(0.0F, -9.8F), true) }
    val rayHandler by lazy {
        RayHandler(box2DWorld).apply {
            setShadows(true)
            setAmbientLight(0.1F)
            setBlurNum(3)
        }
    }

    val spriteBatch = SpriteBatch()

    val world by lazy {
        configureWorld(entityCapacity = 2048) {
            injectables {
                add(box2DWorld)
                add(rayHandler)
                add(spriteBatch)
            }

            systems {
                add(NetworkServerSystem())

                gameBuilder.worldBuilder.preSystems(this)
                add(ControllerSystem())
                add(Box2DSystem())
                add(CameraTrackSystem())
                add(AbilitySystem())
                add(AttributeSystem())
                add(SpriteBatchSystem())
                add(PlayerControllerSystem())
                add(DebugDrawSystem())
                add(ParticleSystemSystem())
                add(CleanupSystem())
                gameBuilder.worldBuilder.postSystems(this)

                add(Box2DLightsSystem())
                add(NetworkClientSystem())
            }
        }
    }

    fun init() {
        gameBuilder.configureAssets(assetManager)
        assetManager.finishLoading()

        when (worldSource) {
            is WorldSource.Host -> {
                isServer = true
                world -= networkClientSystem
            }

            is WorldSource.Connect -> {
                networkClientSystem.start(worldSource.host, worldSource.tcpPort, worldSource.udpPort)
                world -= networkServerSystem
            }
        }

        gameBuilder.onInit(this)
    }

    fun update(delta: Float) {
        this.delta = delta
        gameTicks++
        clearScreen(red = 0.2f, green = 0.2f, blue = 0.2f)
        world.update(delta)

        if (Gdx.input.isKeyJustPressed(Keys.F1)) {
            this.debug = !debug
        }
    }

    override fun dispose() {
        world.dispose()
        spriteBatch.dispose()
    }
}
