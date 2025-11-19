package dev.wildware.udea

import box2dLight.RayHandler
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.InputProcessor
import com.badlogic.gdx.assets.AssetManager
import com.badlogic.gdx.assets.loaders.FileHandleResolver
import com.badlogic.gdx.assets.loaders.ParticleEffectLoader
import com.badlogic.gdx.assets.loaders.resolvers.InternalFileHandleResolver
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.ParticleEffect
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.viewport.ExtendViewport
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.SystemConfiguration
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.assets.Asset
import dev.wildware.udea.assets.AssetBundle
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.assets.GameConfig
import dev.wildware.udea.assets.Level
import dev.wildware.udea.assets.dsl.script.evalScript
import dev.wildware.udea.ecs.UdeaSystem
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Editor
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Game
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.ecs.system.*
import ktx.app.KtxGame
import ktx.app.KtxScreen
import ktx.app.clearScreen
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.findAnnotation
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import com.badlogic.gdx.physics.box2d.World as Box2DWorld

lateinit var game: Game
lateinit var gameManager: UdeaGameManager

class Game(
    val gameManager: UdeaGameManager,
    val level: Level,
    val extraSystems: List<Class<out IntervalSystem>>,
    val isEditor: Boolean
) : KtxScreen {
    var started = false
    var debug: Boolean = true
    val viewport = ExtendViewport(19.20F, 10.80F)
    var camera = viewport.camera as OrthographicCamera
    var delta: Float = 0F
    var networkServerSystem: NetworkServerSystem? = null
    var networkClientSystem: NetworkClientSystem? = null
    val isServer: Boolean = true
    var localPlayer: Entity? = null
    var clientId: Int = -1
    var time = 0F

    val box2DWorld by lazy { Box2DWorld(Vector2(0.0F, -9.8F), true) }

    val rayHandler by lazy {
        RayHandler(
            box2DWorld,
            Gdx.graphics.width / 16,
            Gdx.graphics.height / 16
        ).apply {
            setShadows(true)
            setAmbientLight(0.7F)
            setBlurNum(0)
            setBlur(false)
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
                extraSystems.forEach {
                    addSystem(it.kotlin)
                }

                level.systems.forEach {
                    addSystem(it)
                }
            }
        }
    }

    init {
        game = this

        camera.zoom = 0.7F

        Gdx.app.postRunnable {
            level.entities.forEach { entityDef ->
                val entity = entityDef.blueprint?.value?.newInstance(world)
                    ?: world.entity()

                entity.apply {
                    with(world) {
                        this@apply.configure {
                            it += entityDef.components()
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

            started = true
        }
    }

    override fun render(delta: Float) {
        if (!started) return

        time += delta
        if (time > 1.0F) {
            time = 0F
            println("FPS: ${Gdx.graphics.framesPerSecond}")
        }

        clearScreen(0.1F, 0.1F, 0.1F, 1F)
        this.delta = delta
        world.update(delta)

        if (Gdx.input.isKeyJustPressed(Input.Keys.F1)) {
            this.debug = !debug
        }
    }

    override fun resize(width: Int, height: Int) {
        println("RESIZE $width $height")
        viewport.update(width, height, false)
    }

    private fun SystemConfiguration.addSystem(kClass: KClass<out IntervalSystem>) {
        val gameRuntime = if (isEditor) Editor else Game
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

    init {
        gameManager = this
        assetManager.setLoader(ParticleEffect::class.java, ParticleEffectLoader(assetLoader))
    }

    fun setLevel(level: Level, extraSystems: List<Class<out IntervalSystem>> = emptyList()) {
        screens.clear()
        addScreen(Game(this, level, DefaultSystems, isEditor))
        setScreen<Game>()
    }

    override fun create() {
        Gdx.input.inputProcessor = inputProcessor
        assetLoader.load(assetManager)
        assetManager.finishLoading()

        if (!isEditor) {
            val gameConfig = Assets.filterIsInstance<GameConfig>().first()
            gameConfig.defaultLevel
                ?.let {
                    setLevel(
                        it.value, extraSystems = listOf(
                            CameraTrackSystem::class.java,
                            BackgroundDrawSystem::class.java,
                            AnimationSystem::class.java,
                            Box2DSystem::class.java,
                            SpriteBatchSystem::class.java,
                            Box2DLightsSystem::class.java,
                            AbilitySystem::class.java,
                            CleanupSystem::class.java,
                            ControllerSystem::class.java,
                            ParticleSystemSystem::class.java,
                            NetworkClientSystem::class.java,
                            NetworkServerSystem::class.java,
                        )
                    )
                }
        }

        super.create()
        onCreate?.invoke()
    }

    override fun dispose() {}
    override fun pause() {}
    override fun resize(width: Int, height: Int) {
        if (::game.isInitialized) {
            game.resize(width, height)
        }
    }

    override fun resume() {}

    companion object {
        val DefaultSystems = listOf(
            CameraTrackSystem::class.java,
            BackgroundDrawSystem::class.java,
            AnimationSystem::class.java,
            Box2DSystem::class.java,
            AnimationSetSystem::class.java,
            SpriteBatchSystem::class.java,
            Box2DLightsSystem::class.java,
            AbilitySystem::class.java,
            AttributeSystem::class.java,
            CleanupSystem::class.java,
            ControllerSystem::class.java,
            ParticleSystemSystem::class.java,
            NetworkClientSystem::class.java,
            NetworkServerSystem::class.java,
        )
    }
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
                        "kts" -> loadAsset(file).forEach {
                            Assets["${it.path}/${it.name}"] = it
                        }
                        "p" -> manager.load(path, ParticleEffect::class.java)

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

    private fun loadAsset(file: FileHandle): List<Asset> {
        return loadAssets(file.file())
    }
}

fun loadAssets(
    file: File,
    compilationConfiguration: ScriptCompilationConfiguration.Builder.()->Unit = {},
    evaluationConfig: ScriptEvaluationConfiguration.Builder.() -> Unit = {},
): List<Asset> {
    val fileName = file.name

    when (val evaluationResult = evalScript(file, compilationConfiguration, evaluationConfig)) {
        is ResultWithDiagnostics.Success -> {
            when (val result = evaluationResult.value.returnValue) {
                is ResultValue.Value -> {
                    when (val asset = result.value) {
                        is Asset -> return listOf(asset.apply {
                            path = file.path.replace("\\", "/")
                                .substringBeforeLast("/")
                                .substringAfterLast("assets/")
                            name = fileName.replace(".udea.kts", "")
                        })

                        is AssetBundle -> {
                            require(asset.assets.all { it.name.isNotBlank() }) {
                                "${fileName}: Assets defined in a bundle must be named!"
                            }

                            return asset.assets.map {
                                it.apply {
                                    path = file.path
                                        .replace("\\", "/")
                                        .substringBeforeLast("/")
                                        .replace("assets/", "")
                                }
                            }
                        }

                        else -> error("${fileName}: udea script evaluated and returned a non Asset object.")
                    }
                }

                is ResultValue.Error -> {
                    error("${fileName}: udea script failed to evaluate:\n${result.error.stackTraceToString()}")
                }

                is ResultValue.Unit -> error("${fileName}: udea script evaluated but returned Unit")
                is ResultValue.NotEvaluated -> error("${fileName}: udea script was not evaluated")
            }
        }

        is ResultWithDiagnostics.Failure -> {
            val stackTrace = evaluationResult.reports.joinToString("\n") { report ->
                "${report.message}\n${report.exception?.stackTraceToString() ?: ""}"
            }
            error("${fileName}: udea script failed to evaluate:\n$stackTrace")
        }
    }
}