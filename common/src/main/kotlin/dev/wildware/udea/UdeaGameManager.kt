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
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.viewport.ExtendViewport
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.SystemConfiguration
import com.github.quillraven.fleks.configureWorld
import com.kotcrab.vis.ui.VisUI
import dev.wildware.udea.assets.Asset
import dev.wildware.udea.assets.AssetBundle
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.assets.GameConfig
import dev.wildware.udea.assets.Level
import dev.wildware.udea.assets.dsl.script.evalScript
import dev.wildware.udea.command.Commands
import dev.wildware.udea.command.Console
import dev.wildware.udea.ecs.UdeaSystem
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Editor
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Game
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.ecs.system.*
import dev.wildware.udea.ecs.system.DebugDrawSystem
import dev.wildware.udea.screen.LoadingScreen
import dev.wildware.udea.screen.UIScreen
import ktx.app.KtxGame
import ktx.app.KtxScreen
import ktx.app.clearScreen
import ktx.assets.toInternalFile
import ktx.scene2d.Scene2DSkin
import java.io.File
import kotlin.Boolean
import kotlin.Exception
import kotlin.Float
import kotlin.Int
import kotlin.String
import kotlin.Unit
import kotlin.also
import kotlin.apply
import kotlin.arrayOf
import kotlin.collections.plusAssign
import kotlin.error
import kotlin.getValue
import kotlin.isInitialized
import kotlin.lazy
import kotlin.let
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.findAnnotation
import kotlin.require
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.stackTraceToString
import kotlin.with
import com.badlogic.gdx.physics.box2d.World as Box2DWorld

lateinit var gameScreen: GameScreen
lateinit var gameManager: UdeaGameManager

