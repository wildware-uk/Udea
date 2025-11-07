package dev.wildware

import com.badlogic.gdx.backends.lwjgl.LwjglAWTCanvas
import dev.wildware.udea.AssetLoader
import dev.wildware.udea.UdeaGameManager
import java.awt.Canvas
import java.awt.Dimension

open class GameEditorCanvas(
    assetLoader: AssetLoader,
    init: GameEditorCanvas.()->Unit = {}
) {
    val gameManager = UdeaGameManager(assetLoader, isEditor = true)

    val awtCanvasLwjgl = LwjglAWTCanvas(gameManager).apply {
        canvas.preferredSize = Dimension(512, 512)
    }

    fun getCanvas(): Canvas {
        return awtCanvasLwjgl.canvas
    }

    init {
        init()
    }
}
