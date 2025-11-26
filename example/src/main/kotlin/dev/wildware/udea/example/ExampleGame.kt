package dev.wildware.udea.example

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import dev.wildware.udea.GameAssetLoader
import dev.wildware.udea.UdeaGame
import dev.wildware.udea.UdeaGameManager
import dev.wildware.udea.assets.Assets

class ExampleGame : UdeaGame {
    override fun onReady(gameManager: UdeaGameManager) {
        gameManager.setLevel(Assets["level/test_level"])
    }
}

fun testIt(testEnum: TestEnum) {

}

enum class TestEnum {
    A, B
}

fun main() {
    Lwjgl3Application(
        UdeaGameManager(ExampleGame(), GameAssetLoader("example/assets")),
        Lwjgl3ApplicationConfiguration().apply {
            setTitle("Example Game")
            setForegroundFPS(60)
            setWindowedMode(1920, 1080)
        })
}