class GameScreen(
    val gameManager: UdeaGameManager = dev.wildware.udea.gameManager,
    val isEditor: Boolean = false,
    val level: Level,
    val extraSystems: List<KClass<out IntervalSystem>> = emptyList(),
) : KtxScreen {
    var started = false
    var debug: Boolean = false
    val viewport = ExtendViewport(19.20F, 10.80F)
    var camera = viewport.camera as OrthographicCamera
    var delta: Float = 0F
    var networkServerSystem: NetworkServerSystem? = null
    var networkClientSystem: NetworkClientSystem? = null
    var isServer: Boolean = true
    var localPlayer: Entity? = null
    var clientId: Int = -1
    private var fpsPrintTimer = 0F

    var time: Float = 0F

    val console by lazy {
        Console().apply {
            onCommand {
                println(it)

                try {
                    Commands.execute(it)
                } catch (e: Exception) {
                    println("Error executing command: ${e.message}")
                }
            }
        }
    }

    val stage by lazy {
        Stage(ScreenViewport()).apply {
            addActor(console)
            console.setSize(600F, 300F)
            console.setPosition(20F, 20F)
        }
    }

    val box2DWorld by lazy { Box2DWorld(gameConfig.physics.gravity, true) }

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

    val gameConfig = Assets.filterIsInstance<GameConfig>().firstOrNull() ?: GameConfig()

    val spriteBatch by lazy { SpriteBatch() }
    val shapeRenderer by lazy { ShapeRenderer() }

    var displayConsole = false

    val world by lazy {
        configureWorld {
            injectables {
                add(box2DWorld)
                add(rayHandler)
                add(spriteBatch)
                add(shapeRenderer)
            }

            systems {
                extraSystems.forEach {
                    addSystem(it)
                }

                level.systems.forEach {
                    addSystem(it)
                }
            }
        }
    }

    init {
        gameScreen = this

        gameConfig?.scene2d?.scene2DDefaultSkin?.let {
            Scene2DSkin.defaultSkin = Skin(it.toInternalFile())
        }

        stage.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                if (event?.target !is TextField) {
                    stage.keyboardFocus = null
                }
            }
        })

        gameManager.inputProcessor.addProcessor(stage)

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

                        if (entityDef.position != null) configure {
                            it[Transform].position.set(entityDef.position)
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
        fpsPrintTimer += delta
        if (fpsPrintTimer > 1.0F) {
            fpsPrintTimer = 0F
            println("FPS: ${Gdx.graphics.framesPerSecond}")
        }

        clearScreen(0.1F, 0.1F, 0.1F, 1F)
        this.delta = delta
        world.update(delta)

        stage.act(delta)
        stage.draw()

        if (Gdx.input.isKeyJustPressed(Input.Keys.F1)) {
            this.debug = !debug
        }

        if (Gdx.input.isKeyJustPressed(Input.Keys.GRAVE)) {
            this.displayConsole = !this.displayConsole
            console.isVisible = displayConsole
        }
    }

    fun initializeWorldSource(worldSource: WorldSource) {
        when (worldSource) {
            is WorldSource.Host -> {
                networkServerSystem = NetworkServerSystem(world).also {
                    world += it
                }
                isServer = true
            }

            is WorldSource.Connect -> {
                networkClientSystem = NetworkClientSystem(world).also {
                    world += it
                    it.start(worldSource.host, worldSource.tcpPort, worldSource.udpPort)
                }
                isServer = false
            }
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
    val udeaGame: UdeaGame,
    assetLoaderFun: () -> AssetLoader = ::GameAssetLoader,
    val isEditor: Boolean = false,
    val fileHandleResolver: FileHandleResolver = InternalFileHandleResolver(),
) : KtxGame<KtxScreen>() {

    val inputProcessor = InputMultiplexer()
    val assetLoader by lazy { assetLoaderFun() }
    val assetManager by lazy { AssetManager(fileHandleResolver) }

    var onCreate: (() -> Unit)? = null

    init {
        gameManager = this
    }

    /**
     * Displays the given level in the game.
     * */
    fun setLevel(level: Level) {
        screens.clear()
        addScreen(
            GameScreen(
                isEditor = isEditor,
                level = level,
                extraSystems = DefaultSystems
            )
        )
        setScreen<GameScreen>()
    }

    /**
     * Displays the given UI in the game.
     * */
    inline fun <reified T : UIScreen> showUI(uiScreen: T) {
        addScreen(uiScreen)
        setScreen<T>()
    }

    val loadingScreen by lazy { LoadingScreen(AssetLoaderTask(assetLoader, assetManager), udeaGame) }

    override fun create() {
        assetManager.setLoader(ParticleEffect::class.java, ParticleEffectLoader(fileHandleResolver))

        Gdx.input.inputProcessor = inputProcessor
        VisUI.load()

        addScreen(loadingScreen)
        setScreen<LoadingScreen>()

//        if (!isEditor) {
//            val gameConfig = Assets.filterIsInstance<GameConfig>().first()
//            gameConfig.defaultLevel
//                ?.let {
//                    setLevel(
//                        it.value, extraSystems = listOf(
//                            CameraTrackSystem::class.java,
//                            BackgroundDrawSystem::class.java,
//                            AnimationSystem::class.java,
//                            Box2DSystem::class.java,
//                            SpriteBatchSystem::class.java,
//                            Box2DLightsSystem::class.java,
//                            AbilitySystem::class.java,
//                            CleanupSystem::class.java,
//                            ControllerSystem::class.java,
//                            ParticleSystemSystem::class.java,
//                            NetworkClientSystem::class.java,
//                            NetworkServerSystem::class.java,
//                        )
//                    )
//                }
//        }

        super.create()
        onCreate?.invoke()

        UdeaReflections.registerProject(udeaGame::class)
        UdeaReflections.init()
    }

    override fun dispose() {
        VisUI.dispose()
    }

    override fun pause() {}
    override fun resize(width: Int, height: Int) {
        if (::gameScreen.isInitialized) {
            gameScreen.resize(width, height)
        }
    }

    override fun resume() {}

    companion object {
        val DefaultSystems = listOf(
            CameraTrackSystem::class,
            BackgroundDrawSystem::class,
            CharacterAnimationControllerSystem::class,
            AnimationSystem::class,
            CharacterControllerSystem::class,
            Box2DSystem::class,
            AnimationSetSystem::class,
            SpriteBatchSystem::class,
            DebugDrawSystem::class,
            Box2DLightsSystem::class,
            AbilitySystem::class,
            AttributeSystem::class,
            CleanupSystem::class,
            ControllerSystem::class,
            ParticleSystemSystem::class,
        )
    }
}

class AssetLoaderTask(
    val assetLoader: AssetLoader,
    val assetManager: AssetManager,
) {
    var progress: Int = 0
        private set

    var total: Int = 0
        private set

    var status: String = ""
        private set

    val finished: Boolean
        get() = progress >= total

    val assets = assetLoader.discoverAssets().also {
        total = it.size
    }

    fun load() {
        val assetToLoad = assets[progress]
        status = "Loading $assetToLoad"
        assetLoader.loadAsset(assetToLoad, assetManager)
        progress++

        if (finished) {
            status = "Done!"
            assetManager.finishLoading()
        }
    }
}

interface AssetLoader {

    /**
     * Scan the assets directory for all assets needed for the game.
     * */
    fun discoverAssets(): List<FileHandle>

    /**
     * Loads all assets needed for the game into the [AssetManager].
     * */
    fun loadAsset(assetFile: FileHandle, manager: AssetManager)
}

// TODO implement awesome networking


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
class GameAssetLoader(
    val baseDir: FileHandle = "assets/".toInternalFile()
) : AssetLoader {
    override fun discoverAssets(): List<FileHandle> {
        val assets = mutableListOf<FileHandle>()

        fun scanDirectory(directory: FileHandle) {
            directory.list().forEach { file ->
                if (file.isDirectory) {
                    scanDirectory(file)
                } else {
                    assets += file
                }
            }
        }

        scanDirectory(baseDir)

        return assets
    }

    override fun loadAsset(assetFile: FileHandle, manager: AssetManager) {
        var loaded = true
        val path = assetFile.path().substringAfter(baseDir.path())
        when (assetFile.extension()) {
            "png" -> manager.load(path, Texture::class.java)
            "kts" -> loadAsset(assetFile).forEach {
                Assets["${it.path}/${it.name}"] = it
            }

            "p" -> manager.load(path, ParticleEffect::class.java)

            else -> loaded = false
        }

        if (loaded) {
            Gdx.app.log("GameAssetLoader", "Loaded asset: $path")
        }
    }

    private fun loadAsset(file: FileHandle): List<Asset> {
        return loadAssets(file.file())
    }
}

fun loadAssets(
    file: File,
    compilationConfiguration: ScriptCompilationConfiguration.Builder.() -> Unit = {},
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
                                    path = file.path.replace("\\", "/")
                                        .substringBeforeLast("/")
                                        .substringAfterLast("assets/")
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