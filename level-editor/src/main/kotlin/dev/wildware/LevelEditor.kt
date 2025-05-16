package dev.wildware

import com.badlogic.gdx.backends.lwjgl.LwjglAWTCanvas
import com.github.quillraven.fleks.configureWorld
import dev.wildware.systems.EditorSystem
import dev.wildware.udea.assets.Level
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ktx.app.KtxGame
import ktx.app.KtxScreen
import ktx.app.clearScreen
import java.awt.Canvas
import java.awt.Dimension

class LevelEditorCanvas(
    level: Level
) {
    val editor = Editor(level)
    val awtCanvasLwjgl = LwjglAWTCanvas(editor).apply {
        canvas.preferredSize = Dimension(512, 512)
    }

    fun getCanvas(): Canvas {
        return awtCanvasLwjgl.canvas
    }
}

class Editor(
    level: Level
) : KtxGame<KtxScreen>() {
    val levelEditor: LevelEditorScreen = LevelEditorScreen(level)

    override fun create() {
        if (!screens.containsKey(levelEditor::class.java)) {
            addScreen(levelEditor)
        }

        setScreen<LevelEditorScreen>()
    }
}

class LevelEditorScreen(
    var level: Level,
) : KtxScreen {
    private val _latestSnapshot = MutableStateFlow(level)
    val latestSnapshot = _latestSnapshot.asStateFlow()

    var world = configureWorld(512) {
        systems {
            EditorSystem()

            level.systems.forEach {
//                it.toKClass()createInstance()
            }
        }
    }

    private var playing = false

    init {
        resetWorld()
    }

    override fun render(delta: Float) {
        clearScreen(red = 0.1f, green = 0.1f, blue = 0.1f)

        if (playing) {
            world.update(delta)
        } else {
            world.update(0f)
        }
    }

    fun play() {
        playing = true
//        resetWorld()
    }

    fun stop() {
        playing = false
//        resetWorld()
    }

    fun onEntityAdded() {
        world.entity()
    }

//    fun addToEntity(entityInstance: EntityInstance, component: Component<*>) {
//        entityInstance.snapshot.components
////        with(world) {
////            entity.configure {
////                it += listOf(component)
////            }
////        }
//    }

//    suspend fun save() {
//        level = Level(
//            world.systems.map { it::class },
//            world.snapshot().map {
//                EntityInstance(it.value)
//            }
//        )
//
//        _latestSnapshot.emit(level)
//        println("Saving $level")
//    }

    private fun resetWorld() {
        world.asEntityBag().forEach { world -= it }

        level.entities.forEach { components ->
            world.entity {
                world.loadSnapshotOf(it, components)
            }
        }
    }
}
