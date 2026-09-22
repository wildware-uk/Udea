package com.example.newgame

import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import java.nio.file.Path
import kotlin.concurrent.thread

/**
 * The launcher a player runs: the game in a window. `./gradlew runWindow`.
 *
 * Four steps, in an order that is forced rather than chosen:
 *
 * 1. **What is drawn** is declared on a `RenderRegistry` - [NewGameScene].
 * 2. **The backend** opens the window and builds its drawing pipeline out of that registry, so the
 *    registry has to be complete first.
 * 3. **The host** is built over the backend, which is how the pipeline gets the world to draw.
 * 4. **The backend drives the host**: from here the window's frame loop runs the simulation at its
 *    fixed tick and draws between ticks. This thread waits for the window to close.
 *
 * The same `NewGame.definition()` a server and a test build is what runs here; only the backend is
 * new. A server is `./gradlew run`, which opens nothing.
 */
public object NewGameWindow {

    /** The game's `assets/` directory, absolute. `runWindow` sets it. */
    private const val ASSET_ROOT_PROPERTY: String = "newgame.assets.root"

    /** Closes the window after this many frames, when set. For a scripted run; a player leaves it unset. */
    private const val FRAMES_PROPERTY: String = "newgame.frames"

    /** Printed once the window has drawn this many frames, so a script can tell it is up. */
    private const val READY_FRAMES: Int = 60

    @JvmStatic
    public fun main(args: Array<String>) {
        val assetRoot = Path.of(
            checkNotNull(System.getProperty(ASSET_ROOT_PROPERTY)) {
                "-D$ASSET_ROOT_PROPERTY is not set; `runWindow` in game/build.gradle.kts sets it to " +
                    "game/assets, where the model files are"
            },
        )
        val frameLimit = System.getProperty(FRAMES_PROPERTY)?.toInt()

        val registry = RenderRegistry()
        NewGameScene.register(registry, assetRoot, NewGameAssets.registry)
        val backend = KoolBackend.start(RenderMode.Windowed, WindowConfig(title = "new-game"), registry)
        backend.use {
            val host = GameHost(RenderMode.Windowed, NewGame.definition(), backend)
            var frames = 0
            backend.drive { seconds ->
                host.frame(seconds)
                frames++
                if (frames == READY_FRAMES) println("new-game: window open, $frames frames drawn")
                // Closed from a thread of its own: `close` waits for the frame loop to end, and
                // this lambda *is* the frame loop.
                if (frames == frameLimit) thread(name = "new-game-close") { backend.close() }
            }
            // Until the player closes the window, or the frame limit closes it.
            backend.awaitExit()
        }
        println("new-game: window closed")
    }
}
