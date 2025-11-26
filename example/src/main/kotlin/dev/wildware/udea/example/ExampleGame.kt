package dev.wildware.udea.example

import com.badlogic.gdx.assets.loaders.resolvers.InternalFileHandleResolver
import com.badlogic.gdx.assets.loaders.resolvers.PrefixFileHandleResolver
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import dev.wildware.udea.GameAssetLoader
import dev.wildware.udea.UdeaGame
import dev.wildware.udea.UdeaGameManager
import dev.wildware.udea.assets.Assets
import ktx.assets.toInternalFile

class ExampleGame : UdeaGame {
    override fun onReady(gameManager: UdeaGameManager) {
        gameManager.setLevel(Assets["level/test_level"])
    }
}


fun main() {
    val assetDir = "example/src/main/resources/assets"
    Lwjgl3Application(
        UdeaGameManager(
            ExampleGame(),
            { GameAssetLoader(assetDir.toInternalFile()) },
            fileHandleResolver = PrefixFileHandleResolver(
                InternalFileHandleResolver(),
                assetDir
            )
        ),
        Lwjgl3ApplicationConfiguration().apply {
            setTitle("Example Game")
            setForegroundFPS(60)
            setWindowedMode(1920, 1080)
        })
}
