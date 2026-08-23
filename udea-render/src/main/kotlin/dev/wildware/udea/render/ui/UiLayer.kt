package dev.wildware.udea.render.ui

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.render.FrameTime
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderSystem

/**
 * The scene2d layer: one [Stage], one [ScreenViewport], and the screen currently mounted in it.
 *
 * Runs at [RenderPhase.UI] — **before** the capture point, on purpose. Game UI is part of the
 * game: an agent asking for a screenshot to check whether an ability is on cooldown needs the
 * cooldown ring in the picture. The thing an agent must not see is the *agent activity
 * overlay*, which is an `OverlaySystem` in [RenderPhase.Overlay] and is a different type
 * drawing on a different surface (spec 3.7).
 *
 * ## What it replaces
 *
 * `GameScreen` owned the `ScreenViewport`, the `Stage` and the skin
 * (`common/UdeaGameManager.kt:93-125`) alongside the Box2D world, the ECS world, the network
 * systems and level loading. `stage.act`/`stage.draw` were two lines buried in the middle of
 * `GameScreen.render` (`UdeaGameManager.kt:228`), between the world tick and the debug keys.
 *
 * ## The act delta is clamped, and not to the same figure as everything else
 *
 * `stage.act(min(frameSeconds, 1/30))`. scene2d `Action`s advance by the delta they are given,
 * and a single long frame — a breakpoint, a shader compile, a GC pause — would run a fade or a
 * `moveTo` straight to its end state, which looks like the animation never played. The 1/30s
 * ceiling is tighter than the pipeline's own [dev.wildware.udea.render.RenderPipeline.MAX_FRAME_SECONDS]
 * because a UI action is short: a quarter-second step through a 200ms fade is still the whole
 * fade.
 */
public class UiLayer(
    /** Wall seconds per frame. A stage's actions are wall-timed and never simulated. */
    private val frameTime: FrameTime,
    /** Builds the stage. Injected so a test can drive one with no GL context behind it. */
    stageFactory: (ScreenViewport) -> Stage = { Stage(it) },
) : RenderSystem, Disposable {

    /** The UI viewport: one world unit per pixel, so scene2d lays out in pixels. */
    public val viewport: ScreenViewport = ScreenViewport()

    /** The stage every mounted [UiScreen] lives in. */
    public val stage: Stage = stageFactory(viewport)

    /** Times [Stage.act] has been called. A health signal, and what `UiLayerTest` counts. */
    public var actCount: Long = 0L
        private set

    private var mounted: Mounted? = null

    private var viewportWidth: Int = 0
    private var viewportHeight: Int = 0

    init {
        // Carried forward from GameScreen's init (`UdeaGameManager.kt:184`), because it is a
        // real fix rather than incidental: without it, a text field keeps keyboard focus after
        // the player clicks away from it, and every subsequent key press is swallowed by an
        // invisible widget instead of reaching the game.
        stage.addListener(
            object : ClickListener() {
                override fun clicked(event: InputEvent, x: Float, y: Float) {
                    if (event.target == stage.root) stage.keyboardFocus = null
                }
            },
        )
    }

    override fun onBind(world: World, ctx: GameContext): Unit = Unit

    override fun render(target: OffscreenTarget, alpha: Float) {
        fitTo(target)
        viewport.apply()
        stage.act(frameTime.frameSeconds.coerceAtMost(MAX_ACT_SECONDS))
        actCount++
        stage.draw()
    }

    /**
     * Replaces the mounted screen, disposing the one that was there.
     *
     * One screen at a time, and swapping is a single call: two half-mounted screens is how the
     * old tree ended up with a loading screen's actors still receiving clicks behind a menu.
     */
    public fun show(screen: UiScreen) {
        hide()
        val root = screen.build(stage)
        stage.addActor(root)
        mounted = Mounted(screen, root)
    }

    /** Removes the mounted screen, if any. */
    public fun hide() {
        val current = mounted ?: return
        mounted = null
        current.root.remove()
        current.screen.dispose()
    }

    /**
     * Disposes the stage and whatever screen is mounted.
     *
     * Registered in [dev.wildware.udea.render.RenderTargets.owned] by whoever builds the
     * pipeline, so the pipeline's reverse-order disposal covers it. The old tree disposed a
     * stage in three different places and none of them in a `finally`.
     */
    override fun dispose() {
        hide()
        stage.dispose()
    }

    /**
     * Sizes the UI viewport to the surface being drawn into.
     *
     * `centerCamera = true`, the opposite of the world viewport: a `ScreenViewport`'s job is to
     * put pixel `(0, 0)` at the bottom-left corner of whatever it is drawing on, so its camera
     * belongs in the middle of the drawable area rather than wherever it was last.
     */
    private fun fitTo(target: OffscreenTarget) {
        if (target.width == viewportWidth && target.height == viewportHeight) return
        viewportWidth = target.width
        viewportHeight = target.height
        viewport.update(target.width, target.height, true)
    }

    /** A screen and the actor it built, so [hide] can remove exactly what [show] added. */
    private class Mounted(val screen: UiScreen, val root: Actor)

    public companion object {

        /**
         * Longest delta a scene2d [Stage] is advanced by in one frame.
         *
         * Tighter than the pipeline's frame clamp because UI actions are short: a third of a
         * second through a 200ms fade is the whole fade, and anything longer is a stall being
         * mistaken for elapsed time.
         */
        public const val MAX_ACT_SECONDS: Float = 1f / 30f
    }
}
