package dev.wildware.udea

import box2dLight.RayHandler
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.InputProcessor
import com.badlogic.gdx.assets.AssetManager
import com.badlogic.gdx.assets.loaders.FileHandleResolver
import com.badlogic.gdx.assets.loaders.resolvers.InternalFileHandleResolver
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.SystemConfiguration
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.assets.Asset
import dev.wildware.udea.assets.AssetFile
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.assets.Level
import dev.wildware.udea.config.gameConfig
import dev.wildware.udea.ecs.UdeaSystem
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Editor
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Game
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.ecs.system.NetworkClientSystem
import dev.wildware.udea.ecs.system.NetworkServerSystem
import ktx.app.KtxGame
import ktx.app.KtxScreen
import ktx.app.clearScreen
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.findAnnotation
import com.badlogic.gdx.physics.box2d.World as Box2DWorld

lateinit var game: Game

class Game(
    val gameManager: UdeaGameManager,
    val level: Level,
    val extraSystems: List<Class<out IntervalSystem>>,
    val isEditor: Boolean
) : KtxScreen {
    var debug: Boolean = true
    var camera: Camera = OrthographicCamera(19.2F, 10.8F).apply {
        translate(19.2F / 2, 10.8F / 2)
    }
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

    val world by lazy {
        configureWorld {
            injectables {
                add(box2DWorld)
                add(rayHandler)
                add(spriteBatch)
            }

            systems {
                level.systems.forEach {
                    addSystem(it.toKClass())
                }

                extraSystems.forEach {
                    addSystem(it.kotlin)
                }
            }
        }
    }

    init {
        game = this

        Gdx.app.postRunnable {
            level.entities.forEach { entityDef ->
                world.entity().apply {
                    with(world) {
                        this@apply.configure {
                            it += entityDef.components
                            it += entityDef.tags
                        }

                        if (!this@apply.has(Transform)) {
                            configure {
                                it += Transform()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun render(delta: Float) {
        clearScreen(0.1F, 0.1F, 0.1F, 1F)
        this.delta = delta
        world.update(delta)

        if (Gdx.input.isKeyJustPressed(Input.Keys.F1)) {
            this.debug = !debug
        }
    }

    override fun resize(width: Int, height: Int) {
        camera.viewportWidth = width.toFloat() / 10F
        camera.viewportHeight = height.toFloat() / 10F
        camera.update()
    }

    private fun SystemConfiguration.addSystem(kClass: KClass<out IntervalSystem>) {
        val gameRuntime = if(isEditor) Editor else Game
        val runtime = kClass.findAnnotation<UdeaSystem>()?.runIn ?: DefaultRuntime

        if (gameRuntime in runtime) {
            val system = kClass.createInstance() as IntervalSystem

            if (system is InputProcessor) {
                gameManager.inputProcessor.addProcessor(system)
            }

            add(system)
        }
    }

    companion object {
        private val DefaultRuntime = arrayOf(Game)
    }
}

class UdeaGameManager(
    val assetLoader: AssetLoader = GameAssetLoader(),
    val isEditor: Boolean = false,
) : KtxGame<KtxScreen>() {

    val inputProcessor = InputMultiplexer()
    val assetManager = AssetManager(assetLoader)

    var onCreate: (() -> Unit)? = null

    fun setLevel(level: Level, extraSystems: List<Class<out IntervalSystem>> = emptyList()) {
        screens.clear()
        addScreen(Game(this, level, extraSystems, isEditor))
        setScreen<Game>()
    }

    override fun create() {
        Gdx.input.inputProcessor = inputProcessor
        assetLoader.load(assetManager)
        assetManager.finishLoading()

        if(!isEditor) {
            gameConfig.defaultLevel
                ?.let { setLevel(it.value) }
        }

        super.create()
        onCreate?.invoke()
    }

    override fun dispose() {}
    override fun pause() {}
    override fun resize(width: Int, height: Int) {}
    override fun resume() {}
}

interface AssetLoader : FileHandleResolver {
    /**
     * Loads all assets needed for the game into the [AssetManager].
     * */
    fun load(manager: AssetManager)
}

// TODO implement awesome networking

fun initializeWorldSource(worldSource: WorldSource) {
    when (worldSource) {
        is WorldSource.Host -> {
//            world -= networkClientSystem
        }

        is WorldSource.Connect -> {
//            networkClientSystem.start(worldSource.host, worldSource.tcpPort, worldSource.udpPort)
//            world -= networkServerSystem
        }
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

/**
 * Default [GameAssetLoader] implementation.
 * */
class GameAssetLoader : AssetLoader {
    private val internalFileLoader = InternalFileHandleResolver()

    override fun load(manager: AssetManager) {
        fun scanDirectory(directory: FileHandle) {
            directory.list().forEach { file ->
                if (file.isDirectory) {
                    scanDirectory(file)
                } else {
                    var loaded = true

                    val path = file.path().substringAfter("assets/")

                    when (file.extension()) {
                        "png" -> manager.load(path, Texture::class.java)
                        "udea" -> Assets[path] = loadAsset(file)
                        else -> loaded = false
                    }

                    if (loaded) {
                        Gdx.app.log("GameAssetLoader", "Loaded asset: $path")
                    }
                }
            }
        }

        scanDirectory(Gdx.files.internal("assets"))
    }

    override fun resolve(fileName: String): FileHandle {
        return internalFileLoader.resolve(fileName)
    }

    private fun loadAsset(file: FileHandle): Asset {
        val asset = Json.fromJson<AssetFile>(file.read())
        return asset.asset ?: error("Asset file $file does not contain an asset")
    }
}
