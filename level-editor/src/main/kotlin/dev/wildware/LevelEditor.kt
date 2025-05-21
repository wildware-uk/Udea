package dev.wildware

import com.badlogic.gdx.backends.lwjgl.LwjglAWTCanvas
import dev.wildware.systems.EditorSystem
import dev.wildware.udea.AssetLoader
import dev.wildware.udea.UdeaGameManager
import dev.wildware.udea.assets.Level
import java.awt.Canvas
import java.awt.Dimension

class LevelEditorCanvas(
    assetLoader: AssetLoader,
    level: Level
) {
    val gameManager = UdeaGameManager(assetLoader).apply {
        onCreate {
            setLevel(level,listOf(EditorSystem::class.java))
        }
    }
    val awtCanvasLwjgl = LwjglAWTCanvas(gameManager).apply {
        canvas.preferredSize = Dimension(512, 512)
    }

    fun getCanvas(): Canvas {
        return awtCanvasLwjgl.canvas
    }
}
