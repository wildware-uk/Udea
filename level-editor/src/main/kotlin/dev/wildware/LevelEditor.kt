package dev.wildware

import com.badlogic.gdx.backends.lwjgl.LwjglAWTCanvas
import dev.wildware.udea.UdeaGameManager
import dev.wildware.udea.assets.Level
import java.awt.Canvas
import java.awt.Dimension

class LevelEditorCanvas(
    level: Level
) {
    val gameManager = UdeaGameManager().apply {
        onCreate {
            setLevel(level)
        }
    }
    val awtCanvasLwjgl = LwjglAWTCanvas(gameManager).apply {
        canvas.preferredSize = Dimension(512, 512)
    }

    fun getCanvas(): Canvas {
        return awtCanvasLwjgl.canvas
    }
}
